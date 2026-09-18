# 0077. Scale conformance level

Status: accepted. Date: 2026-09-17.

Extends the level partition of [`0059`](0059-place-conformance-level.md) and the revision basis of
[`0061`](0061-suite-revision-identifier.md), both of which stand.

## Context

Every topology the suite shipped was small enough to check by hand. The largest carried eleven
nodes, and the suite said nothing at all about behaviour above that size. As a correctness oracle
that is the right shape; as the only gate a second implementation passes through it is not.

[`99-roadmap.md`](../99-roadmap.md) plans Go, Rust, and Python ports, each declaring its levels
against a suite revision. A port could declare every level having never loaded a thousand-node
document, never built a ring of a million tokens, and never scored a thousand nodes for one routing
key. Nothing in the suite would have noticed.

The specification states a cost model. `PLACE-070` charges preparation, one routing call, and the
resident size of a `PreparedPlacement` against three tables; `PLACE-071` states that laziness
removes the ordering term under `ring`, `slot`, and `directory` and does not remove the scoring term
under `rendezvous`; `PLACE-073` compares two totals against thresholds `CFG-010` sets. None of it
was exercised. The `materialisedEntries` assertion of `CORE-046` is the one figure in the suite that
constrains a port's per-call work, and over an eleven-node topology it costs nothing to satisfy by
materialising the whole ordering and counting a prefix of it.

The reviews proposed one large topology as a preparation-and-load vector rather than a timing
vector, and were explicit that no timing bound belongs in a data file.

## Decision

The suite gains a conformance level `scale`, requiring `core`, testing the `routing` surface. It is
not optional, for the reason `hash`, `place`, and `core` are not: it tests the surface `CORE-110`
requires every implementation to expose.

It carries two generated documents and two vector files of the new kind `scale`.
`scale-ring-1000` carries a thousand nodes under `ring` with derived tokens, at a
`tokensPerWeightUnit` that puts the token total above the `ringWarnTokens` default and with four
nodes above `maxTokensPerNode`. `scale-rendezvous-1000` carries the same thousand nodes under
`rendezvous`, at a `virtualNodesPerWeightUnit` that puts the virtual node total above the
`rendezvousWarnVirtualNodes` default. Each case states the node count, the placement set count, the
total `PLACE-073` measures, the setting it was compared against, the shard cardinality, a prefix of
the enumerated shards, and six routing decisions.

The level asserts no timing and no resident size. A wall time is a property of a machine, a
language, and a runtime, and a bound written into a data file would be wrong on the next machine.
What the level asserts is the ordinary exact expectations of every other level, over inputs a port
that built the wrong structure arrives at slowly.

A case asserts a prefix of each ordering rather than the whole of it. The candidate ordering under
either document is a thousand entries long, `CORE-047` answers the whole of it on demand, and
`PLACE-015` makes the prefix a caller consumes the cost a routing call pays. A case that asserted
the thousand would ask a port for exactly the materialisation the level exists to discourage. The
prefix is eight entries, which is longer than the replication factor, so it reaches the fallback
tail of `REPL-017` as well as the replica prefix.

The declaration rule gains a sixth item: a port publishes the wall time and the peak resident size
its driver observed at `scale`, with the machine and the runtime they were observed on. No level is
reached or missed by that figure and the suite states no bound for it. It is published because the
author of a port is the reader it is for. The reference driver under `conformance/driver/python/`
prints both figures after the level table.

## Consequences

A port that materialises a candidate ordering on every routing call, stores the ring as a list it
scans, or rebuilds the ring per call, finishes `scale` in a time its author notices. Nothing fails,
which is the point: the suite reports what the port cost and leaves the reading to the person who
wrote it.

The suite revision moves, and a declaration made against the previous revision names a level table
that no longer matches. A v0.1 Java implementation declares `hash`, `place`, `core`, `scale`,
`failover`, `readAffinity`, and `fencing` reached, and `migration` excluded, where before it
declared six reached and one excluded.

Regeneration and a full driver run each cost about twenty seconds more on the reference, almost all
of it the ring build of over a million tokens in pure Python, and the driver's peak resident size
rises to a few hundred mebibytes. `generate_scale.py` prints what each file cost and the driver
prints what each file cost, so the figure is visible rather than absorbed. The reference is the
slowest implementation the suite will ever have; a port in a compiled language pays a small
fraction of it.

The two documents are about 170 kibibytes each and the two vector files about 13 kibibytes each. The
documents are stored once under `conformance/topologies/` and referenced by path, which is why they
are not carried twice: a file at `place` carries its documents inline, and `scale` requires `core`,
so the port running it has a document pipeline to read one with.

`PLACE-070`, `PLACE-071`, `PLACE-073`, `PLACE-074`, and `PLACE-077` become covered, and `CFG-014`
with them. `PLACE-075` and `PLACE-076` stay uncovered, because each states a bound to within a
constant factor rather than a value.

The level is not part of the Java binding's `check`, which runs the suite at level `core`. A port
runs `scale` for a declaration rather than on every build, which is the same treatment the binding
gives its benchmarks.

A port exposing neither `ring` nor `rendezvous` runs no file at `scale` and reaches the level
trivially. That is the strategy surface rule applied unchanged, and it is a real gap: the suite
offers no scale document for `slot` or `directory`.

## Alternatives

Adding the documents to `place` rather than adding a level. Rejected because `place` is the level a
port under development runs many times a day, and because the partition exists so that a level's
contents stay stable while the suite grows. A scale artefact for another strategy joins `scale`
without disturbing what `place` contains.

Adding them to `core`. Rejected for the same reason, and because `core` is what the Java binding's
`check` runs on every build.

Requiring `place` rather than `core`. Rejected because a file requiring `place` alone must carry its
documents inline, under the rule that a port at that level has no document pipeline, and a
170-kibibyte document carried twice is a file nobody opens. Every port reaches `core`, so requiring
it costs no port anything.

Asserting a wall time, a throughput, or an entries-walked count. Rejected because none of the three
is a property of the answer. A wall time is not comparable between machines, and an entries-walked
count would fix an implementation strategy that `RING-025` deliberately leaves open.

Generating the documents from a seed at run time rather than shipping them. Rejected because the
revision of [`0061`](0061-suite-revision-identifier.md) is a digest over the files a port runs, and
a document a driver generates is not one of them. The documents are small enough that shipping them
costs less than the machinery of agreeing on a generator in four languages.

Sizing the ring at the schema maximum, which is a thousand nodes at `maxTokensPerNode` of 65536.
Rejected because the reference would take an hour, and because the point of the level is a figure a
port's author reads rather than an endurance test.

Adding scale documents for `slot` and `directory`. Rejected for now. Under those two the figures of
`PLACE-070` are the `slotCount` and the `entryCount` the document spells, so a large one is a large
file rather than a large computation, and a directory large enough to cross `directoryWarnEntries`
would be a megabyte of authored matchers.
