# 0059. Placement conformance level

Status: accepted, with the drift check named below rehomed to `build_manifest.py`. Date: 2026-09-17.

The partition, the inlined documents, and the retention of `topologyDigest` as provenance all stand.
One mechanism named below does not sit where this record put it. Step 5 of the driver contract in
[`../30-conformance.md`](../30-conformance.md#driver-contract) asserts `topologyDigest` only for a
file that names a topology and carries none, at every level, so a port at `core` does not check a
moved file's inline copy against the document it was taken from. `build_manifest.py` performs that
check when it builds the manifest, and refuses a drifted copy there.

## Context

The suite had nothing between the hash primitive and the whole document pipeline. `hash` was two
files and 34 cases, and the next level down the prerequisite chain required a strict JSON reader
that refuses a duplicate member and an unpaired surrogate under `TOPO-002`, a structural validator
matching the published schema, a semantic validator, an RFC 8785 canonicaliser, SHA-256, snapshot
installation, placement preparation, a strategy, and the preference list builder before a single
routing vector could go green.

A port therefore got external feedback in its first week and then none until its ninth. That is a
schedule risk rather than a coupling smell: the pipeline is eight to ten weeks of work, and every
placement decision a port makes in that window is unchecked against anything.

Nothing in the design required it. [`0052`](0052-conformance-level-partition.md) established that a
level is data and that one added later is a data change, and
`conformance/generator/sharder_ref/placement.py` already takes a snapshot rather than a document,
with `sharder_ref/topology.py` owning validation. The reference already separated the two things the
suite joined.

## Decision

A `place` level sits between `hash` and `core`, and `core` requires it.

A vector file at `place` carries, in `topologyDocuments`, a copy of every topology document it
names, keyed by the path the rest of the suite names it by. The suite guarantees that document
valid. A port at `place` prepares placement over it, which is stage 6 of `TOPO-001`, and performs no
stage 1 through 5: no strict JSON reader for the document format, no schema validation, no semantic
validation, no canonical form, no digest, no provider, and no snapshot lifecycle. It needs a JSON
reader for the vector file, the hash construction, and the placement engine.

`write_json` in `conformance/generator/generate.py` attaches the documents, by walking the payload
for every path that names one, so a vector set that moves to `place` later carries its documents
without its generator being taught to. `build_manifest.py` refuses a file at `place` that names a
document it does not carry, a copy that differs from the document at that path, and a file at any
other level that carries one.

The cases at `place` are moved from `core`, not new. Forty-four vector files move, carrying 284 of
`core`'s 408 cases. `core` keeps 124 cases in six files: the canonical form and digest vectors,
document validation, the ownership delta, the error taxonomy, skew detection, and the property
witnesses, together with its one scenario and its twenty-two properties. The rule that decided each
file is the one the levels now state: a file whose cases take a topology and a key and expect an
ordering, a shard, or a routing key belongs to `place`, and a file whose cases take a document and
expect a validity, a canonical form, a digest, an epoch decision, or a delta belongs to `core`.

No expected value moved. Every case body is byte-identical across the move, and the only members
that changed on a moved file are `level` and the added `topologyDocuments`. One file gained
expectations rather than losing them: `vectors/overrides/pin-keeps-shard.json` named no topology and
the reference driver held the path in its own source, so the file now names
`topologies/ring-pinned.topology.json` and carries its digest like every other file.

A moved file keeps `topology` and `topologyDigest`. They name the document the inline copy was taken
from. A port that reaches `core` computes the digest and checks it, which is also the check that the
inline copy has not drifted; a port at `place` alone has no canonical form to check it with.

Properties and scenarios stay where they are. A property run samples a hundred thousand keys and a
scenario replays an installation sequence, and neither is the first external feedback a port needs.
`place` is vector files alone, which is what keeps it reachable in week three.

## Consequences

A port at `place` runs 318 cases across 46 files once the hash level is added, against the 34 it
could run before. The placement engine, the four strategies, the key transforms, overrides,
replication, the spread ladder, the tie-breaks, and shard enumeration are all checked against the
reference before the port reads its first document.

A declaration of `core` reaches exactly the cases it reached before, because `core` requires
`place`. No port that has declared a level loses coverage, and no expected value it passed against
has moved.

The suite carries a second copy of each document a `place` file names. The copies are written in one
regeneration from one source and `build_manifest.py` compares them, so the duplication cannot become
a divergence; it costs file size and nothing else.

`core`'s name now covers less than it did. It is the document pipeline, the snapshot lifecycle, and
what the library reports, rather than the whole routing decision. The level structure is what a
declaration names, and `core` still means everything below it, so no declaration changes meaning.

## Alternatives

Generating new `place` cases rather than moving `core`'s. Rejected because the same expectation
would then appear twice in the suite under two levels, which is the duplication
[`0052`](0052-conformance-level-partition.md) partitioned the suite to remove, and because a port
would run the same key against the same topology twice and learn nothing the second time.

Moving the routing vectors and dropping `topologyDigest` from them. Rejected because it deletes 27
expected values from the suite to make a level tidy. Carrying the digest as provenance keeps every
value and costs a port at `place` nothing, since it is not asked to compute one.

Inlining the document per case rather than per file. Rejected because a case would then differ from
the case at `core` that it was, and the move would no longer be a move.

Giving `place` the placement properties as well. Rejected because the property layer needs a
sampling harness over a hundred thousand keys, which is the slowest thing in the suite to implement
and the last thing a port wants in its first green level.
