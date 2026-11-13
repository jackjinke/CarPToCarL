"""Observed ID8 protocol roles: authenticated control/events, iAP and resources."""
import asyncio
import copy
import json
import plistlib
import struct
import time
from pathlib import Path
from .wire import Connection, hkdf
from .pairing import Identity, Pairing
from .iap import IapPeer
from .media import AudioReceiver, VideoReceiver
from .shutdown import Shutdown

FIXTURES = Path(__file__).with_name('fixtures')


def capabilities():
    value = json.loads((FIXTURES / 'receiver.json').read_text())
    for hid in value['hidDevices']:
        hid['hidDescriptor'] = bytes.fromhex(hid['hidDescriptor']['hex'])
    return value


class DatagramRole(asyncio.DatagramProtocol):
    def __init__(self, session, role):
        self.session, self.role = session, role
        self.transport = None

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, address):
        now = time.monotonic()
        if self.role == 'keepalive':
            if len(data) != 4:
                self.session.fail('keepalive-wire', 'keepalive must be four bytes')
            else:
                self.session.keepalive.append(now)
        elif self.role == 'timing':
            if len(data) != 32 or data[1] & 127 not in (82, 83):
                self.session.fail('timing-wire', 'malformed timing packet')
                return
            self.session.timing.append(now)
            if data[1] & 127 == 82:
                ntp = int((time.time() + 2208988800) * 2**32)
                reply = bytes([0x80, 0xD3]) + data[2:4] + b'\0' * 4 + data[24:32] + struct.pack('>QQ', ntp, ntp)
                self.transport.sendto(reply, address)
        elif self.role == 'audio-control':
            # RTP sync packets carry the sender's audio/NTP clock, not audio samples.
            if len(data) < 8:
                self.session.fail('audio-control', 'truncated RTP control packet')


class ReceiverSession:
    def __init__(self, harness, reader, writer, epoch):
        self.harness, self.epoch = harness, epoch
        self.directory, self.transcript, self.host = harness.directory, harness.transcript, harness.host
        self.control = Connection(reader, writer, self.transcript, f'control-{epoch}')
        self.event = None
        self.pairing = Pairing(harness.identity, harness.fault == 'negative-bad-auth')
        self.shared = None
        self.fault = harness.fault
        self.initialized = self.recorded = False
        self.recorded_streams = set()
        self.audio_record_delays = 0
        self.audio_setups = []
        self.record_requests = []
        self.info = capabilities()
        self.resources = {1: dict(entity=2, permanentEntity=2), 2: dict(entity=2, permanentEntity=2)}
        if getattr(harness, 'scenario_name', None) in ('car-take-during-claim', 'initial-source-cancel'):
            self.info['modes']['resources'] = [
                item for item in self.info['modes']['resources'] if item['resourceID'] != 2]
            self.info['modes']['initialPermanentEntity'] = [
                dict(resourceID=2, permanentEntity=1, takeConstraint=100, borrowConstraint=100)]
            self.resources[2] = dict(entity=1, permanentEntity=1)
        self.resource_revision = 0
        (self.directory / f'receiver-input-{epoch}.plist').write_bytes(
            plistlib.dumps(self.info, fmt=plistlib.FMT_BINARY))
        self.yield_at = {}
        self.servers, self.datagrams, self.streams, self.connections = [], [], {}, {self.control}
        self.video_observations = harness.video_observations
        self.audio_observations = harness.audio_observations
        self.tones = harness.tones
        self.screen_keepalive, self.keepalive, self.timing, self.feedback = [], [], [], []
        self.sender_timing_port = 0
        self.timing_responses = []
        self.timing_started = False
        self.mode_announcements = []
        self.hold_audio_take = False
        self.held_audio_take = None
        self.audio_take_released = asyncio.Event()
        self.held_audio_setup = None
        self.initial_audio_setup_id = None
        self.audio_setup_hold_used = False
        self.teardowns = []
        self.closed = False
        self.tasks = set()
        self.iap = IapPeer(self.send_event, self.transcript, self.fail,
                           withhold=self.fault == 'negative-iap-auth',
                           windowed=getattr(harness, 'scenario_name', None) == 'iap-window')

    def track_current(self):
        self.harness.tasks.add(asyncio.current_task())
        self.tasks.add(asyncio.current_task())

    def untrack_current(self):
        self.harness.tasks.discard(asyncio.current_task())
        self.tasks.discard(asyncio.current_task())

    def fail(self, check, detail):
        self.harness.fail(check, detail)

    async def udp(self, role):
        transport, _ = await asyncio.get_running_loop().create_datagram_endpoint(
            lambda: DatagramRole(self, role), local_addr=(self.host, 0))
        self.datagrams.append(transport)
        return transport.get_extra_info('sockname')[1]

    async def run(self):
        self.track_current()
        try:
            while not self.closed:
                line, headers, body = await self.control.receive()
                if line.startswith(('RTSP/', 'HTTP/')):
                    self.control.response(line, headers, body)
                    continue
                await self.request(self.control, line, headers, body)
        except (EOFError, asyncio.IncompleteReadError, ConnectionError):
            self.transcript.add('control-closed', epoch=self.epoch)
        except Exception as error:
            self.fail('control-wire', f'{type(error).__name__}: {error}')
        finally:
            self.control.closed = True
            self.untrack_current()

    async def request(self, conn, line, headers, body):
        parts = line.split()
        if len(parts) != 3:
            raise ValueError('malformed RTSP request line')
        method, path, version = parts
        corrupted_proof = self.fault == 'negative-bad-auth' and any(
            session.pairing.corrupt_proof_sent for session in self.harness.sessions)
        if corrupted_proof and (path == '/pair-verify' or method == 'SETUP'):
            self.fail('bad-auth-continued', f'{method} {path} followed a corrupted SRP proof')
        if path in ('/pair-setup', '/pair-verify'):
            if conn is not self.control or conn.read_key:
                raise ValueError('pairing on established/encrypted channel')
            response = self.pairing.setup(body) if path == '/pair-setup' else self.pairing.verify(body)
            await conn.reply(headers, raw=response)
            if self.pairing.verified:
                self.shared = self.pairing.shared
                conn.encrypt(hkdf(self.shared, 'Control-Salt', 'Control-Write-Encryption-Key'),
                             hkdf(self.shared, 'Control-Salt', 'Control-Read-Encryption-Key'))
                self.harness.authenticated.append(self.epoch)
            return
        if not self.pairing.verified or conn.read_key is None:
            await conn.reply(headers, status=403)
            return
        if path == '/info' and method in ('GET', 'POST'):
            await conn.reply(headers, self.info)
        elif method == 'OPTIONS':
            await conn.reply(headers)
        elif method == 'SETUP':
            value = plistlib.loads(body)
            if not isinstance(value, dict):
                raise ValueError('SETUP body is not dictionary')
            await self.setup(conn, headers, value)
        elif method == 'RECORD':
            # BMW accepts RECORD before its independent iAP control negotiation completes.
            if not self.initialized or not self.streams:
                await conn.reply(headers, status=455)
            else:
                if (getattr(self.harness, 'scenario_name', None) == 'audio-record-delay'
                        and any(key not in self.recorded_streams and stream.description['type'] in (100, 102)
                                for key, stream in self.streams.items())):
                    self.audio_record_delays += 1
                    await asyncio.sleep(1)
                self.recorded = True
                self.recorded_streams.update(self.streams)
                self.record_requests.append(dict(t=time.monotonic(), streams=list(self.streams)))
                self.harness.recorded.append(self.epoch)
                await conn.reply(headers, {'audioLatency': 11025})
        elif method == 'TEARDOWN':
            value = plistlib.loads(body) if body else {}
            for item in value.get('streams', []):
                key = item.get('streamConnectionID')
                stream = self.streams.pop(key, None)
                if stream is None or stream.description['type'] != item.get('type'):
                    await conn.reply(headers, status=455)
                    return
                await stream.close()
                self.recorded_streams.discard(key)
                self.teardowns.append(key)
            if not body:
                self.recorded = False
                self.teardowns.append('session')
            await conn.reply(headers)
        elif path == '/command' and method == 'POST':
            await self.command(conn, headers, plistlib.loads(body))
        elif path == '/feedback' and method == 'POST':
            self.feedback.append(time.monotonic())
            await conn.reply(headers, {'streams': [dict(type=stream.description['type'],
                               streamConnectionID=key, sampleTime=len(self.audio_observations),
                               timestampRawNs=time.monotonic_ns(), sr=44100)
                               for key, stream in self.streams.items()]})
        elif method in ('GET_PARAMETER', 'SET_PARAMETER'):
            if not self.initialized:
                await conn.reply(headers, status=455)
            else:
                await conn.reply(headers, raw=b'volume: -12.000000\r\n' if method == 'GET_PARAMETER' else b'')
        else:
            await conn.reply(headers, status=501)

    async def setup(self, conn, headers, value):
        if 'streams' not in value:
            if self.initialized:
                await conn.reply(headers, status=455)
                return
            if not isinstance(value.get('timingPort'), int) or value['timingPort'] == 0:
                await conn.reply(headers, status=400)
                return
            server = await asyncio.start_server(self.accept_event, self.host, 0)
            self.servers.append(server)
            event_port = server.sockets[0].getsockname()[1]
            timing_port, keepalive_port = await self.udp('timing'), await self.udp('keepalive')
            self.initialized = True
            self.sender_timing_port = value['timingPort']
            await conn.reply(headers, {'eventPort': event_port, 'timingPort': timing_port,
                                      'keepAlivePort': keepalive_port,
                                      'enabledFeatures': value.get('features', [])})
            return
        if not self.initialized:
            await conn.reply(headers, status=455)
            return
        results = []
        for description in value['streams']:
            kind = description.get('type')
            if kind == 130:
                # This BMW event/control-iAP role does not provide a type130 TCP tunnel.
                results.append({'type': 130})
                continue
            key = description.get('streamConnectionID')
            if not isinstance(key, int) or key <= 0 or key in self.streams:
                await conn.reply(headers, status=400)
                return
            if kind == 110:
                if description.get('uuid') != self.info['displays'][0]['uuid']:
                    await conn.reply(headers, status=400)
                    return
                receiver = VideoReceiver(self, description)
                port = await receiver.start()
                results.append(dict(type=kind, streamConnectionID=key, dataPort=port))
            elif kind in (100, 102):
                accepted = any(item['type'] == kind and item['audioType'] == description.get('audioType')
                               and item.get('audioOutputFormats', 0) & description.get('audioFormat', 0)
                               for item in self.info['audioFormats'])
                if not accepted or self.resources[2]['entity'] != 1 or description.get('input'):
                    await conn.reply(headers, status=455)
                    return
                receiver = AudioReceiver(self, description)
                port, control = await receiver.start(), await self.udp('audio-control')
                results.append(dict(type=kind, streamConnectionID=key, dataPort=port, controlPort=control))
            else:
                await conn.reply(headers, status=461)
                return
            self.streams[key] = receiver
            if kind in (100, 102):
                self.audio_setups.append(key)
        if (getattr(self.harness, 'scenario_name', None) == 'initial-source-cancel'
                and self.audio_setups and not self.audio_setup_hold_used):
            self.audio_setup_hold_used = True
            self.initial_audio_setup_id = self.audio_setups[-1]
            self.held_audio_setup = asyncio.Event()
            self.transcript.add('audio-setup-held', stream=self.initial_audio_setup_id)
            await asyncio.wait_for(self.held_audio_setup.wait(), 8)
        await conn.reply(headers, {'streams': results})

    async def command(self, conn, headers, value):
        if not isinstance(value, dict) or not isinstance(value.get('params', {}), dict):
            await conn.reply(headers, {'status': 1})
            return
        kind, params = value.get('type'), value.get('params', {})
        self.transcript.add('command', channel=conn.channel, command=kind,
                            params=params if kind != 'iAPSendMessage' else {'bytes': len(params.get('data', b''))})
        if kind == 'iAPSendMessage':
            await conn.reply(headers, {'status': 0})
            # Separate task: incoming event responses must be read before waiting on them.
            self.harness.task(self.iap.feed(params['data']))
        elif kind == 'modesChanged':
            resources = params.get('resources')
            if not isinstance(resources, list) or not isinstance(params.get('appStates'), list):
                await conn.reply(headers, {'status': 1})
                return
            next_resources = copy.deepcopy(self.resources)
            for item in resources:
                rid = item.get('resourceID')
                if rid not in (1, 2) or item.get('entity') not in (0, 1, 2) or item.get('permanentEntity') not in (0, 1, 2):
                    await conn.reply(headers, {'status': 1})
                    return
                if rid == 2 and item['entity'] == 1 and self.resources[2]['entity'] != 1:
                    # User/source playback or an actual navigation announcement is required.
                    if not self.harness.source.playing and not self.harness.source.navigation:
                        self.fail('radio-background-take', 'phone claimed audio without source playback')
                        await conn.reply(headers, {'status': 1})
                        return
                next_resources[rid] = dict(item)
            held = False
            publication_revision = self.resource_revision
            if self.hold_audio_take and next_resources[2]['entity'] == 1 and self.harness.source.playing:
                self.held_audio_take = asyncio.Event()
                self.transcript.add('audio-take-held', epoch=self.epoch)
                await asyncio.wait_for(self.held_audio_take.wait(), 8)
                self.hold_audio_take = False
                held = True
            if publication_revision == self.resource_revision:
                returned_at = time.monotonic()
                for resource, next_state in next_resources.items():
                    if next_state['entity'] == 2 and self.resources[resource]['entity'] != 2:
                        self.yield_at[resource] = returned_at
                self.resources = next_resources
            self.mode_announcements.append(dict(t=time.monotonic(), **params))
            await conn.reply(headers, {'status': 0})
            if held:
                self.audio_take_released.set()
        elif kind in ('hidSetInputMode', 'setNightMode', 'setLimitedUI'):
            await conn.reply(headers, {'status': 0})
        else:
            await conn.reply(headers, {'status': 1})

    async def accept_event(self, reader, writer):
        self.track_current()
        conn = Connection(reader, writer, self.transcript, f'event-{self.epoch}')
        conn.encrypt(hkdf(self.shared, 'Events-Salt', 'Events-Read-Encryption-Key'),
                     hkdf(self.shared, 'Events-Salt', 'Events-Write-Encryption-Key'))
        self.event = conn
        self.connections.add(conn)
        try:
            while not self.closed:
                line, headers, body = await conn.receive()
                if line.startswith(('RTSP/', 'HTTP/')):
                    conn.response(line, headers, body)
                else:
                    await self.request(conn, line, headers, body)
        except (EOFError, asyncio.IncompleteReadError, ConnectionError):
            pass
        except Exception as error:
            self.fail('event-wire', f'{type(error).__name__}: {error}')
        finally:
            conn.closed = True
            self.untrack_current()

    async def send_event(self, kind, params):
        deadline = time.monotonic() + 5
        while self.event is None:
            if time.monotonic() > deadline:
                raise TimeoutError('event channel not connected')
            await asyncio.sleep(.02)
        return await self.event.command(kind, params)

    async def change_resource(self, resource, take):
        item = dict(resourceID=resource, transferType=1 if take else 2)
        if take:
            item.update(transferPriority=500, takeConstraint=500, borrowConstraint=100)
        line, response = await self.send_event('changeModes', {'resources': [item]})
        if '200' not in line or response.get('status') != 0:
            raise AssertionError('resource change rejected')
        self.resources[resource] = dict(entity=2 if take else 0, permanentEntity=2 if take else 0)
        self.resource_revision += 1
        if take:
            self.yield_at[resource] = time.monotonic()
        return response

    async def touch(self):
        # BMW descriptor: tip-switch bit followed by little-endian 16-bit X/Y.
        for pressed in (1, 0):
            await self.send_event('hidSendReport', {'uuid': '1', 'hidReport': struct.pack('<BHH', pressed, 600, 400)})
            await asyncio.sleep(.08)

    async def close(self):
        if self.closed:
            return
        self.closed = True
        shutdown = Shutdown()
        await shutdown.wait('iAP acknowledgements', self.iap.close())
        for server in self.servers:
            server.close()
        for stream in list(self.streams.values()):
            await shutdown.wait('media receiver', stream.close())
        for conn in list(self.connections):
            await shutdown.wait('RTSP connection', conn.close())
        for transport in self.datagrams:
            transport.close()
        await shutdown.cancel(self.tasks)
        for server in self.servers:
            await shutdown.wait('event server', server.wait_closed())
        shutdown.finish()
