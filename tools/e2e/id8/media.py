"""Receive, authenticate and decode media; observations come only from wire data."""
import asyncio
import struct
import time
import wave
import av
import numpy as np
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from .wire import hkdf, nonce
from .shutdown import Shutdown


def stream_key(secret, connection):
    return hkdf(secret, f'DataStream-Salt{connection}', 'DataStream-Output-Encryption-Key')


def nals(data, length_size=4):
    offset = 0
    while offset < len(data):
        if offset + length_size > len(data):
            raise ValueError('truncated H264 NAL length')
        size = int.from_bytes(data[offset:offset + length_size], 'big')
        offset += length_size
        if size == 0 or offset + size > len(data):
            raise ValueError('invalid H264 NAL length')
        yield data[offset:offset + size]
        offset += size


def avcc(data):
    if len(data) < 7 or data[0] != 1:
        raise ValueError('invalid avcC record')
    length_size = (data[4] & 3) + 1
    offset = 6
    units = []
    for count in (data[5] & 31,):
        for _ in range(count):
            if offset + 2 > len(data):
                raise ValueError('truncated SPS length')
            size = int.from_bytes(data[offset:offset + 2], 'big')
            offset += 2
            if size == 0 or offset + size > len(data):
                raise ValueError('truncated SPS')
            units.append(data[offset:offset + size])
            offset += size
    if offset >= len(data):
        raise ValueError('missing PPS count')
    count = data[offset]
    offset += 1
    for _ in range(count):
        if offset + 2 > len(data):
            raise ValueError('truncated PPS length')
        size = int.from_bytes(data[offset:offset + 2], 'big')
        offset += 2
        if size == 0 or offset + size > len(data):
            raise ValueError('truncated PPS')
        units.append(data[offset:offset + size])
        offset += size
    if not any(x[0] & 31 == 7 for x in units) or not any(x[0] & 31 == 8 for x in units):
        raise ValueError('avcC requires SPS and PPS')
    return length_size, units


class VideoReceiver:
    def __init__(self, owner, description):
        self.owner, self.description = owner, description
        self.key = stream_key(owner.shared, description['streamConnectionID'])
        self.server = None
        self.writers = set()
        self.tasks = set()
        self.connections = 0
        self.frames = []
        self.keepalive = []
        self.closed = False
        self.recorded_file = (owner.directory / f"received-{owner.epoch}-{description['streamConnectionID']}.h264").open('wb')

    async def start(self):
        self.server = await asyncio.start_server(self.accept, self.owner.host, 0)
        return self.server.sockets[0].getsockname()[1]

    async def accept(self, reader, writer):
        self.owner.track_current()
        self.tasks.add(asyncio.current_task())
        self.writers.add(writer)
        self.connections += 1
        counter, configured, first, length_size = 0, False, True, 4
        decoder = av.CodecContext.create('h264', 'r')
        try:
            while True:
                header = await reader.readexactly(128)
                size = struct.unpack('<I', header[:4])[0]
                opcode = header[4]
                if size > 4 * 1024 * 1024:
                    raise ValueError('screen frame exceeds limit')
                body = await reader.readexactly(size)
                now = time.monotonic()
                if opcode == 1:
                    length_size, units = avcc(body)
                    codec_data = b''.join(b'\0\0\0\1' + x for x in units)
                    decoder = av.CodecContext.create('h264', 'r')
                    decoder.extradata = codec_data
                    self.recorded_file.write(codec_data)
                    configured, first = True, True
                elif opcode == 0:
                    if not configured:
                        raise ValueError('video before codec configuration')
                    plain = ChaCha20Poly1305(self.key).decrypt(nonce(counter), body, header)
                    counter += 1
                    units = list(nals(plain, length_size))
                    if first and not any(x[0] & 31 == 5 for x in units):
                        raise ValueError('new decoder starts without an IDR')
                    first = False
                    if not self.owner.recorded:
                        raise ValueError('screen media before RECORD')
                    if self.owner.resources[1]['entity'] != 1 and now > self.owner.yield_at.get(1, now) + .75:
                        raise ValueError('screen media while car owns screen')
                    annexb = b''.join(b'\0\0\0\1' + x for x in units)
                    self.recorded_file.write(annexb)
                    if self.owner.fault == 'negative-no-video':
                        self.owner.harness.fault_packets['video'] += 1
                        continue  # Network loss control: authenticated packets never reach decoder.
                    for decoded in decoder.decode(av.Packet(annexb)):
                        pixels = decoded.to_ndarray(format='rgb24')
                        h, w = pixels.shape[:2]
                        if (w, h) != (2560, 800):
                            raise ValueError(f'visible source geometry {w}x{h}, expected2560x800 independently of HU canvas2662x812')
                        center = pixels[h // 4:3 * h // 4, w // 8:3 * w // 8].mean(axis=(0, 1))
                        state = 'green' if center[1] > center[0] + 70 else 'red' if center[0] > center[1] + 70 else 'unknown'
                        movement = int(np.argmax(pixels[:, w // 2:, 2].mean(axis=0)))
                        source_generation = round((float(pixels[8:56, 8:56, 0].mean()) - 32) / 48)
                        observation = dict(t=now, state=state, movement=movement, width=w, height=h,
                                           epoch=self.owner.epoch, source_generation=source_generation)
                        self.frames.append(observation)
                        self.owner.video_observations.append(observation)
                        if len(self.frames) in (1, 10) or state != getattr(self, 'last_state', None):
                            self.owner.transcript.add('video-decoded', **observation)
                            # PPM is dependency-free; Pillow is optional in PyAV.
                            path = self.owner.directory / f'frame-{self.owner.epoch}-{len(self.frames)}.ppm'
                            path.write_bytes(f'P6\n{w} {h}\n255\n'.encode() + pixels.tobytes())
                        self.last_state = state
                elif opcode in (2, 5):
                    if opcode == 2 and body:
                        raise ValueError('empty screen keepalive carries body')
                    self.keepalive.append(now)
                    self.owner.screen_keepalive.append(now)
                else:
                    raise ValueError(f'unrecognized screen opcode {opcode}')
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        except Exception as error:
            self.owner.fail('video-wire', str(error))
        finally:
            self.writers.discard(writer)
            writer.close()
            self.owner.untrack_current()
            self.tasks.discard(asyncio.current_task())

    async def close(self):
        if self.closed:
            return
        self.closed = True
        shutdown = Shutdown()
        if self.server:
            self.server.close()
        for writer in list(self.writers):
            writer.close()
        await shutdown.cancel(self.tasks)
        if self.server:
            await shutdown.wait('video server', self.server.wait_closed())
        self.recorded_file.close()
        shutdown.finish()


class AudioReceiver(asyncio.DatagramProtocol):
    def __init__(self, owner, description):
        self.owner, self.description = owner, description
        self.key = stream_key(owner.shared, description['streamConnectionID'])
        self.type = description['type']
        self.transport = None
        self.seen = set()
        self.last_sequence = self.last_timestamp = None
        self.received = 0
        self.rate = 44100 if description['audioFormat'] in (1 << 11, 1 << 22) else 48000
        self.channels = 2
        self.decoder = None
        if self.type == 102:
            self.decoder = av.CodecContext.create('aac', 'r')
            # MPEG-4 AudioSpecificConfig: AAC-LC, indexed sampling frequency, stereo.
            index = 4 if self.rate == 44100 else 3
            self.decoder.extradata = ((2 << 11) | (index << 7) | (2 << 3)).to_bytes(2, 'big')
        self.resampler = av.AudioResampler(format='s16', layout='stereo', rate=self.rate)
        self.wave = wave.open(str(owner.directory / f"received-{owner.epoch}-{description['streamConnectionID']}.wav"), 'wb')
        self.wave.setnchannels(2)
        self.wave.setsampwidth(2)
        self.wave.setframerate(self.rate)
        self.samples = []
        self.analysis_samples = []
        self.closed = False
        self.test_tones = (440, 550, 660, 770, 880)
        short_times = np.arange(self.rate // 100) / self.rate
        basis = np.column_stack([function(2 * np.pi * hz * short_times)
                                 for hz in self.test_tones for function in (np.sin, np.cos)])
        self.tone_projection = np.linalg.pinv(basis)

    async def start(self):
        loop = asyncio.get_running_loop()
        self.transport, _ = await loop.create_datagram_endpoint(lambda: self, local_addr=(self.owner.host, 0))
        return self.transport.get_extra_info('sockname')[1]

    def datagram_received(self, data, address):
        if self.closed:
            return
        try:
            self.receive(data)
        except Exception as error:
            self.owner.fail('audio-wire', str(error))

    def receive(self, data):
        if len(data) < 36 or data[0] != 0x80 or data[1] & 127 != self.type:
            raise ValueError('invalid RTP audio header')
        seq, timestamp, ssrc = struct.unpack('>HII', data[2:12])
        count = int.from_bytes(data[-8:], 'little')
        if count in self.seen:
            raise ValueError('audio nonce replay')
        self.seen.add(count)
        if self.last_sequence is not None:
            delta = (seq - self.last_sequence) & 65535
            ticks = (timestamp - self.last_timestamp) & 0xffffffff
            if delta == 0 or delta > 128 or ticks == 0:
                raise ValueError('RTP audio sequence/timestamp discontinuity')
            if self.type == 102 and ticks != 1024 * delta:
                raise ValueError('AAC RTP clock does not advance by 1024 samples per packet')
        self.last_sequence, self.last_timestamp = seq, timestamp
        plain = ChaCha20Poly1305(self.key).decrypt(b'\0' * 4 + data[-8:], data[12:-8], data[4:12])
        now = time.monotonic()
        if self.description['streamConnectionID'] not in self.owner.recorded_streams:
            raise ValueError('audio stream sent before its RECORD activation')
        if self.owner.resources[2]['entity'] != 1 and now > self.owner.yield_at.get(2, now) + .75:
            raise ValueError('audio while radio owns resource')
        if self.owner.fault == 'negative-no-audio':
            self.owner.harness.fault_packets['audio'] += 1
            return
        self.received += 1
        if self.decoder:
            frames = self.decoder.decode(av.Packet(plain))
            outputs = [out for frame in frames for out in self.resampler.resample(frame)]
            pcm = b''.join(frame.to_ndarray().tobytes() for frame in outputs)
        else:
            if len(plain) % 4:
                raise ValueError('stereo PCM payload is not sample aligned')
            pcm = np.frombuffer(plain, dtype='>i2').astype('<i2').tobytes()
        if not pcm:
            return
        self.wave.writeframesraw(pcm)
        mono = np.frombuffer(pcm, dtype='<i2').reshape(-1, 2).mean(axis=1)
        self.samples.extend(mono.tolist())
        self.analysis_samples.extend(mono.tolist())
        window_size = self.rate // 10
        while len(self.analysis_samples) >= window_size:
            window = np.asarray(self.analysis_samples[:window_size])
            del self.analysis_samples[:window_size]
            power = abs(np.fft.rfft(window * np.hanning(window_size))) ** 2
            frequencies = np.fft.rfftfreq(window_size, 1 / self.rate)
            total = max(float(power.sum()), 1)
            short = window.reshape(10, -1)
            coefficients = short @ self.tone_projection.T
            amplitudes = np.sqrt(coefficients[:, 0::2] ** 2 + coefficients[:, 1::2] ** 2)
            self.owner.harness.audio_quality.append(dict(
                t=now, epoch=self.owner.epoch, rate=self.rate, samples=window_size,
                rms=float(np.sqrt(np.mean(window ** 2))),
                clipped_fraction=float(np.mean(abs(window) >= 32760)),
                audible_10ms_fraction={str(hz): float(np.mean(amplitudes[:, index] > 700))
                                       for index, hz in enumerate(self.test_tones)},
                bands={str(hz): float(power[abs(frequencies - hz) <= 10].sum() / total)
                       for hz in self.test_tones}))
        self.owner.audio_observations.append(dict(t=now, epoch=self.owner.epoch,
                                                  rate=self.rate, samples=len(mono)))
        if len(self.samples) >= self.rate // 5:
            window = np.asarray(self.samples[-self.rate // 5:])
            spectrum = abs(np.fft.rfft(window * np.hanning(len(window))))
            frequency = np.fft.rfftfreq(len(window), 1 / self.rate)[int(np.argmax(spectrum))]
            rms = float(np.sqrt(np.mean(window ** 2)))
            self.owner.tones.append(dict(t=now, frequency=float(frequency), rms=rms, epoch=self.owner.epoch))
            self.samples = self.samples[-self.rate // 5:]

    def error_received(self, error):
        self.owner.fail('audio-udp', str(error))

    async def close(self):
        self.closed = True
        if self.transport:
            self.transport.close()
        self.wave.close()
