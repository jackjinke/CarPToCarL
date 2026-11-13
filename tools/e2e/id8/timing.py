"""RTP timing probes sent to the app's wire-advertised UDP service."""
import asyncio
import socket
import struct
import time


async def probe(session, endpoint):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setblocking(False)
    loop = asyncio.get_running_loop()
    previous = None
    try:
        while not session.closed and not session.control.closed:
            started = time.monotonic()
            origin = int(time.monotonic() * 2**32) & ((1 << 64) - 1)
            request = struct.pack('>BBH', 0x80, 0xD2, 7) + b'\0' * 20 + struct.pack('>Q', origin)
            await loop.sock_sendto(sock, request, endpoint)
            try:
                response, address = await asyncio.wait_for(loop.sock_recvfrom(sock, 256), 2)
            except asyncio.TimeoutError:
                if session.closed or session.control.closed or getattr(session, 'resetting', False):
                    return
                session.fail('timing-response', 'wire-advertised sender timing UDP service did not respond')
                return
            if len(response) != 32 or response[:4] != b'\x80\xd3\0\x07':
                session.fail('timing-response', 'malformed RTP timing response')
                return
            echoed, received, transmitted = struct.unpack('>QQQ', response[8:32])
            if echoed != origin or received == 0 or transmitted < received:
                session.fail('timing-response', 'timing origin echo or remote clock fields invalid')
                return
            if previous is not None and received <= previous:
                session.fail('timing-clock', 'remote timing clock did not advance')
                return
            previous = received
            rtt = time.monotonic() - started
            session.timing_responses.append(dict(t=time.monotonic(), rtt=rtt,
                                                 remote_receive=received, remote_transmit=transmitted))
            session.transcript.add('timing-response', epoch=session.epoch, rtt=rtt,
                                   origin=echoed, receive=received, transmit=transmitted)
            await asyncio.sleep(1)
    finally:
        sock.close()
