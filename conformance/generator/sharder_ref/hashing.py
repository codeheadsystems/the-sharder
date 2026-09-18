"""The framed, domain-tagged hash construction of the specification.

    seed        = hash.seed from the document, 16 octets, default 16 zero octets
    u32be(n)    = 4 octets, most significant first
    frame(f...) = concat over i of ( u32be(len(f_i)) || f_i )
    H(f...)     = siphash24(key = seed, message = frame(f...))   -> u64

The domain tag is always the first framed field, as ASCII octets with no terminator.
"""

from .siphash import siphash24

ZERO_SEED = bytes(16)

TAG_KEY = b"sharder/key/v1"
TAG_RING_TOKEN = b"sharder/ring-token/v1"
TAG_RENDEZVOUS = b"sharder/rendezvous/v1"


def u32be(value: int) -> bytes:
    if value < 0 or value > 0xFFFFFFFF:
        raise ValueError("u32 out of range: %r" % (value,))
    return value.to_bytes(4, "big")


def frame(*fields: bytes) -> bytes:
    out = bytearray()
    for field in fields:
        out += u32be(len(field))
        out += field
    return bytes(out)


def H(seed: bytes, *fields: bytes) -> int:
    return siphash24(seed, frame(*fields))


def key_hash(seed: bytes, routing_key: bytes) -> int:
    return H(seed, TAG_KEY, routing_key)


def ring_token(seed: bytes, node_id: bytes, index: int) -> int:
    return H(seed, TAG_RING_TOKEN, node_id, u32be(index))


def rv_score(seed: bytes, routing_key: bytes, node_id: bytes, index: int) -> int:
    return H(seed, TAG_RENDEZVOUS, routing_key, node_id, u32be(index))


def hex_u64(value: int) -> str:
    """The textual form of a u64: sixteen lowercase hexadecimal digits, most significant first."""
    return "%016x" % value
