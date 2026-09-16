# 0036. Spread relaxation ladder direction

Status: accepted. Date: 2026-09-16.

Supersedes the ladder direction fixed by
[`0015`](0015-spread-degradation-algorithm.md), which stands in every other respect.

## Context

[`0006`](0006-failure-domain-model.md) fixed degradation as dropping spread requirements from the
finest named level upwards, and [`0015`](0015-spread-degradation-algorithm.md) rendered that as a
ladder in which stage `k` enforces the coarsest `m-k` levels. The normative specification carried it
as `SPREAD-010`, and `SPREAD-011` tested whether two entries share a domain by comparing
`domain_path(x, L)`, the tuple of identifiers from the coarsest declared level through `L`.

The two do not compose. A domain path at a finer level extends the path at every coarser level, so
two nodes that agree at a finer level agree at every coarser one. Requiring distinct paths at the
coarsest enforced level therefore implies distinct paths at every finer level in the same stage, and
a stage admits exactly what its coarsest enforced level admits.

Because stage `k` dropped the finest levels first, every stage from `0` to `m-1` retained
`spread[0]` and admitted the same entries. Only stage `m`, enforcing node distinctness alone,
differed. A `spread` of `["region", "zone", "rack"]` had two outcomes rather than four: distinct
regions, or nothing beyond distinct nodes. A topology that could not place four replicas in distinct
regions never settled for distinct zones, which is the behaviour the ladder exists to provide.

The conformance suite measured it rather than reasoning about it.
`conformance/vectors/spread/relaxation-stages.json` records every stage and counts the distinct
outcomes, and its `spread-ladder` cases reported `distinctStageOutcomes` of 2 against the four
stages `SPREAD-017` permits.

## Decision

Stage `k` enforces node distinctness together with the finest `m-k` levels of `spread`, which are
the levels at indices `k` through `m-1`. Stage `0` still enforces every named level and stage `m`
still enforces distinctness alone; the stages between them now differ.

Under the `domain_path` comparison this makes stage `k`, for `k` below `m`, admit exactly the
entries that enforcing `spread[k]` alone admits. The ladder is therefore ordered from the strongest
constraint at stage `0` to node distinctness at stage `m`, with `m + 1` rungs that differ wherever
the topology carries a distinct domain at each named level. An entry stage `k` admits against a set
of already admitted entries, stage `k + 1` admits against that set too. `SPREAD-018` and
`SPREAD-019` state those two properties so that a port can check its ladder without reconstructing
the argument.

The replica prefixes the rungs produce are not nested, because the greedy walk halts at the
replication factor and a weaker rung can fill the prefix before it reaches an entry a stronger rung
admitted. `SPREAD-019` says so, so that a port does not test the wrong invariant.

`SPREAD-015` reports the `k` coarsest levels of `spread` as the relaxed levels, at indices `0`
through `k-1`, rather than the `k` finest.

`spreadPolicy` of `strict` is unchanged: it evaluates `select(0)` alone, and stage `0` enforces the
same levels under both directions.

The selection rule of `SPREAD-012`, the fallback of `SPREAD-013`, the stage bound of `SPREAD-017`,
and the purity of `SPREAD-020` are unchanged.

Measured on the `spread-ladder` topology of the conformance suite, a `spread` of
`["region", "zone", "rack"]` at factor 4 over six nodes goes from two distinct stage outcomes to
four, and the chosen stage moves from 3 to 2: the builder now places four replicas in four distinct
racks rather than four replicas subject to node distinctness alone.

## Consequences

Degradation now proceeds from the coarsest requirement to the finest, which is the order in which
the requirements weaken. A topology that cannot spread across regions spreads across zones, then
across racks, then across nodes. That is what an operator reading
[`0006`](0006-failure-domain-model.md) expects of "degrade one level at a time", even though
[`0006`](0006-failure-domain-model.md) named the direction the other way round.

The redundancy [`0015`](0015-spread-degradation-algorithm.md) anticipated is gone. It predicted that
naming several nested levels produces redundant rungs, and under the old direction every rung below
`m` was redundant rather than some of them. Under the new direction no rung is redundant when the
topology has a distinct domain at each named level.

Preference lists change for any topology whose `replication.spread` names two or more levels and
whose placement cannot satisfy every level. A topology naming one level is unaffected, because at
`m` of 1 the two directions agree. The conformance suite ships one topology with a multi-level
spread, so the observable change in the suite is confined to
`conformance/vectors/spread/relaxation-stages.json` and
`conformance/vectors/spread/degradation-ladder.json`.

The change is a behaviour change to a released ordering rule and would move replicas in a deployed
cluster. It is taken before any implementation ships, so no data has been placed under the old
ladder.

An implementation that read `SPREAD-010` literally and implemented the collapsed ladder is now
non-conforming, and the relaxation-stages vector is what tells it so.

## Alternatives

Keeping the direction and changing `domain_path` to compare the identifier at `L` alone rather than
the path from the coarsest level. That makes the levels independent, so dropping the finest first
weakens the constraint as [`0006`](0006-failure-domain-model.md) described. Rejected because
[`0006`](0006-failure-domain-model.md) locked ancestor-scoped domain identifiers: a rack named `r01`
in one zone and a rack named `r01` in another are distinct racks, and comparing the identifier alone
would treat them as one. The repair would trade a collapsed ladder for a wrong domain comparison.

Keeping the direction and defining a stage's constraint as its finest enforced level rather than the
conjunction of its levels. Arithmetically equivalent to the decision taken, and rejected because it
leaves `SPREAD-010` reading as though it drops the finest level first while doing the opposite,
which is the reading that produced the defect.

Dropping the ladder for an occupancy cap per domain, incremented one level at a time.
[`0015`](0015-spread-degradation-algorithm.md) rejected it and
[`../90-open-questions.md`](../90-open-questions.md) carries it as `OQ-04`; nothing found here
changes that argument.

Withdrawing `SPREAD-010` through `SPREAD-021` and renumbering. Rejected because the conformance
suite is keyed to requirement identifiers, and a renumbered identifier silently breaks a test.
