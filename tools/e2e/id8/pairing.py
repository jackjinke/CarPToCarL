"""SRP-6a (3072/SHA512), Ed25519 identities and X25519 pair verification.

All identities/keys are generated per Harness. No captured pairing material is used.
"""
import hashlib
import hmac
import secrets
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from .wire import hkdf, label, tlv, untlv

# RFC 3526 group 15 / RFC 5054 3072-bit SRP group, g=5.
N = int('''FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74
020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437
4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED
EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05
98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB
9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B
E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183
995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A
85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7AB
F5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D8
7602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208
E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF'''.replace('\n', ''), 16)
G = 5


def raw_public(key):
    return key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)


def integer(value, padded=False):
    return value.to_bytes(384 if padded else max(1, (value.bit_length() + 7) // 8), 'big')


def digest(*values):
    return hashlib.sha512(b''.join(values)).digest()


def number(value):
    return int.from_bytes(value, 'big')


class Identity:
    def __init__(self):
        self.key = ed25519.Ed25519PrivateKey.generate()
        self.identifier = b'id8-network-test-receiver'
        self.controllers = {}


class Pairing:
    def __init__(self, identity, bad_auth=False):
        self.identity = identity
        self.bad_auth = bad_auth
        self.corrupt_proof_sent = False
        self.stage = 0
        self.shared = None
        self.verified = False

    def setup(self, body):
        values = untlv(body)
        state = values.get(6)
        if state == b'\x01':
            self.salt = secrets.token_bytes(16)
            self.b = secrets.randbits(256)
            x = number(digest(self.salt, digest(b'Pair-Setup:3939')))
            self.v = pow(G, x, N)
            k = number(digest(integer(N, True), integer(G, True)))
            self.B = (k * self.v + pow(G, self.b, N)) % N
            self.stage = 2
            return tlv({6: 2, 2: self.salt, 3: integer(self.B, True)})
        if state == b'\x03' and self.stage == 2:
            A = number(values[3])
            if A % N == 0:
                raise ValueError('invalid SRP public value')
            a_bytes, b_bytes = integer(A, True), integer(self.B, True)
            u = number(digest(a_bytes, b_bytes))
            secret = pow(A * pow(self.v, u, N) % N, self.b, N)
            self.session = digest(integer(secret))
            xor = bytes(x ^ y for x, y in zip(digest(integer(N)), digest(integer(G))))
            proof = digest(xor, digest(b'Pair-Setup'), self.salt, a_bytes, b_bytes, self.session)
            if not hmac.compare_digest(proof, values[4]):
                raise ValueError('SRP client proof mismatch')
            self.stage = 4
            server = digest(a_bytes, proof, self.session)
            if self.bad_auth:
                server = bytes([server[0] ^ 1]) + server[1:]
                self.corrupt_proof_sent = True
            return tlv({6: 4, 4: server})
        if state == b'\x05' and self.stage == 4:
            key = hkdf(self.session, 'Pair-Setup-Encrypt-Salt', 'Pair-Setup-Encrypt-Info')
            inner = untlv(ChaCha20Poly1305(key).decrypt(label('PS-Msg05'), values[5], None))
            sign = hkdf(self.session, 'Pair-Setup-Controller-Sign-Salt', 'Pair-Setup-Controller-Sign-Info')
            ed25519.Ed25519PublicKey.from_public_bytes(inner[3]).verify(
                inner[10], sign + inner[1] + inner[3])
            self.identity.controllers[inner[1]] = inner[3]
            public = raw_public(self.identity.key)
            sign = hkdf(self.session, 'Pair-Setup-Accessory-Sign-Salt', 'Pair-Setup-Accessory-Sign-Info')
            signature = self.identity.key.sign(sign + self.identity.identifier + public)
            inner = tlv({1: self.identity.identifier, 3: public, 10: signature})
            self.stage = 6
            return tlv({6: 6, 5: ChaCha20Poly1305(key).encrypt(label('PS-Msg06'), inner, None)})
        raise ValueError('pair-setup out of order')

    def verify(self, body):
        values = untlv(body)
        if values.get(6) == b'\x01':
            self.ephemeral = x25519.X25519PrivateKey.generate()
            self.client_ephemeral = values[3]
            self.shared = self.ephemeral.exchange(x25519.X25519PublicKey.from_public_bytes(values[3]))
            self.verify_key = hkdf(self.shared, 'Pair-Verify-Encrypt-Salt', 'Pair-Verify-Encrypt-Info')
            public = raw_public(self.ephemeral)
            signature = self.identity.key.sign(public + self.identity.identifier + values[3])
            inner = tlv({1: self.identity.identifier, 10: signature})
            self.verify_stage = 2
            return tlv({6: 2, 3: public, 5: ChaCha20Poly1305(self.verify_key).encrypt(label('PV-Msg02'), inner, None)})
        if values.get(6) == b'\x03' and getattr(self, 'verify_stage', None) == 2:
            inner = untlv(ChaCha20Poly1305(self.verify_key).decrypt(label('PV-Msg03'), values[5], None))
            public = self.identity.controllers.get(inner[1])
            if public is None:
                return tlv({6: 4, 7: 2})
            ed25519.Ed25519PublicKey.from_public_bytes(public).verify(
                inner[10], self.client_ephemeral + inner[1] + raw_public(self.ephemeral))
            self.verified = True
            return tlv({6: 4})
        raise ValueError('pair-verify out of order')
