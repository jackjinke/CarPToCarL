"""Bounded RTSP/TLV8 framing, independently parsed at the network seam."""
import asyncio
import hashlib
import json
import plistlib
import struct
import time
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

MAX_BODY = 4 * 1024 * 1024


def hkdf(secret, salt, info):
    return HKDF(algorithm=hashes.SHA512(), length=32,
                salt=salt.encode(), info=info.encode()).derive(secret)


def nonce(counter):
    return b'\0' * 4 + struct.pack('<Q', counter)


def label(value):
    return b'\0' * 4 + value.encode()


def tlv(values):
    out = bytearray()
    for tag, value in values.items():
        if isinstance(value, int):
            value = bytes([value])
        for offset in range(0, max(1, len(value)), 255):
            part = value[offset:offset + 255]
            out.extend(bytes([tag, len(part)]) + part)
    return bytes(out)


def untlv(data):
    result = {}
    offset = 0
    while offset < len(data):
        if offset + 2 > len(data):
            raise ValueError('truncated TLV header')
        tag, size = data[offset:offset + 2]
        offset += 2
        if offset + size > len(data):
            raise ValueError('truncated TLV value')
        result[tag] = result.get(tag, b'') + data[offset:offset + size]
        offset += size
    return result


def plist(value):
    return plistlib.dumps(value, fmt=plistlib.FMT_BINARY, sort_keys=False)


class Transcript:
    def __init__(self, directory):
        self.file = (directory / 'network.jsonl').open('w')
        self.start = time.monotonic()

    def add(self, kind, **fields):
        observed = fields.pop('t', None)
        timestamp = time.monotonic() if observed is None else observed
        self.file.write(json.dumps(dict(t=round(timestamp - self.start, 6),
                                       kind=kind, **fields), default=str) + '\n')
        self.file.flush()

    def close(self):
        self.file.close()


class Connection:
    def __init__(self, reader, writer, transcript, channel):
        self.reader, self.writer = reader, writer
        self.transcript, self.channel = transcript, channel
        self.read_key = self.write_key = None
        self.rx = self.tx = 0
        self.buffer = bytearray()
        self.lock = asyncio.Lock()
        self.pending = {}
        self.cseq = 10000
        self.encrypted_frames = 0
        self.closed = False

    def encrypt(self, read_key, write_key):
        self.read_key, self.write_key = read_key, write_key

    async def _more(self):
        if self.read_key is None:
            chunk = await self.reader.read(8192)
            if not chunk:
                raise EOFError('network EOF')
        else:
            head = await self.reader.readexactly(2)
            size = struct.unpack('<H', head)[0]
            if not 0 < size <= 1422:
                raise ValueError('encrypted record exceeds wire MTU')
            sealed = await self.reader.readexactly(size + 16)
            chunk = ChaCha20Poly1305(self.read_key).decrypt(nonce(self.rx), sealed, head)
            self.rx += 1
            self.encrypted_frames += 1
        self.buffer.extend(chunk)
        if len(self.buffer) > MAX_BODY + 65536:
            raise ValueError('RTSP buffer limit')

    async def receive(self):
        while b'\r\n\r\n' not in self.buffer:
            if len(self.buffer) > 65536:
                raise ValueError('RTSP header limit')
            await self._more()
        end = self.buffer.index(b'\r\n\r\n')
        lines = self.buffer[:end].decode('iso-8859-1').split('\r\n')
        headers = {}
        for line in lines[1:]:
            key, sep, value = line.partition(':')
            if not sep or key.lower() in headers:
                raise ValueError('malformed or duplicate RTSP header')
            headers[key.lower()] = value.strip()
        size = int(headers.get('content-length', '0'))
        if not 0 <= size <= MAX_BODY:
            raise ValueError('RTSP body limit')
        total = end + 4 + size
        while len(self.buffer) < total:
            await self._more()
        body = bytes(self.buffer[end + 4:total])
        del self.buffer[:total]
        self.transcript.add('rtsp', channel=self.channel, direction='in',
                            line=lines[0], size=size, sha256=hashlib.sha256(body).hexdigest(),
                            encrypted=self.read_key is not None)
        return lines[0], headers, body

    async def send(self, line, headers=None, body=b''):
        headers = dict(headers or {})
        headers['Content-Length'] = str(len(body))
        message = (line + '\r\n' + ''.join(f'{k}: {v}\r\n' for k, v in headers.items())
                   + '\r\n').encode('iso-8859-1') + body
        async with self.lock:
            if self.write_key is not None:
                wire = bytearray()
                for offset in range(0, len(message), 1024):
                    part = message[offset:offset + 1024]
                    head = struct.pack('<H', len(part))
                    wire.extend(head + ChaCha20Poly1305(self.write_key).encrypt(
                        nonce(self.tx), part, head))
                    self.tx += 1
                message = bytes(wire)
            self.writer.write(message)
            await self.writer.drain()
        self.transcript.add('rtsp', channel=self.channel, direction='out', line=line,
                            size=len(body), encrypted=self.write_key is not None)

    async def reply(self, headers, value=None, status=200, raw=None):
        body = raw if raw is not None else (plist(value) if value is not None else b'')
        await self.send(f'RTSP/1.0 {status} ' + ('OK' if status == 200 else 'Rejected'),
                        {'CSeq': headers.get('cseq', '0'),
                         'Content-Type': 'application/x-apple-binary-plist'}, body)

    async def command(self, kind, params, timeout=5):
        self.cseq += 1
        seq = str(self.cseq)
        future = asyncio.get_running_loop().create_future()
        self.pending[seq] = future
        envelope = {'type': kind, **params} if kind == 'hidSendReport' else {'type': kind, 'params': params}
        try:
            await self.send('POST /command RTSP/1.0',
                            {'CSeq': seq, 'Content-Type': 'application/x-apple-binary-plist'},
                            plist(envelope))
            return await asyncio.wait_for(future, timeout)
        finally:
            self.pending.pop(seq, None)

    def response(self, line, headers, body):
        future = self.pending.get(headers.get('cseq'))
        if future and not future.done():
            future.set_result((line, plistlib.loads(body) if body else {}))

    async def close(self):
        self.closed = True
        self.writer.close()
        try:
            await self.writer.wait_closed()
        except (ConnectionError, OSError):
            pass
