"""Minimal iAP2 link/control-session peer, not a head-unit implementation."""
import asyncio
import datetime
import struct
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.x509.oid import NameOID

DETECT = bytes.fromhex('ff550200ee10')
SYN_PARAMETERS = bytes.fromhex('0105ffff000000000000010002020102030201')


def parameter(kind, value=b''):
    return struct.pack('>HH', len(value) + 4, kind) + value


def parameters(data):
    result = {}
    while data:
        if len(data) < 4:
            raise ValueError('truncated iAP parameter')
        size, kind = struct.unpack('>HH', data[:4])
        if size < 4 or size > len(data):
            raise ValueError('invalid iAP parameter size')
        result[kind] = data[4:size]
        data = data[size:]
    return result


def frame(flags, sequence, ack, session=0, payload=b''):
    header = b'\xff\x5a' + struct.pack('>HBBBB', 9 + len(payload) + bool(payload), flags, sequence, ack, session)
    header += bytes([(-sum(header)) & 255])
    return header + payload + (bytes([(-sum(payload)) & 255]) if payload else b'')


def identification():
    # Only fields consumed by this role; no vehicle serial/name/address is retained.
    sends = [0xAA06, 0xAA01, 0xAA03, 0x6802, 0x5000]
    receives = [0xAA00, 0xAA02, 0xAA05, 0x6801, 0x5001]
    return b''.join([
        parameter(0, b'ID8 Network Contract\0'), parameter(1, b'MGU-93\0'),
        parameter(2, b'Protocol Fixture\0'), parameter(3, b'ANONYMIZED\0'),
        parameter(4, b'23.50.3\0'), parameter(5, b'4.1.0\0'),
        parameter(6, b''.join(struct.pack('>H', x) for x in sends)),
        parameter(7, b''.join(struct.pack('>H', x) for x in receives)),
        parameter(8, b'\0'), parameter(9, b'\0\0'),
        parameter(12, b'en\0'), parameter(13, b'en\0')])


class IapPeer:
    def __init__(self, send, transcript, fail, withhold=False, windowed=False):
        self.send, self.transcript, self.fail = send, transcript, fail
        self.withhold = withhold
        self.windowed = windowed
        self.ingress = bytearray()
        self.unacknowledged = []
        self.ack_task = None
        self.maximum_outstanding = 0
        self.discovery_complete = False
        self.discovery_responses = 0
        self.sequence, self.ack = 65, 0
        self.buffer = bytearray()
        self.syn_sent = False
        self.normal = False
        self.serial_sent = self.cert_sent = self.challenge_seen = self.complete = False
        self.seen_data = set()
        self.detect_count = 0
        self.lock = asyncio.Lock()
        self.key = rsa.generate_private_key(public_exponent=65537, key_size=1024)
        subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Synthetic network test certificate')])
        now = datetime.datetime.now(datetime.timezone.utc)
        self.certificate = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
                            .public_key(self.key.public_key()).serial_number(1)
                            .not_valid_before(now - datetime.timedelta(days=1))
                            .not_valid_after(now + datetime.timedelta(days=1))
                            .sign(self.key, hashes.SHA256()).public_bytes(serialization.Encoding.DER))

    async def emit(self, payload):
        await self.send('iAPSendMessage', {'data': payload})

    async def csm(self, kind, body=b''):
        self.publish_ack()
        self.sequence = (self.sequence + 1) & 255
        payload = b'@@' + struct.pack('>HH', 6 + len(body), kind) + body
        await self.emit(frame(0x40, self.sequence, self.ack, 1, payload))

    async def feed(self, data):
        if self.windowed:
            self.observe_window(data)
        async with self.lock:
            await self._feed(data)

    def publish_ack(self):
        if self.ack in self.unacknowledged:
            index = self.unacknowledged.index(self.ack)
            del self.unacknowledged[:index + 1]

    def observe_window(self, data):
        # Observe at ingress, before serialized handlers can hide frames sent ahead
        # of a delayed ACK. Transport delivery is not a synthetic peer ACK.
        self.ingress.extend(data)
        while len(self.ingress) >= 6:
            if self.ingress[:2] == b'\xff\x55':
                del self.ingress[:6]
                continue
            size = int.from_bytes(self.ingress[2:4], 'big')
            if size < 9 or len(self.ingress) < size:
                return
            packet = bytes(self.ingress[:size])
            del self.ingress[:size]
            if (size > 9 and packet[4] & 0x40 and not packet[4] & 0x80
                    and packet[5] not in self.unacknowledged):
                self.unacknowledged.append(packet[5])
                self.maximum_outstanding = max(self.maximum_outstanding, len(self.unacknowledged))
                if len(self.unacknowledged) > 5:
                    self.fail('iap-peer-window', 'sender exceeded negotiated five unacknowledged payloads')

    async def delayed_ack(self):
        await asyncio.sleep(.15)
        async with self.lock:
            if self.unacknowledged:
                self.publish_ack()
                await self.emit(frame(0x40, self.sequence, self.ack))

    async def close(self):
        if self.ack_task:
            self.ack_task.cancel()
            await asyncio.gather(self.ack_task, return_exceptions=True)

    async def _feed(self, data):
        self.buffer.extend(data)
        if len(self.buffer) > 131072:
            raise ValueError('iAP buffer limit')
        while self.buffer:
            if len(self.buffer) < 6:
                return
            if self.buffer[:2] == b'\xff\x55':
                if self.buffer[:6] != DETECT:
                    raise ValueError('invalid iAP detect')
                del self.buffer[:6]
                self.detect_count += 1
                if not self.syn_sent:
                    await self.emit(DETECT)
                    await self.emit(frame(0x80, self.sequence, 0, 0, SYN_PARAMETERS))
                    self.syn_sent = True
                continue
            if self.buffer[:2] != b'\xff\x5a':
                raise ValueError('invalid iAP link prefix')
            size = int.from_bytes(self.buffer[2:4], 'big')
            if not 9 <= size <= 65535:
                raise ValueError('invalid iAP link length')
            if len(self.buffer) < size:
                return
            packet = bytes(self.buffer[:size])
            del self.buffer[:size]
            if sum(packet[:9]) & 255 or (size > 9 and sum(packet[9:]) & 255):
                raise ValueError('iAP checksum mismatch')
            flags, seq, ack, session = packet[4:8]
            self.transcript.add('iap-link', flags=flags, sequence=seq, ack=ack,
                                session=session, size=size)
            if flags & 0x80:
                self.ack = seq
                self.syn_sent = True
                if flags & 0x40:
                    await self.emit(frame(0x40, self.sequence, seq))
                else:
                    await self.emit(frame(0xC0, self.sequence, seq, 0, SYN_PARAMETERS))
                self.normal = True
                continue
            if not self.normal and flags & 0x40:
                self.normal = True
            if size == 9:
                continue  # ACK may reuse sequence; it is not a new data packet.
            self.ack = seq
            if self.windowed and self.complete:
                if self.ack_task is None or self.ack_task.done():
                    self.ack_task = asyncio.create_task(self.delayed_ack())
            else:
                self.publish_ack()
                await self.emit(frame(0x40, self.sequence, seq))
            identity = (seq, packet[9:-1])
            if identity in self.seen_data:
                continue
            self.seen_data.add(identity)
            payload = packet[9:-1]
            if session != 1 or len(payload) < 6 or payload[:2] != b'@@':
                raise ValueError('unexpected iAP control session')
            length, kind = struct.unpack('>HH', payload[2:6])
            if length != len(payload):
                raise ValueError('iAP CSM length mismatch')
            body = payload[6:]
            self.transcript.add('iap-message', message=kind, size=len(body))
            await self.message(kind, body)

    async def message(self, kind, body):
        if kind == 0x1D00:
            await self.csm(0x1D01, identification())
        elif kind == 0xAA00:
            if not self.serial_sent:
                if body != parameter(0):
                    self.fail('iap-initial-request', 'advertised AA06 requires serial-number selector')
                    return
                self.serial_sent = True
                await self.csm(0xAA06, parameter(0, b'\x01' * 16))
            elif not self.cert_sent:
                if body:
                    self.fail('iap-certificate-request', 'full certificate request must omit selector')
                    return
                self.cert_sent = True
                await self.csm(0xAA01, parameter(0, self.certificate))
        elif kind == 0xAA02:
            if not self.cert_sent:
                self.fail('iap-order', 'challenge before certificate')
                return
            challenge = parameters(body).get(0, b'')
            if len(challenge) not in (16, 20, 32):
                self.fail('iap-challenge', 'unsupported empty/malformed challenge')
                return
            self.challenge_seen = True
            if not self.withhold:
                signature = self.key.sign(challenge, padding.PKCS1v15(), hashes.SHA1())
                await self.csm(0xAA03, parameter(0, signature))
        elif kind == 0xAA05:
            if not self.challenge_seen or self.withhold:
                self.fail('iap-false-auth', 'authentication success without challenge response')
                return
            self.complete = True
            for _ in range(4 if self.windowed else 1):
                await self.csm(0xAD00, parameter(0))
        elif kind == 0xAD01:
            if not self.complete or body != parameter(0, b'\x01'):
                self.fail('iap-app-discovery', 'missing authenticated empty app-discovery result')
                return
            self.discovery_complete = True
            self.discovery_responses += 1
