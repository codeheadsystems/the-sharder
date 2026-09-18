"""The suite's deterministic key sample.

`PROP-020` and the movement bounds are stated over a sample of routing keys "drawn independently
and uniformly from the set of sixteen-octet sequences".  A conformance suite cannot draw at
random and still be reproducible, so the suite fixes the sample and `PROP-006` requires every
sampled bound to be evaluated over it: SplitMix64 seeded with a stated value, two draws per key,
each written most significant octet first.

SplitMix64 is used because it is four lines of integer arithmetic in every language the suite
targets, needs no library, and has no floating point.  It never reaches a placement decision; it
only chooses which keys a property is evaluated over.
"""

MASK64 = 0xFFFFFFFFFFFFFFFF
GOLDEN = 0x9E3779B97F4A7C15


class SplitMix64:
    def __init__(self, seed: int):
        self.state = seed & MASK64

    def next_u64(self) -> int:
        self.state = (self.state + GOLDEN) & MASK64
        z = self.state
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK64
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK64
        return (z ^ (z >> 31)) & MASK64


def sample_keys(seed: int, count: int):
    """Yield `count` sixteen-octet keys from the stream seeded with `seed`."""
    rng = SplitMix64(seed)
    for _ in range(count):
        yield rng.next_u64().to_bytes(8, "big") + rng.next_u64().to_bytes(8, "big")
