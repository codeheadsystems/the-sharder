# 0088. Lineage inside the migration surface

Date: 2026-09-19

Status: accepted

## Context

[`0086`](0086-shard-lineage-derived-from-extent.md) adds the `LIN` prefix. Where its requirements
are tested, and which implementations they bind, is a separate judgement, because
[`0058`](0058-conformance-surfaces.md) makes a surface a unit an implementation exposes whole or not
at all and [`0052`](0052-conformance-level-partition.md) fixes the partition of the suite into
levels.

Three placements were available: a new `lineage` surface with a level of its own, the existing
`migration` surface and level, or the `routing` surface alongside the ownership delta.

## Decision

Lineage belongs to the existing `migration` surface and is tested at the existing `migration` level.
No surface is added and the level partition does not move.

## Consequences

A port that declines the `migration` surface implements no lineage and is bound by no `LIN`
requirement, which is the same answer `0058` already gives for every other part of the handoff
coordinator.

A port that exposes `migration` implements lineage for every strategy surface it also exposes. There
is no configuration in which a port exposes the handoff coordinator over `ring` and has no answer
for a token addition, because that configuration is the one that emits the self-handoffs `0086`
describes.

The level partition is untouched, symmetrically with `0054`'s own argument that removing artefacts
from a level does not move the partition. `migration` already requires `failover`, and it gains
vector files rather than a new requires edge.

The Java port declares every level, so it pays for lineage at `migration` and for the `TOPO-213`
repair at `core`. No other port exists. A future port declining `migration` pays nothing, and a
future port exposing only `rendezvous` pays nothing beyond the `core` repair, because `rendezvous`
enumerates no shard and `MOVE-241` already excludes it.

## Alternatives

A `lineage` surface of its own. Rejected because it would let a port declare `migration` without it,
which is the broken configuration named above.

Lineage on the `routing` surface, as a parent member of `ShardChange`. Rejected in `0086`'s
Consequences: it would put ring interval containment and directory precedence at the `core` level,
which a port cannot decline, for behaviour an integrator routing tenant identifiers never uses.

A new conformance level. Rejected. A level tests one surface, `migration` is that surface, and a
second level over the same surface would let a port claim the coordinator while declining the part
that makes it correct.
