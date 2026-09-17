# 0049. Ownership delta computed on demand

Status: accepted. Date: 2026-09-17.

## Context

`TOPO-211` said an implementation exposes the ownership delta between two snapshots. Exposing is an
operation. Two other places read as though it is a product of installation: `MOVE-101` said the
library publishes the ownership delta and an event, and the rebalance data flow in
[`../00-overview.md`](../00-overview.md#rebalance-data-flow) put the delta between the snapshot
swap and the integrator's decision, on the install path.

The readings are not equally affordable. A delta walks every shard the two snapshots enumerate and
evaluates a candidate ordering for it under each, which is two preference list constructions per
shard, each carrying the spread pass of `REPL-012`. Under `ring`, `RING-031` makes a shard a
distinct token value, and the storage walkthrough in
[`../00-overview.md`](../00-overview.md#storage-node-placement) reaches `maxTokensPerNode` at
`tokensPerWeightUnit` of 4 with weight written in gigabytes. At 1000 nodes that is roughly four
million shards and eight million ordering evaluations, in every caller process in the fleet, on
every epoch change, whether or not that caller migrates anything.

[`../40-java-binding.md`](../40-java-binding.md) had already chosen: `OwnershipDelta.between` is a
static factory over two snapshots, which is the on-demand reading. A second port written from the
specification alone would have been free to choose the other.

Two further defects sit inside the same operation. The delta had no stated order, and the reference
implementation sorted shard identifiers as octets, which under `slot` disagrees with the ascending
slot index `SLOT-031` enumerates from the moment `slotCount` exceeds 10. Separately, `TOPO-211` and
`FENCE-081` both called the replica set the first `factor` entries of the preference list, which
after the `SPREAD-006` repair is wrong wherever a shortfall occurs: `REPL-020` produces a replica
prefix of `r` entries with `r` below `factor`, and the entries between `r` and `factor` are fallback
tail entries under `REPL-013`, placed there because spread excluded them.

## Decision

The delta is an operation the integrator calls, and installation computes none.

`TOPO-211` states the operation. `TOPO-212` forbids computing a delta as a step of `TOPO-001`, as
part of installing a snapshot, or as a precondition of a routing call reading an installed snapshot,
and moves `topology.delta` to the call. `MOVE-101` now says the library emits `topology.installed`
and exposes the delta, and the integrator computes it. The overview's rebalance flow puts the delta
on the integrator's branch, after the decision to migrate rather than before it.

`PLACE-075` states what the operation costs: `2S` candidate orderings over the shards the two
snapshots enumerate between them, each costing what the routing table of `PLACE-070` gives for the
configuration. The figure belongs in neither the preparation table nor the routing table, because
the delta is on neither path.

`TOPO-213` states the order. The entries for shards the second snapshot enumerates come first, in
the order `shards` gives for that snapshot under `PLACE-031`, followed by the entries for shards
only the first snapshot enumerates, in the order `shards` gives for it. `PLACE-031` already fixes an
enumeration order per strategy kind, so the delta inherits one that a reader of a `slot` delta and a
reader of a `range` delta each already expect, and the requirement says plainly that ordering by the
identifier's octets is not it.

`TOPO-211` and `FENCE-081` now name the replica set as the entries whose role is `replica` under
`REPL-017`, which are the first `r` entries for the achieved replica count `r` of `REPL-020`.
`FENCE-091` follows `FENCE-081`, so `ownership` and `ownershipStable` answer the same question over
the same entries.

## Consequences

Installing a snapshot costs what `TOPO-001` costs and nothing further. A caller that routes and
never migrates, which is every caller in the cache and tenant routing use cases, pays nothing for
the delta at any fleet size. A caller that migrates pays `PLACE-075` once, on the thread it chose,
at the moment it asked.

`topology.delta` no longer marks an epoch change. An operator watching for one watches
`topology.installed`, which `MOVE-101` now names, and `topology.delta` marks an integrator asking
what moved.

The delta's order is now observable, so a port that sorts identifiers as octets fails rather than
passing on a corpus too small to tell the two orders apart. The delta vectors gain a `slot`
topology at `slotCount` 16, where the ascending index order and the octet order disagree from the
second entry, and a spread topology where a degraded placement makes `r` smaller than `factor`.

Correcting `factor` to `r` changes which nodes a delta and a fencing verdict call owners, but only
under a shortfall. Where the replica prefix is full the two readings name the same entries, so every
existing vector over a full prefix is unmoved and the vectors that move are the ones the suite did
not previously carry.

## Alternatives

Computing the delta on install and caching it on the snapshot. Rejected because the cost is paid by
every caller and the result is read by almost none of them, and because `TOPO-021` would place that
work before a routing call may observe the new snapshot, converting a topology push into a
fleet-wide pause proportional to the shard count.

Computing it lazily on install, as a memoised field the first reader forces. Rejected because it
hides the cost rather than removing it: the first reader is whichever thread happens to touch the
field, the memoised value pins a second snapshot for the lifetime of the first, and
`TOPO-101` makes a snapshot immutable, which a field that populates itself contradicts in spirit.

Ordering the delta by the shard identifier's octets, and letting `SLOT-031` be the order of
`shards` alone. Rejected because a reader of a `slot` delta reads slot 2 after slot 10, and because
the two orders agreeing for `slotCount` up to 10 is exactly the condition under which a defect
survives a test corpus.

Ordering the delta by the source snapshot's enumeration first. Rejected because the target snapshot
is the one the integrator is moving towards, a shard the target does not enumerate is a shard being
retired, and the retired shards read better as a tail than as an interleaving.

Keeping `factor` in `TOPO-211` and `FENCE-081` and treating a fallback entry inside the first
`factor` positions as an owner. Rejected because it contradicts `REPL-017`, which labels those
entries `fallback`, and `FAIL-041`, which makes writing to one a substitution the caller performs
knowingly. A recipient that called itself an owner on that basis would serve a request the topology
gave it no claim to.
