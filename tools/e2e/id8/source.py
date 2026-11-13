"""Network-only CarLife phone source with touch-caused visible media feedback."""
import asyncio
import math
import struct
import time
from fractions import Fraction
import av
import numpy as np
from .shutdown import Shutdown

PORTS = (7240, 8240, 9240, 9241, 9242, 9340, 9440)
WIDTH, HEIGHT, FPS = 2560, 800, 20


def varint(value):
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def pb(field, value):
    if isinstance(value, bytes):
        return varint(field * 8 + 2) + varint(len(value)) + value
    return varint(field * 8) + varint(value)


def command(kind, body=b''):
    return struct.pack('>HHI', len(body), 0, kind) + body


def stream(kind, stamp, body):
    return struct.pack('>III', len(body), stamp & 0xffffffff, kind) + body


def module_status(active):
    return pb(1, 1) + pb(2, pb(1, 3) + pb(2, int(active)))


class Source:
    ports = PORTS

    def __init__(self, directory, transcript, host='127.0.0.1'):
        self.directory, self.transcript, self.host = directory, transcript, host
        self.servers, self.tasks, self.writers = [], set(), {}
        self.playing = False
        self.touch_state = 0
        self.generation = 0
        self.music_hz = 440
        self.navigation = False
        self.encoding = asyncio.Event()
        self.closed = False
        self.fault = None
        self.touch_times = []
        self.pcm_indices = {}
        self.errors = []
        self.pcm_artifacts = {}
        self.ordered_start_fault = False
        self.video_connection_seen = False
        self.features_acknowledged = False
        self.features_ack_pending = False
        self.encoder_initialized_after_features = False
        self.connection_counts = {}
        self.initial_video_reset_at = None
        self.command_connections = set()

    def task(self, coroutine):
        task = asyncio.create_task(coroutine)
        self.tasks.add(task)
        def completed(task):
            self.tasks.discard(task)
            if not task.cancelled():
                error = task.exception()
                if error:
                    self.errors.append(f'{type(error).__name__}: {error}')
        task.add_done_callback(completed)
        return task

    async def start(self):
        for port in PORTS:
            async def accept(reader, writer, port=port):
                await self.accept(port, reader, writer)
            self.servers.append(await asyncio.start_server(accept, self.host, port))
        self.task(self.video_loop())
        self.task(self.audio_loop())

    async def write(self, port, payload):
        writer = self.writers.get(port)
        if writer is None or writer.is_closing():
            return False
        try:
            writer.write(payload)
            await writer.drain()
            return True
        except (ConnectionError, BrokenPipeError):
            if self.writers.get(port) is writer:
                self.writers.pop(port, None)
            writer.close()
            return False

    async def accept(self, port, reader, writer):
        self.tasks.add(asyncio.current_task())
        self.writers[port] = writer
        self.connection_counts[port] = self.connection_counts.get(port, 0) + 1
        connection_id = self.connection_counts[port]
        self.transcript.add('source-connect', port=port, connection=connection_id)
        if port == 8240 and self.ordered_start_fault and not self.video_connection_seen:
            self.video_connection_seen = True
            self.initial_video_reset_at = time.monotonic()
            self.transcript.add('source-initial-video-reset')
            self.writers.pop(port, None)
            writer.close()
            self.tasks.discard(asyncio.current_task())
            return
        try:
            if port == 7240:
                self.features_acknowledged = False
                self.features_ack_pending = False
                self.encoder_initialized_after_features = False
                await self.write(port, command(65538, pb(1, 1)))
                await self.write(port, command(65540, pb(1, b'Independent fixture')))
                await self.write(port, command(65574, module_status(self.playing)))
            if port == 9240 and self.playing:
                await self.audio_socket_init()
                await self.write(9240, stream(0x30006, 0, self.pcm(882, self.music_hz, 44100, 2)))
                await self.write(7240, command(65574, module_status(True)))
            if port in (7240, 9340):
                while True:
                    head = await reader.readexactly(8)
                    size, reserved, kind = struct.unpack('>HHI', head)
                    if reserved != 0:
                        raise ValueError('CarLife reserved header not zero')
                    body = await reader.readexactly(size)
                    self.transcript.add('source-command', port=port, message=kind, size=size)
                    if port == 9340 and kind in (425985, 425986, 425987, 425989):
                        if kind in (425985, 425986, 425989):
                            self.touch_state = 1
                            self.touch_times.append(time.monotonic())
                            if not self.playing:
                                await self.begin_music()
                    if port == 7240:
                        self.command_connections.add(connection_id)
                        await self.handle_command(kind, body)
            else:
                while await reader.read(4096):
                    pass
        except (asyncio.IncompleteReadError, ConnectionError, EOFError):
            pass
        except Exception as error:
            self.errors.append(f'{type(error).__name__}: {error}')
        finally:
            if self.writers.get(port) is writer:
                self.writers.pop(port, None)
            writer.close()
            self.tasks.discard(asyncio.current_task())

    async def acknowledge_features(self, owner):
        if self.ordered_start_fault:
            await asyncio.sleep(2)
        if self.writers.get(7240) is not owner:
            return
        self.features_acknowledged = True
        self.transcript.add('source-features-ack')
        sent = await self.write(7240, command(65570))
        if self.writers.get(7240) is owner:
            self.features_ack_pending = False
            self.features_acknowledged = sent

    async def handle_command(self, kind, body):
        if kind == 98386:
            if not self.features_ack_pending:
                self.features_ack_pending = True
                self.task(self.acknowledge_features(self.writers.get(7240)))
        elif kind in (98311, 98315):
            self.encoder_initialized_after_features = self.features_acknowledged
            self.transcript.add('source-encoder-init', after_features=self.features_acknowledged)
            await self.write(7240, command(65544, pb(1, WIDTH) + pb(2, HEIGHT) + pb(3, FPS)))
        elif kind == 98313:
            if not self.ordered_start_fault or self.encoder_initialized_after_features:
                self.encoding.set()
            else:
                self.transcript.add('source-start-before-features-ignored')
        elif kind == 98316:
            await self.write(7240, command(65549, pb(1, FPS)))
        elif kind == 131074:
            await self.write(7240, command(131074))

    async def audio_init(self):
        fmt = pb(1, 44100) + pb(2, 2) + pb(3, 16)
        await self.write(9240, stream(0x30001, 0, fmt))

    async def audio_socket_init(self):
        await self.audio_init()
        await self.write(9240, stream(0x30002, 0, b''))
        await self.audio_init()

    async def begin_music(self):
        # Independently observed MusicAudioHelper socket-init sequence. STOP is not RESUME.
        await self.audio_socket_init()
        self.playing = True
        await self.write(9240, stream(0x30006, 0, self.pcm(882, self.music_hz, 44100, 2)))
        await self.write(7240, command(65574, module_status(True)))

    async def stop_music(self):
        self.playing = False
        await self.write(9240, stream(0x30002, 0, b''))
        await self.write(7240, command(65574, module_status(False)))

    async def next_track(self, frequency, reinitialize=True):
        self.playing = False
        await self.write(9240, stream(0x30002, 0, b''))
        self.music_hz = frequency
        if reinitialize:
            await self.audio_init()
        self.playing = True
        await self.write(9240, stream(0x30006, 0, self.pcm(882, frequency, 44100, 2)))
        # A track boundary need not change the music module's focus state.
        self.transcript.add('source-next-track', frequency=frequency, reinitialize=reinitialize)

    def pcm(self, count, frequency, rate, channels):
        key = (rate, channels)
        index = self.pcm_indices.get(key, 0)
        indices = np.arange(index, index + count)
        samples = (np.sin(indices * (2 * math.pi * frequency / rate)) * 11000).astype('<i2')
        self.pcm_indices[key] = index + count
        data = np.repeat(samples[:, None], channels, axis=1).tobytes()
        if key not in self.pcm_artifacts:
            self.pcm_artifacts[key] = (self.directory / f'source-{rate}-{channels}.s16le').open('wb')
        self.pcm_artifacts[key].write(data)
        return data

    async def audio_loop(self):
        navigation_active = False
        deadline = time.monotonic()
        while not self.closed:
            stamp = int(time.monotonic() * 1000)
            if self.playing:
                await self.write(9240, stream(0x30006, stamp, self.pcm(882, self.music_hz, 44100, 2)))
            if self.navigation:
                if not navigation_active:
                    await self.write(9241, stream(0x40001, stamp, pb(1, 16000) + pb(2, 1) + pb(3, 16)))
                await self.write(9241, stream(0x40003, stamp, self.pcm(320, 880, 16000, 1)))
            elif navigation_active:
                await self.write(9241, stream(0x40002, stamp, b''))
            navigation_active = self.navigation
            deadline += .02
            await asyncio.sleep(max(0, deadline - time.monotonic()))

    async def video_loop(self):
        encoder = av.CodecContext.create('libx264', 'w')
        encoder.width, encoder.height = WIDTH, HEIGHT
        encoder.pix_fmt = 'yuv420p'
        encoder.time_base = Fraction(1, FPS)
        encoder.framerate = Fraction(FPS)
        encoder.options = {'preset': 'ultrafast', 'tune': 'zerolatency',
                           'x264-params': 'keyint=20:min-keyint=20:scenecut=0:repeat-headers=1:annexb=1'}
        encoder.open()
        counter = 0
        raw = (self.directory / 'source.h264').open('wb')
        try:
            await self.encoding.wait()
            timeline = time.monotonic()
            while not self.closed:
                pixels = np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8)
                pixels[:, :WIDTH // 2, 1 if self.touch_state else 0] = 210
                pixels[:, WIDTH // 2:, :] = 40
                x = WIDTH // 2 + (counter * 7 % (WIDTH // 2 - 16))
                pixels[:, x:x + 16, 2] = 230
                pixels[:64, :64] = (32 + 48 * self.generation, 0, 0)
                frame = av.VideoFrame.from_ndarray(pixels, format='rgb24')
                frame.pts = counter
                packets = encoder.encode(frame)
                for packet in packets:
                    data = bytes(packet)
                    raw.write(data)
                    await self.write(8240, stream(0x20001, counter * 1000 // FPS, data))
                counter += 1
                await asyncio.sleep(max(0, timeline + counter / FPS - time.monotonic()))
        finally:
            raw.close()

    async def reset_connections(self, music_hz=None):
        self.generation += 1
        if music_hz is not None:
            self.music_hz = music_hz
        self.transcript.add('source-generation', generation=self.generation, music_hz=self.music_hz)
        writers = list(self.writers.values())
        self.writers.clear()
        for writer in writers:
            writer.close()
        shutdown = Shutdown()
        for writer in writers:
            await shutdown.wait('CarLife source TCP reset', writer.wait_closed())
        shutdown.finish()

    async def close(self):
        self.closed = True
        shutdown = Shutdown()
        for server in self.servers:
            server.close()
        for writer in list(self.writers.values()):
            writer.close()
        await shutdown.cancel(self.tasks)
        for server in self.servers:
            await shutdown.wait('CarLife source server', server.wait_closed())
        for artifact in self.pcm_artifacts.values():
            artifact.close()
        shutdown.finish()
