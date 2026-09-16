# 0015. Spread degradation algorithm

Status: accepted, with the ladder direction superseded by
[`0036`](0036-spread-relaxation-ladder-direction.md). Date: 2026-09-16.

The direction this record fixes, stage `k` enforcing the coarsest `m-k` levels, collapses against
the `domain_path` comparison the specification uses, so stages `0` through `m-1` admit the same
entries. [`0036`](0036-spread-relaxation-ladder-direction.md) reverses it and records why.
Everything else here, including whole-stage evaluation, the minimum over a finite ladder, the
`strict` rule, and the rejected alternatives, stands unchanged.

## Context

[`0006`](0006-failure-domain-model.md) fixes the shape of degradation: under `spreadPolicy` of
`relaxed`, spread requirements are dropped from the finest named level upwards until a placement
exists, and under `strict` the preference list is shorter than the replication factor rather than
less well spread. It leaves the algorithm to the normative specification.

The algorithm has to settle three things that the shape does not. What "a placement exists" means
when a greedy walk over the candidate ordering is the only walk available. Whether a relaxation
applies to the whole replica prefix or only to the entries that could not be placed under the
stronger requirement. And what happens when no relaxation, including dropping every level, produces
a full replica prefix.

All three have to be settled identically in every implementation, because two callers deriving
different preference lists for the same key at the same epoch is the failure mode the whole design
exists to prevent.

## Decision

Degradation is a ladder of relaxation stages, evaluated whole.

Let `spread` hold `m` levels, coarsest first. Stage `k`, for `k` from `0` to `m`, enforces node
distinctness together with the coarsest `m-k` levels. Stage `0` enforces everything named; stage `m`
enforces distinctness alone. `select(k)` is the replica prefix produced by one greedy walk over the
candidate ordering under stage `k`'s constraints, admitting each entry that does not conflict with
an entry already admitted, and halting at the replication factor or at the end of the ordering.

Under `relaxed`, the builder uses `select(k)` for the smallest `k` that yields a full replica
prefix. Where no `k` yields one, it uses `select(m)`, which is the longest prefix any stage can
produce. Under `strict`, the builder uses `select(0)` and evaluates no other stage.

Each stage is evaluated from scratch, not patched onto the previous one. At most `m + 1` stages
exist and `domainLevels` holds at most eight levels, so a routing key costs at most nine walks.

The chosen stage and the levels it does not enforce are reported on the routing decision and in an
event, so an operator can see that a topology is placing less well spread than it asked for.

The specification carries this as `SPREAD-010` through `SPREAD-021`.

## Consequences

A stage is a pure function of the snapshot and the routing key, and the choice between stages is a
minimum over a finite ladder, so two implementations that agree on the candidate ordering agree on
the preference list. That is the property the conformance suite tests.

Naming several levels in `replication.spread` produces redundant rungs. Distinct regions imply
distinct zones, so a topology asking for `["region", "zone"]` has a stage 1 that admits exactly what
stage 0 admits. The ladder is still correct and still terminates; it simply reaches the level that
matters in two steps rather than one. The redundancy is the price of a single rule that does not
special-case nested levels.

Evaluating each stage from scratch means a relaxation can change which nodes are replicas, not only
how many. A topology at factor 3 across two zones places two replicas at stage 0 and three at stage
1, and the third is whichever candidate comes next in the ordering rather than whichever was skipped
first. Patching the prefix instead would have preserved the first two placements at lower cost, and
would have made the result depend on the order in which stages were evaluated rather than on the
stage alone.

Degradation under `relaxed` is all-or-nothing per level. A topology at factor 4 across three zones
drops the zone requirement entirely and may place three replicas in one zone, rather than settling
for two in one zone and one in each of the others. An occupancy cap per domain would express the
better outcome, and it is not what [`0006`](0006-failure-domain-model.md) locked.
[`../90-open-questions.md`](../90-open-questions.md) carries it as `OQ-04`.

`strict` makes a shortfall visible rather than silently degrading, which suits a deployment whose
durability argument depends on the spread. It also means a zone outage reduces the replica count
rather than the spread, and a caller that treats a short preference list as an error refuses writes
during that outage.

## Alternatives

Relaxing per replica slot rather than per list: place as many replicas as stage 0 allows, then fill
the remainder at stage 1, and so on. Rejected because the result then depends on the sequence of
stages traversed rather than on the stage in force, which is a longer thing to specify and a harder
thing to test. It also produces a preference list whose entries were admitted under different
constraints, so no single statement describes the spread of the list.

An occupancy cap per domain, incremented one level at a time, in the manner of Cassandra's
`NetworkTopologyStrategy` tolerance for uneven datacentre counts. Better behaviour at factor 4
across three zones, and rejected here because [`0006`](0006-failure-domain-model.md) locked drop
semantics and because the ladder over caps has no obvious total order once two levels are both
capped.

Backtracking search for a placement satisfying every level, rather than a greedy walk. Rejected
because the candidate ordering is the thing that carries the balance and minimal movement
properties, and a search that reorders it to satisfy spread discards both. The greedy walk preserves
the ordering by construction.

Failing the routing call when stage 0 cannot be satisfied under `relaxed`. Rejected because
`relaxed` is the default and a first-time integrator whose test cluster has one zone would find that
nothing routes.

Reporting the relaxation only as a metric rather than on the routing decision. Rejected because a
caller deciding whether a write is durable enough needs to know, per call, whether the replicas it
was given are spread as the topology asked.
