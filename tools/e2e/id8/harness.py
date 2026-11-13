"""Public async runner interface and independently specified end-effect scenarios."""
import asyncio
import inspect
import json
import time
from pathlib import Path
from .wire import Transcript
from .pairing import Identity
from .peer import ReceiverSession, FIXTURES
from .timing import probe
from .shutdown import Shutdown
from .source import Source, command, stream, module_status, WIDTH, HEIGHT, FPS
from .audio_contract import concurrent_audio, track_transition, explicit_source_intent, initial_source_cancel, save_quality

SCENARIOS = ('baseline', 'late-invite', 'duplicate-invite', 'screen-yield-return',
             'radio-yield-return', 'navigation', 'peer-reset', 'teardown-reconnect',
             'network-drop-reconnect', 'negative-bad-auth', 'negative-iap-auth',
             'negative-no-audio', 'negative-no-video', 'source-reconnect', 'stale-audio-take',
             'car-take-during-claim', 'concurrent-audio', 'track-transition', 'iap-window',
             'source-start-order', 'audio-record-delay', 'explicit-source-intent', 'initial-source-cancel')


class OracleFailure(AssertionError):
    def __init__(self, check, detail):
        super().__init__(f'{check}: {detail}')
        self.check, self.detail = check, detail


class Harness:
    """Run one scenario per instance; ports/services start before normal app connect.

    driver is async: connect/disconnect perform the normal production lifecycle;
    ctrl-endpoint returns an ADB-forwarded listener (host, port). Optional OS network
    actions use network-down/network-up. The driver must never supply a success oracle.
    """
    source_ports = Source.ports

    def __init__(self, artifact_dir, host='0.0.0.0', control_port=7000, source_host='127.0.0.1', scenario=None):
        self.directory = Path(artifact_dir)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.host, self.control_port = host, control_port
        self.transcript = Transcript(self.directory)
        self.source = Source(self.directory, self.transcript, source_host)
        self.scenario_name = scenario
        self.source.ordered_start_fault = scenario == 'source-start-order'
        self.source.playing = scenario == 'initial-source-cancel'
        self.identity = Identity()
        self.server = None
        self.sessions = []
        self.tasks = set()
        self.errors = []
        self.checks = []
        self.fault = None
        self.fault_packets = dict(audio=0, video=0)
        self.authenticated, self.recorded = [], []
        self.video_observations, self.audio_observations, self.tones = [], [], []
        self.audio_quality = []
        self.closed = False
        self.driver = None
        self.started_at = time.monotonic()
        self.deadline = 0
        self.scenario_started = False

    def fail(self, check, detail):
        entry = dict(check=check, detail=detail)
        if entry not in self.errors:
            self.errors.append(entry)
            self.transcript.add('oracle-failure', **entry)

    def task(self, coroutine):
        task = asyncio.create_task(coroutine)
        self.tasks.add(task)
        def completed(task):
            self.tasks.discard(task)
            if not task.cancelled():
                error = task.exception()
                if error:
                    self.fail('peer-task', f'{type(error).__name__}: {error}')
        task.add_done_callback(completed)
        return task

    async def start(self):
        try:
            await self.source.start()
            self.server = await asyncio.start_server(self.accept, self.host, self.control_port)
            self.control_port = self.server.sockets[0].getsockname()[1]
        except BaseException:
            await self.close()
            raise
        manifest = dict(schema=1, control_port=self.control_port, source_ports=self.source_ports,
                        source=dict(width=WIDTH, height=HEIGHT, fps=FPS, music_hz=440, navigation_hz=880),
                        receiver=json.loads((FIXTURES / 'receiver.json').read_text()),
                        provenance=json.loads((FIXTURES / 'provenance.json').read_text()),
                        contract=json.loads((FIXTURES / 'contract.json').read_text()))
        (self.directory / 'inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')

    async def accept(self, reader, writer):
        session = ReceiverSession(self, reader, writer, len(self.sessions) + 1)
        self.sessions.append(session)
        await session.run()

    @property
    def active(self):
        return next((session for session in reversed(self.sessions)
                     if session.pairing.verified and not session.control.closed), None)

    def ensure_clean(self):
        if self.source.errors:
            raise OracleFailure('source-fixture', self.source.errors[0])
        if self.errors:
            error = self.errors[0]
            raise OracleFailure(error['check'], error['detail'])

    def checked(self, check, **detail):
        entry = dict(check=check, passed=True, **detail)
        self.checks.append(entry)
        self.transcript.add('oracle-check', **entry)

    async def until(self, predicate, check, seconds=15):
        deadline = min(self.deadline, time.monotonic() + seconds)
        while time.monotonic() < deadline:
            self.ensure_clean()
            if predicate():
                return
            await asyncio.sleep(.05)
        self.ensure_clean()
        raise OracleFailure(check, 'required network end-effect absent before deadline')

    async def observe(self, seconds):
        end = min(self.deadline, time.monotonic() + seconds)
        if end - time.monotonic() < seconds - .05:
            raise OracleFailure('scenario-timeout', 'insufficient scenario deadline')
        while time.monotonic() < end:
            self.ensure_clean()
            await asyncio.sleep(.05)

    def video_since(self, start, epoch=None):
        return [item for item in self.video_observations if item['t'] >= start
                and (epoch is None or item['epoch'] == epoch)]

    def audio_since(self, start, epoch=None):
        return [item for item in self.audio_observations if item['t'] >= start
                and (epoch is None or item['epoch'] == epoch)]

    def has_tone(self, start, frequency, epoch=None):
        return any(item['t'] >= start and abs(item['frequency'] - frequency) < 15
                   and 500 < item['rms'] < 20000 and (epoch is None or item['epoch'] == epoch)
                   for item in self.tones)

    def continuity(self, observations, start, end, check, maximum_gap=1):
        times = [item['t'] for item in observations if start <= item['t'] <= end]
        if not times or max(b - a for a, b in zip([start] + times, times + [end])) > maximum_gap:
            raise OracleFailure(check, f'decoded media gap exceeds {maximum_gap}s')
        self.checked(check, duration=round(end - start, 3), max_gap=maximum_gap)

    async def projection(self, epoch=None, play=True, idle_radio=True):
        await self.until(lambda: self.active is not None and self.active.recorded,
                         'missing-iap' if self.fault == 'negative-iap-auth' else 'missing-authenticated-record', 25)
        session = self.active
        await self.until(lambda: session.iap.complete, 'missing-iap', 16)
        await self.until(lambda: session.iap.discovery_complete, 'missing-iap-app-discovery', 5)
        self.checked('iap-app-discovery')
        epoch = session.epoch if epoch is None else epoch
        await self.until(lambda: any(x['epoch'] == epoch and x['state'] == 'red'
                                     for x in self.video_observations), 'missing-video',
                         8 if self.scenario_name == 'source-start-order' else 12)
        self.checked('source-visible-geometry', width=2560, height=800, hu_canvas=[2662, 812])
        if not session.iap.complete or not session.control.encrypted_frames or not session.event.encrypted_frames:
            raise OracleFailure('authenticated-channels', 'control/event/iAP not independently authenticated')
        self.checked('authenticated-channels', epoch=epoch)
        start = time.monotonic()
        await self.observe(2)
        if self.audio_since(start) or idle_radio and session.resources[2]['entity'] == 1:
            raise OracleFailure('radio-background-take', 'idle source produced media or displaced car radio')
        if idle_radio:
            self.checked('idle-radio-preserved')
            await self.check_non_play_intent(session)
        else:
            self.checked('idle-source-no-media')
        if play:
            await self.play_via_touch(session)
        await self.start_timing(session)
        return session

    async def stale_audio_take(self, session):
        session.hold_audio_take = True
        start = time.monotonic()
        await session.touch()
        await self.until(lambda: session.held_audio_take is not None, 'audio-take-not-exercised', 10)
        await self.source.stop_music()
        await self.observe(.5)
        session.held_audio_take.set()
        await self.until(lambda: session.audio_take_released.is_set(), 'audio-take-acceptance', 3)
        await self.until(lambda: session.resources[2]['entity'] == 2, 'stale-audio-take-rollback', 6)
        await self.observe(2)
        if self.audio_since(start, session.epoch):
            raise OracleFailure('stale-audio-take-activation', 'delayed take acceptance activated stopped source audio')
        self.checked('stale-audio-take-rollback')
        self.checked('stale-audio-take-no-activation')

    async def car_take_during_claim(self, session):
        session.hold_audio_take = True
        await session.touch()
        await self.until(lambda: session.held_audio_take is not None, 'audio-take-not-exercised', 10)
        await session.change_resource(2, True)
        suppressed_since = time.monotonic()
        session.held_audio_take.set()
        await self.until(lambda: session.audio_take_released.is_set(), 'audio-take-acceptance', 3)
        await self.observe(1)
        await self.source.audio_init()
        await self.source.write(7240, command(65574, module_status(True)))
        await self.observe(3)
        if self.audio_since(suppressed_since, session.epoch) or session.resources[2]['entity'] != 2:
            raise OracleFailure('car-take-during-claim', 'old accepted claim/retry displaced newer car TAKE')
        self.checked('car-take-during-claim-suppressed')
        await self.source.stop_music()
        await self.observe(.5)
        start = time.monotonic()
        await self.source.begin_music()
        await self.until(lambda: self.has_tone(start, 440, session.epoch), 'fresh-play-after-car-take', 12)
        self.checked('fresh-play-after-car-take')

    async def start_timing(self, session):
        if session.timing_started:
            return
        endpoint = (await self.driver('udp-endpoint', session.sender_timing_port)
                    if self.driver else
                    (session.control.writer.get_extra_info('peername')[0], session.sender_timing_port))
        session.timing_started = True
        self.task(probe(session, endpoint))
        await self.until(lambda: len(session.timing_responses) >= 2, 'timing-response', 6)
        self.checked('timing-response', epoch=session.epoch)

    async def resumed_projection(self, previous, expect_audio):
        await self.until(lambda: self.active is not None and self.active.epoch > previous.epoch
                         and self.active.recorded, 'same-episode-control-rebuild', 25)
        current = self.active
        start = time.monotonic()
        await self.until(lambda: any(item['state'] == 'green'
                                    for item in self.video_since(start, current.epoch)),
                         'same-episode-video-rebuild', 10)
        await self.until(lambda: current.iap.complete, 'missing-iap', 16)
        await self.until(lambda: current.iap.discovery_complete, 'missing-iap-app-discovery', 5)
        if not current.iap.complete or current.shared == previous.shared:
            raise OracleFailure('fresh-pairing-after-reset', 'new media session lacks fresh pairing/iAP completion')
        await self.start_timing(current)
        if expect_audio:
            await self.until(lambda: self.has_tone(start, 440, current.epoch),
                             'same-episode-audio-rebuild', 12)
            self.checked('same-episode-audio-rebuild', new_epoch=current.epoch)
        else:
            await self.observe(3)
            if self.audio_since(start, current.epoch) or current.resources[2]['entity'] == 1:
                raise OracleFailure('radio-yield-rebuild', 'rebuilding helpers cleared same-episode radio suppression')
            self.checked('radio-yield-rebuild', new_epoch=current.epoch)
        return current

    async def play_via_touch(self, session):
        start = time.monotonic()
        await session.touch()
        await self.until(lambda: any(x['t'] >= start and x['state'] == 'green'
                                     and x['epoch'] == session.epoch for x in self.video_observations),
                         'missing-touch-feedback', 10)
        await self.until(lambda: self.has_tone(start, 440, session.epoch), 'missing-audio', 12)
        self.checked('touch-source-media-feedback', epoch=session.epoch)
        self.checked('music-decoded-440hz', epoch=session.epoch)

    async def check_non_play_intent(self, session):
        start = time.monotonic()
        await self.source.audio_init()
        await self.observe(.5)
        await self.source.write(9240, stream(0x30006, 0, self.source.pcm(882, 440, 44100, 2)))
        await self.observe(.5)
        await self.source.write(9240, stream(0x30002, 0, b''))
        await self.source.write(7240, command(65574, module_status(True)))
        await self.observe(.5)
        await self.source.write(7240, command(65574, module_status(False)))
        if self.audio_since(start) or session.resources[2]['entity'] == 1:
            raise OracleFailure('source-intent-proof', 'INIT/PCM without focus or focus alone displaced radio')
        self.checked('source-intent-proof')

    async def source_reconnect(self, session):
        epoch = session.epoch
        for cycle, frequency in enumerate((660, 550, 770), 1):
            start = time.monotonic()
            await self.source.reset_connections(music_hz=frequency)
            await self.until(lambda: any(frame['source_generation'] == self.source.generation
                                        for frame in self.video_since(start, epoch))
                             and self.has_tone(start, frequency, epoch), 'source-reconnect-media', 8)
            recovered_after = time.monotonic() - start
            if session.control.closed or self.active.epoch != epoch:
                raise OracleFailure('source-reconnect-control', 'source transport reset replaced healthy AirPlay session')
            await self.baseline_continuity(session, 3)
            self.checked('source-reconnect-media', cycle=cycle, maximum_recovery_seconds=8,
                         recovery_seconds=round(recovered_after, 3), same_control_epoch=epoch,
                         decoded_source_generation=self.source.generation, decoded_music_hz=frequency)

    async def baseline_continuity(self, session, seconds=6):
        start = time.monotonic()
        await self.observe(seconds)
        end = time.monotonic()
        video, audio = self.video_since(start, session.epoch), self.audio_since(start, session.epoch)
        self.continuity(video, start, end, 'video-continuity')
        self.continuity(audio, start, end, 'audio-continuity')
        elapsed = end - start
        decoded_fps = len(video) / elapsed
        if decoded_fps < FPS * .75:
            raise OracleFailure('video-frame-rate',
                                f'{decoded_fps:.2f} decoded fps for independent {FPS}fps source')
        self.checked('video-frame-rate', decoded_fps=round(decoded_fps, 2), minimum_fps=FPS * .75)
        decoded_seconds = sum(item['samples'] / item['rate'] for item in audio)
        if not elapsed - .35 <= decoded_seconds <= elapsed + .5:
            raise OracleFailure('audio-playout-coverage',
                                f'{decoded_seconds:.3f}s decoded samples for {elapsed:.3f}s elapsed')
        self.checked('audio-playout-coverage', elapsed=round(elapsed, 3),
                     decoded_seconds=round(decoded_seconds, 3), startup_tail_allowance=.35)
        if len({x['movement'] for x in video}) < 3:
            raise OracleFailure('video-motion', 'decoded picture is frozen')
        if not session.keepalive or not session.feedback or not session.screen_keepalive:
            raise OracleFailure('keepalive', 'required network keepalive role absent')
        for name, values, gap in (('udp', session.keepalive, 7), ('feedback', session.feedback, 3),
                                  ('screen', session.screen_keepalive, 3)):
            current = [x for x in values if x >= start]
            if max(b - a for a, b in zip([start] + current, current + [end])) > gap:
                raise OracleFailure('keepalive-continuity', f'{name} watchdog gap exceeds {gap}s')
        self.checked('video-motion')
        self.checked('keepalive-continuity')

    async def invite(self, driver, endpoint):
        if callable(endpoint):
            endpoint = endpoint()
            if inspect.isawaitable(endpoint):
                endpoint = await endpoint
        if endpoint is None:
            endpoint = await driver('ctrl-endpoint')
        reader, writer = await asyncio.open_connection(*endpoint)
        try:
            writer.write(b'GET /ctrl-int/1/connect HTTP/1.1\r\n'
                         b'Host: network-test\r\nAirPlay-Receiver-Device-ID: 02:00:00:00:00:01\r\n'
                         b'Connection: close\r\n\r\n')
            await writer.drain()
            head = await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'), 5)
            if not head.startswith(b'HTTP/1.1 200'):
                raise OracleFailure('ctrl-invite', 'production listener rejected invite')
            self.transcript.add('ctrl-invite', response=head.split(b'\r\n')[0].decode())
        finally:
            writer.close()
            await writer.wait_closed()

    async def radio_yield(self, session):
        await session.change_resource(2, True)
        await self.observe(1)
        start = time.monotonic()
        await self.source.audio_init()
        await self.source.write(7240, command(65574, module_status(True)))
        await self.observe(2)
        writer = self.source.writers.get(9240)
        if writer:
            writer.close()
        await self.observe(3)
        if self.audio_since(start) or session.resources[2]['entity'] == 1:
            raise OracleFailure('radio-yield-preserved', 'same source focus episode reclaimed car radio')
        self.checked('radio-yield-preserved')
        if self.driver is None:
            raise ValueError('radio suppression across rebuild requires normal lifecycle driver')
        previous = session
        previous.resetting = True
        await previous.close()
        await self.source.reset_connections()
        await self.driver('disconnect')
        await self.driver('connect')
        session = await self.resumed_projection(previous, expect_audio=False)
        await self.source.stop_music()
        await self.observe(.4)
        start = time.monotonic()
        await self.source.begin_music()
        await self.until(lambda: self.has_tone(start, 440), 'fresh-play-reacquisition', 12)
        self.checked('fresh-play-reacquisition')

    async def screen_return(self, session):
        await session.change_resource(1, True)
        await self.observe(1)
        start = time.monotonic()
        await self.observe(2)
        if self.video_since(start):
            raise OracleFailure('screen-yield', 'decoded video continued after screen yield')
        if not self.has_tone(start, 440):
            raise OracleFailure('screen-yield-audio', 'screen yield interrupted independent audio resource')
        self.checked('screen-yield')
        await session.send_event('requestUI', {})
        await self.until(lambda: len(self.video_since(time.monotonic() - .5, session.epoch)) > 1,
                         'screen-return', 12)
        self.checked('screen-return')

    async def navigation(self, session):
        await self.source.stop_music()
        await session.change_resource(2, True)
        await self.observe(1)
        start = time.monotonic()
        self.source.navigation = True
        try:
            await self.until(lambda: self.has_tone(start, 880), 'navigation-audio', 12)
            if session.resources[2].get('permanentEntity') != 2:
                raise OracleFailure('navigation-borrow', 'navigation stole permanent radio ownership')
            self.checked('navigation-borrow')
            await self.observe(2)
        finally:
            self.source.navigation = False
        await self.until(lambda: session.resources[2]['entity'] == 2, 'navigation-return', 8)
        await self.observe(1)
        start = time.monotonic()
        await self.observe(1)
        if self.audio_since(start):
            raise OracleFailure('navigation-return', 'audio continued after temporary borrow returned')
        self.checked('navigation-return')

    async def reconnect(self, session, driver, name):
        old_epoch = session.epoch
        if name == 'teardown-reconnect':
            await driver('disconnect')
            await self.until(lambda: 'session' in session.teardowns, 'session-teardown', 6)
            if session.streams:
                raise OracleFailure('stream-teardown', 'normal disconnect left negotiated streams alive')
            self.checked('stream-and-session-teardown')
        else:
            session.resetting = True
            if name == 'network-drop-reconnect':
                await driver('network-down')
            try:
                await session.close()
                await self.observe(1)
                await driver('disconnect')
            finally:
                if name == 'network-drop-reconnect':
                    await driver('network-up')
        if name == 'peer-reset':
            await self.source.reset_connections()
        else:
            await self.source.stop_music()
            self.source.touch_state = 0
        await driver('connect')
        await self.until(lambda: self.active is not None and self.active.epoch > old_epoch,
                         'fresh-pairing-after-reset', 20)
        current = (await self.resumed_projection(session, expect_audio=True)
                   if name == 'peer-reset' else await self.projection())
        if current.epoch == old_epoch or current.shared == session.shared:
            raise OracleFailure('fresh-pairing-after-reset', 'reconnect reused previous cryptographic session')
        await self.baseline_continuity(current, 3)
        self.checked('fresh-pairing-after-reset', old_epoch=old_epoch, new_epoch=current.epoch,
                     network_scope='OS WiFi toggle plus TCP reset; loopback ADB reverse does not model radio association'
                     if name == 'network-drop-reconnect' else 'whole control/event/media TCP closure')

    async def run_scenario(self, name, timeout=90, driver=None, ctrl_invite_endpoint=None):
        if name not in SCENARIOS:
            raise ValueError(f'unknown scenario {name}')
        if self.scenario_started:
            raise RuntimeError('one fresh Harness is required per scenario')
        self.scenario_started = True
        self.scenario_name = name
        self.source.ordered_start_fault = name == 'source-start-order'
        self.fault = name if name.startswith('negative-') else None
        self.deadline = time.monotonic() + timeout
        expected_failure = {'negative-bad-auth': 'missing-authenticated-control',
                            'negative-iap-auth': 'missing-iap', 'negative-no-audio': 'missing-audio',
                            'negative-no-video': 'missing-video'}.get(name)
        self.driver = driver
        oracle_rejected = False
        failures = []
        try:
            if driver:
                await driver('connect')
            if name == 'negative-bad-auth':
                await self.until(lambda: bool(self.authenticated), 'missing-authenticated-control', 8)
                raise OracleFailure('bad-auth-accepted', 'invalid SRP proof reached authenticated control')
            if name == 'negative-iap-auth':
                await self.until(lambda: self.active is not None and self.active.iap.challenge_seen,
                                 'iap-challenge-not-exercised', 20)
                await self.until(lambda: self.active.iap.complete, 'missing-iap', 16)
                raise OracleFailure('iap-false-auth', 'withheld challenge was accepted')
            if name == 'initial-source-cancel':
                session = await initial_source_cancel(self)
            else:
                session = await self.projection(
                    play=name not in ('stale-audio-take', 'car-take-during-claim'),
                    idle_radio=name != 'car-take-during-claim')
            if name in ('late-invite', 'duplicate-invite'):
                if driver is None and ctrl_invite_endpoint is None:
                    raise ValueError('ctrl invitation requires reachable production listener endpoint')
                start, epoch = time.monotonic(), session.epoch
                await self.invite(driver, ctrl_invite_endpoint)
                if name == 'duplicate-invite':
                    await self.invite(driver, ctrl_invite_endpoint)
                await self.baseline_continuity(session)
                if session.control.closed or self.active.epoch != epoch:
                    raise OracleFailure('late-invite-survival', 'invite retired a healthy projection session')
                self.checked('late-invite-survival')
            elif name == 'screen-yield-return':
                await self.screen_return(session)
            elif name == 'radio-yield-return':
                await self.radio_yield(session)
            elif name == 'navigation':
                await self.navigation(session)
            elif name == 'concurrent-audio':
                await concurrent_audio(self, session)
            elif name == 'track-transition':
                await track_transition(self, session)
            elif name == 'iap-window':
                await self.until(lambda: session.iap.discovery_responses >= 4,
                                 'iap-window-queued-replies', 5)
                await self.baseline_continuity(session)
                if session.iap.maximum_outstanding != 5:
                    raise OracleFailure('iap-peer-window-exercised',
                                        f'only {session.iap.maximum_outstanding} unacknowledged payloads observed')
                self.checked('iap-peer-window', maximum_outstanding=session.iap.maximum_outstanding,
                             advertised_window=5, delayed_ack_ms=150)
            elif name == 'source-start-order':
                if not self.source.video_connection_seen or not self.source.features_acknowledged:
                    raise OracleFailure('source-start-order-exercised', 'fixture did not reset video and acknowledge features')
                reset_at = self.source.initial_video_reset_at
                frames = self.video_since(reset_at, session.epoch)
                recovery = min(frame['t'] for frame in frames) - reset_at
                source_recreated = len(self.source.command_connections) != 1
                if recovery > 8 or source_recreated or len(self.sessions) != 1:
                    raise OracleFailure('source-start-recovery',
                                        f'recovery={recovery:.3f}s source_recreated={source_recreated} control_connections={len(self.sessions)}')
                await self.baseline_continuity(session)
                self.checked('source-start-order', feature_ack_delay_seconds=2,
                             recovery_seconds=round(recovery, 3), maximum_recovery_seconds=8,
                             initial_video_socket_reset=True, source_pairing_recreated=source_recreated,
                             control_connections=len(self.sessions),
                             source_command_connections=len(self.source.command_connections),
                             source_command_tcp_accepts=self.source.connection_counts.get(7240, 0))
            elif name == 'audio-record-delay':
                if session.audio_record_delays != 1:
                    raise OracleFailure('audio-record-delay-exercised', 'new audio RECORD was not held for one second')
                await self.baseline_continuity(session)
                self.checked('audio-record-activation', delayed_seconds=1, packets_before_record=0)
            elif name == 'explicit-source-intent':
                await explicit_source_intent(self, session)
            elif name == 'source-reconnect':
                await self.source_reconnect(session)
            elif name == 'stale-audio-take':
                await self.stale_audio_take(session)
            elif name == 'car-take-during-claim':
                await self.car_take_during_claim(session)
            elif name in ('peer-reset', 'teardown-reconnect', 'network-drop-reconnect'):
                if driver is None:
                    raise ValueError('reconnect scenario requires normal lifecycle driver')
                await self.reconnect(session, driver, name)
            else:
                await self.baseline_continuity(session, 60 if name == 'baseline' else 6)
            self.ensure_clean()
        except OracleFailure as error:
            failures.append(dict(check=error.check, detail=error.detail))
            exercised = {
                'negative-bad-auth': any(session.pairing.corrupt_proof_sent for session in self.sessions),
                'negative-iap-auth': any(session.iap.challenge_seen for session in self.sessions),
                'negative-no-audio': self.fault_packets['audio'] > 0,
                'negative-no-video': self.fault_packets['video'] > 0,
            }.get(name, False)
            oracle_rejected = bool(expected_failure == error.check and exercised and not self.errors)
        except Exception as error:
            failures.append(dict(check='unexpected-exception', detail=f'{type(error).__name__}: {error}'))
        for error in self.errors:
            if error not in failures:
                failures.append(error)
        outcome = dict(schema=1, scenario=name, passed=not failures, checks=self.checks,
                       failures=failures, oracle_rejected=oracle_rejected,
                       duration=round(time.monotonic() - self.started_at, 3),
                       evidence=dict(authenticated_epochs=self.authenticated, recorded_epochs=self.recorded,
                                     fault_packets=self.fault_packets,
                                     decoded_video_frames=len(self.video_observations),
                                     decoded_audio_blocks=len(self.audio_observations),
                                     observed_tones=self.tones[::max(1, len(self.tones) // 20)]))
        (self.directory / 'outcome.json').write_text(json.dumps(outcome, indent=2) + '\n')
        save_quality(self)
        return outcome

    async def close(self):
        if self.closed:
            return
        self.closed = True
        if self.server:
            self.server.close()
        shutdown = Shutdown()
        for session in self.sessions:
            await shutdown.wait('receiver session', session.close(), timeout=8)
        await shutdown.wait('CarLife source', self.source.close(), timeout=8)
        await shutdown.cancel(self.tasks)
        if self.server:
            await shutdown.wait('control server', self.server.wait_closed())
        cleanup = dict(passed=not shutdown.errors, failures=shutdown.errors)
        (self.directory / 'cleanup.json').write_text(json.dumps(cleanup, indent=2) + '\n')
        self.transcript.add('cleanup', **cleanup)
        self.transcript.close()
        shutdown.finish()
