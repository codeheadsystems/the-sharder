"""SipHash-2-4 with 64-bit output.

The Aumasson and Bernstein construction: two compression rounds per message word, four
finalisation rounds, 64-bit output. Word loading is little-endian and is part of the algorithm.

`verify_siphash.py` checks this module against the 64 reference vectors of the published paper
and against OpenSSL's independent implementation before any vector is generated.
"""

MASK64 = 0xFFFFFFFFFFFFFFFF


def _rotl(x, b):
    return ((x << b) | (x >> (64 - b))) & MASK64


def _sip_round(v0, v1, v2, v3):
    v0 = (v0 + v1) & MASK64
    v1 = _rotl(v1, 13)
    v1 ^= v0
    v0 = _rotl(v0, 32)
    v2 = (v2 + v3) & MASK64
    v3 = _rotl(v3, 16)
    v3 ^= v2
    v0 = (v0 + v3) & MASK64
    v3 = _rotl(v3, 21)
    v3 ^= v0
    v2 = (v2 + v1) & MASK64
    v1 = _rotl(v1, 17)
    v1 ^= v2
    v2 = _rotl(v2, 32)
    return v0, v1, v2, v3


def siphash24(key: bytes, message: bytes) -> int:
    """Return the 64-bit SipHash-2-4 of `message` under the 16-octet `key`."""
    if len(key) != 16:
        raise ValueError("siphash-2-4 takes a 16 octet key, got %d" % len(key))

    k0 = int.from_bytes(key[0:8], "little")
    k1 = int.from_bytes(key[8:16], "little")

    v0 = 0x736F6D6570736575 ^ k0
    v1 = 0x646F72616E646F6D ^ k1
    v2 = 0x6C7967656E657261 ^ k0
    v3 = 0x7465646279746573 ^ k1

    n = len(message)
    whole = n - (n % 8)
    for offset in range(0, whole, 8):
        m = int.from_bytes(message[offset:offset + 8], "little")
        v3 ^= m
        v0, v1, v2, v3 = _sip_round(v0, v1, v2, v3)
        v0, v1, v2, v3 = _sip_round(v0, v1, v2, v3)
        v0 ^= m

    tail = int.from_bytes(message[whole:], "little") | ((n & 0xFF) << 56)
    v3 ^= tail
    v0, v1, v2, v3 = _sip_round(v0, v1, v2, v3)
    v0, v1, v2, v3 = _sip_round(v0, v1, v2, v3)
    v0 ^= tail

    v2 ^= 0xFF
    for _ in range(4):
        v0, v1, v2, v3 = _sip_round(v0, v1, v2, v3)

    return (v0 ^ v1 ^ v2 ^ v3) & MASK64
