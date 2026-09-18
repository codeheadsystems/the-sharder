# 0054. Range strategy withdrawal

Status: accepted. Date: 2026-09-17.

## Context

[`0002`](0002-placement-strategy-set.md) shipped five strategy kinds, and justified `range` as the
only option for an ordered keyspace with scans. The three use cases the design is validated against
have no ordered keyspace and no scan. [`../00-overview.md`](../00-overview.md) walks storage to
`ring`, tenant routing to `rendezvous` with overrides or to `directory`, and cache routing to
`rendezvous`; no walkthrough reaches `range`.

The kind was not cheap to carry. It held twenty-one requirements under the `RANGE` prefix, and it
was the sole reason the `SPLIT` prefix existed: `SPLIT-001` required orchestrated split and merge
under `range` and forbade them under every other kind, so the twenty-two `SPLIT` identifiers, the
lineage classifier, the two-epoch representation, the decomposition into local steps, and the
interaction with plan rebase served one strategy nobody had asked for. It carried
`PROP-023`, a balance bound whose precondition needs at least twenty thousand authored ranges, and
`PROP-026`, which states that no conformance suite can test it. `OQ-12` asked what to do about that
pair and admitted no evidence could settle it at a workable topology size.

It also generated the design's two sharpest local traps.
[`0043`](0043-assignment-mode-defaults.md) records that an absent `assignment` selects `derived`
under `slot` and `explicit` under `range`, so one member name selected opposite modes under two
kinds, and that record's own conclusion was to state the trap loudly rather than remove it.
`SPLIT-171` required a handoff whose local split had already succeeded to move to
`failed(undetermined)` without a re-observation, which was the single carve-out in `MOVE-233`.

[`../99-roadmap.md`](../99-roadmap.md) already stated the reversibility: a further kind is a minor
format version, and renaming a shipped kind is not available. Carrying `range` now costs
forty-three requirements and a lineage subsystem; adding it later costs a minor version.

## Decision

The `range` strategy and everything that hangs off it leave format version 1.0.

Withdrawn under the convention of [`0053`](0053-requirement-withdrawal-convention.md): `RANGE-001`
through `RANGE-042`, the whole `SPLIT` prefix from `SPLIT-001` to `SPLIT-211`, `PROP-023`,
`PROP-026`, and `CFG-063`. The `RANGE` and `SPLIT` prefixes leave the prefix table with them.
`OQ-12` leaves [`../90-open-questions.md`](../90-open-questions.md) under the same convention,
because the bound it asked about no longer exists.

The `rangeScore` function and its domain tag `sharder/range-rendezvous/v1` leave `HASH-030`, which
now names three functions rather than five; `slotScore` leaves it under
[`0055`](0055-slot-derived-assignment-withdrawal.md). The `strategyRange` branch leaves
[`../topology-v1.schema.json`](../topology-v1.schema.json) together with the `hexBytes` definition
that only a range bound used.

Three settings leave the configuration surface, `splitAdviceBytes`, `splitAdviceKeys`, and
`splitAdviceRequests`, and one permanent event name leaves `OBS-020`, `sharder.shard.split_advice`.
The `unalignedRanges` cause leaves the closed set of `ERR-050` and `noRangeEntry` leaves the closed
set of `ERR-021`.

Skew detection is not part of the withdrawal and keeps its surface. `SPLIT-021` carried the
`ShardMetricsSource` and `ShardReport` shapes that `OBS-030` through `OBS-035` consume, and
`SPLIT-041` carried the key skew comparison that `OBS-032` reports. The shapes move to `OBS-036`,
which takes the next free number in the skew detection group, and `OBS-032` states the comparison
directly. `ShardReport` loses its `bytes` and `keys` members, because the split advice thresholds
were their only reader.

## Consequences

An ordered keyspace deployment cannot use format version 1.0. That deployment is not among the three
the design is validated against, and [`0002`](0002-placement-strategy-set.md) already offers the
pluggable strategy interface to an integrator who has one.

`range` returns as a fifth core kind in a minor format version if a deployment asks for it, which
[`../99-roadmap.md`](../99-roadmap.md) now records. Returning it means restating the withdrawn
requirements under fresh identifiers, because `RANGE-011` is never reused. That is the cost the
permanence rule imposes, and it is paid once against the forty-three identifiers carried in every
revision until then.

Split and merge are no longer available under any kind. `MOVE-241` lists the kinds that support
orchestrated migration and `ring`, `slot`, and `directory` remain, so a topology change under any
of them is an ordinary ownership delta driven through the handoff coordinator. A token addition
under `ring` divides a token range as a consequence of placement, which `SPLIT-001` already
distinguished from a split.

`MOVE-233` loses its only carve-out. Every handoff that reaches `failed` with the kind
`undetermined` is admissible for a re-observation, which is what
[`0051`](0051-recovery-from-an-undetermined-cutover.md) wanted before split lineage took an
exception to it.

The `migration` conformance level loses the split lineage vectors and keeps the rate control
formulas and the handoff scenarios. The level structure of
[`0052`](0052-conformance-level-partition.md) is unchanged, because a level is a set of artefacts
and removing artefacts from one does not move the partition.

## Alternatives

Keeping `range` and withdrawing `SPLIT-*` alone. Rejected because `range` without split and merge is
a strategy whose shard boundaries an authority can never move: `SPLIT-091` refused a plan across an
unaligned classification, and with no lineage at all every boundary change becomes unplannable. The
kind would ship in a shape nobody would deploy.

Keeping `range` with `explicit` assignment alone, dropping the derived branch. Rejected because the
derived branch is not where the cost is. The bound comparison, the covering range rule, the
contiguity validation, and the lineage subsystem all belong to the authored form, and they are what
a port has to implement before it can declare `core`.

Moving `range` to a registered strategy outside the core set, shipped in the repository. Rejected
for this batch because the registration surface is itself incomplete: no requirement states how a
custom kind is named, what a document naming an unregistered kind does, or whether a registered kind
may shadow a core one. Shipping `range` against a surface that is not specified would move the cost
rather than remove it.

Deferring the decision to v0.2, when the migration surface is implemented. Rejected because the
strategy kind names and the closed core set are a one-way door once the format publishes, and the
schema, twenty-two topology documents, and the `core` conformance level all carry `range` today.
