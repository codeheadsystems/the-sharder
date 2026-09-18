# 0001. Hash function and key encoding

Status: accepted, with the `slotScore` function withdrawn by
[`0055`](0055-slot-derived-assignment-withdrawal.md) and `range` placement withdrawn by
[`0054`](0054-range-strategy-withdrawal.md). Date: 2026-09-16.

The hash function, the framed domain-tagged construction, the key encoding, and the seed policy all
stand. Two names in the measurement table and the prose below them do not. `slotScore` was the
function that derived a slot map, which `0055` withdraws with `SLOT-020` to `SLOT-023`; a `slot`
document now carries an authored map and evaluates no score. `range` placement is withdrawn with the
`RANGE-*` and `SPLIT-*` identifiers by `0054`. The measured figures are kept as the record of what
was measured on the date above.

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

MurmurHash3's difficulty with the first property is in the ecosystem rather than in the algorithm.
Appleby's reference `MurmurHash3.cpp` is unambiguous and mainstream implementations of `x64_128`
agree with it. What diverges is everything around it: the `x86_32`, `x86_128`, and `x64_128`
variants produce different outputs for one input and one seed and an API does not always force the
choice, a 128-bit output needs a truncation rule to become a 64-bit placement value, a port that
types the tail bytes as signed sign-extends every octet above `0x7f`, and Cassandra's
`Murmur3Partitioner` takes the first 64 bits and then maps `Long.MIN_VALUE` to `Long.MAX_VALUE`.
Naming the variant and the truncation rule in this specification, with the framing this record fixes
and the unsigned discipline of [`0030`](0030-unsigned-integer-discipline.md), would neutralise most
of that.

Two objections survive that repair, and they are the ones that decide the question. The first is the
acceptance test. Murmur3 has no per-input reference table of comparable authority, Appleby
publishing a verification checksum over a corpus rather than an expected output per input, and no
Murmur3 implementation ships in OpenSSL. Adopting it would remove the only external oracle the
system has and leave the verification this record requires with nothing independent to run against.
The second is the keyed property. Murmur3 takes a seed, so it reads as a weaker keyed function than
SipHash-2-4, and it is not a keyed function at all for this purpose: Aumasson, Bernstein, and
Boßlet demonstrated seed-independent multicollisions in MurmurHash3, colliding inputs generable in
bulk without knowing the seed. That is a property of the compression function, which no framing and
no domain tag repairs, and choosing it would permanently foreclose `SEC-010`, `SEC-014`, `SEC-015`,
[`0027`](0027-hash-seed-exposure-and-tenancy.md), and `OQ-07`.

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

SipHash-2-4 costs more per hash evaluation than an unkeyed alternative, and the margin at the sizes
this design uses is smaller than a large-buffer comparison suggests. Every framed input of
`HASH-030` is a few dozen octets, where the fixed costs of a call, the tail assembly, and the
finalisation dominate and a per-byte ratio does not apply.

The figures below were measured on 16 September 2026 on an AMD Ryzen 7 7840U, with reference C
implementations of all three functions compiled at `gcc -O2`, over the four framed input lengths
`HASH-030` produces at a 16-octet routing key and a 10-octet node identity, at 30 million iterations
per cell and four runs. Each cell gives the lowest and highest of the four runs in nanoseconds per
hash evaluation. The SipHash-2-4 implementation was checked against the 15-octet row of the
reference table before the timing ran. The source is `bench/hash-short-input.c`.

| Framed input | SipHash-2-4 | Murmur3 `x64_128` | xxHash64 |
|---|---|---|---|
| `keyHash`, 38 octets | 18.9 to 24.3 | 12.3 to 14.8 | 16.6 to 20.0 |
| `ringToken`, 47 octets | 21.3 to 24.6 | 12.5 to 14.9 | 19.2 to 22.4 |
| `slotScore`, 60 octets | 24.8 to 29.6 | 15.1 to 17.4 | 18.6 to 22.2 |
| `rvScore`, 67 octets | 27.1 to 33.0 | 16.2 to 18.4 | 17.9 to 20.0 |

SipHash-2-4 is 1.5 to 1.7 times Murmur3 and 1.1 to 1.5 times xxHash64 over that range, which is an
absolute saving of 8 to 11 nanoseconds per hash evaluation for the fastest alternative. A tuned Java
implementation lands within about eight per cent of the C figure, so the ratios carry into the
binding. Keys in the intended use cases are short, and the cost is bounded by one hash per key for
slot, ring, and range placement, and by one hash per virtual node for rendezvous placement.
[`0039`](0039-placement-cost-model-and-warning-thresholds.md) carries what that second bound costs.

Framing removes concatenation ambiguity. Without it, a node named `ab` at token index 1 and a node
named `a` at token index 11 could produce the same input bytes.

The seed is part of the topology, so rotating it moves every key. Seed rotation is a full data
migration and is not an operational routine.

Adversarial resistance is real only where the seed is not known to the adversary. A seed committed
to a public repository provides none. The zero default is chosen so that conformance vectors are
reproducible, and an operator who cares about crafted keys sets a random seed.

Every port carries a SipHash-2-4 implementation, verified against the reference vectors from the
original paper before any conformance vector runs. The hundred-line criterion above therefore stays
a selection criterion rather than becoming historical: under
[`0040`](0040-cryptographic-primitive-sourcing-policy.md) the project writes no cryptographic
algorithm by hand and this function is classified outside that rule, so each port still transcribes
it and still pays the cost of a length the transcriber can read in one sitting.

## Alternatives

MurmurHash3, in the `x64_128` form Cassandra uses. Fast and widely deployed, and 1.5 to 1.7 times
cheaper per hash evaluation than the function chosen. The ecosystem divergence the Context describes
is mostly repairable by naming the variant and the truncation rule, and the rejection does not rest
on it. Rejected because it has no per-input reference table of comparable authority and no OpenSSL
implementation, so adopting it would remove the only external oracle in the system, and because
seed-independent multicollisions make it an unkeyed function for this purpose whatever seed it is
given, which forecloses the adversarial property permanently.

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
