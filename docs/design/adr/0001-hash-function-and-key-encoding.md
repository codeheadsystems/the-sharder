# 0001. Hash function and key encoding

Status: accepted. Date: 2026-09-16.

## Context

Two implementations in two languages must produce byte-identical routing decisions for the same
topology and key. The hash function is the point at which that requirement is most easily lost, and
it is a one-way door: changing it after any data has been placed moves every key.

Four properties are in tension. The function must have a single unambiguous specification with
published test vectors, because a function with several mutually incompatible implementations in the
wild guarantees that two ports disagree. It must be implementable in C, Go, Rust, Python, and Java
in well under a hundred lines, because every port carries its own copy. It must avalanche well
enough that placement over a few hundred nodes is balanced. It should make it expensive for an
adversary who can choose keys to concentrate traffic on one shard.

MurmurHash3 fails the first property in practice. The `x86_128` and `x64_128` variants produce
different results, several widely used libraries differ in their handling of the tail block and of
signed arithmetic, and Cassandra's `Murmur3Partitioner` is not interchangeable with a naive port.
CRC16, as used by Redis Cluster for slot selection, is 16 bits wide and carries no avalanche
guarantee suitable for node placement.

## Decision

The hash function is SipHash-2-4 with a 128-bit key and a 64-bit output, as published by Aumasson
and Bernstein. The key comes from `hash.seed` in the topology document, which defaults to sixteen
zero bytes. The output is interpreted as an unsigned 64-bit integer.

Every hash input is framed. A framed input is the concatenation, in order, of each field encoded as
its length in bytes as a 32-bit big-endian integer followed by the field's bytes.

```
frame(f1, f2, ..., fn) = u32be(len(f1)) || f1 || u32be(len(f2)) || f2 || ... || u32be(len(fn)) || fn
H(field...)             = siphash24(seed, frame(field...))
```

The first field is always a domain tag, an ASCII literal that separates one use of the hash from
another.

| Use | Domain tag | Remaining fields |
|---|---|---|
| key placement | `sharder/key/v1` | routing key |
| ring token | `sharder/ring-token/v1` | node identity, `u32be(index)` |
| rendezvous score | `sharder/rendezvous/v1` | routing key, node identity, `u32be(index)` |
| slot ordering | `sharder/slot-rendezvous/v1` | `u32be(slotIndex)`, node identity, `u32be(index)` |
| range ordering | `sharder/range-rendezvous/v1` | shard identifier, node identity, `u32be(index)` |

The `index` field is the token index under `ring` and the virtual node index elsewhere. A node
identity and a shard identifier are framed as their UTF-8 bytes, exactly as the topology document
spells them. A routing key is framed as the octets the caller supplied, after the key transform.

Integer conventions hold throughout placement. All hash values are unsigned 64-bit and all
comparisons on them are unsigned. All indices, counts, weights, and slot identifiers are unsigned
32-bit. Integers the sharder library composes into a hash input are big-endian; the little-endian
words inside SipHash are part of that algorithm rather than a library convention. Where a 64-bit
hash value is written as text, in a topology document or in a conformance vector, it is sixteen
lowercase hexadecimal digits, most significant first.

No placement arithmetic uses floating point. The only division in placement is the unsigned
remainder that maps a key hash to a slot identifier. Metrics and balance reports may use floating
point, and their values never feed a placement decision.

Where two hash values are equal, the tie is broken by comparing node identities as unsigned byte
sequences, ascending, and the comparison is over UTF-8 bytes rather than over code points or
collation order. Every comparator the specification defines ends in this tie-break, so every
ordering is total and no sort in any implementation depends on stability.

The library applies no character encoding, no Unicode normalisation, and no case folding to a key. A
binding that accepts text keys encodes them as UTF-8 without a byte order mark. A caller that needs
normalisation performs it before the routing call.

## Consequences

SipHash-2-4 costs roughly two to three times as much per byte as xxHash64 on short inputs. Keys in
the intended use cases are short, and the cost is bounded by one hash per key for slot, ring, and
range placement, and by one hash per virtual node for rendezvous placement.

Framing removes concatenation ambiguity. Without it, a node named `ab` at token index 1 and a node
named `a` at token index 11 could produce the same input bytes.

The seed is part of the topology, so rotating it moves every key. Seed rotation is a full data
migration and is not an operational routine.

Adversarial resistance is real only where the seed is not known to the adversary. A seed committed
to a public repository provides none. The zero default is chosen so that conformance vectors are
reproducible, and an operator who cares about crafted keys sets a random seed.

Every port carries a SipHash-2-4 implementation, verified against the reference vectors from the
original paper before any conformance vector runs.

## Alternatives

MurmurHash3, in the `x64_128` form Cassandra uses. Fast and widely deployed. Rejected because
independent implementations disagree in the tail and in sign handling, and because the 128-bit
output requires an arbitrary truncation rule that is itself a source of divergence.

xxHash64 and XXH3. Faster, with a specification and test vectors. Rejected because they are unkeyed,
so crafted keys can concentrate load, and because XXH3 is substantially more code than SipHash in
every language.

CRC16 over a bracketed key tag, as Redis Cluster uses for slots. Rejected as too narrow for node
placement. The bracketed tag convention is borrowed as the `braceTag` key transform.

FNV-1a. Trivial to implement. Rejected for poor avalanche on short keys with common prefixes, which
is exactly the shape of a tenant identifier.

SHA-256 truncated to 64 bits. Unambiguous, available everywhere, and resistant. Rejected as an order
of magnitude slower than needed for a per-request call, with no placement benefit.

HighwayHash and BLAKE3. Keyed and fast. Rejected as too much code to port five times.

Two independent hash functions combined to derive offsets, as Maglev does. Rejected because the
strategy set does not include Maglev, and combining functions doubles the surface where two ports
can disagree.
