# 0055. Slot derived assignment withdrawal

Status: accepted. Date: 2026-09-17.

## Context

[`0011`](0011-derived-assignment-virtual-nodes.md) states the case against itself in its own
Context. Adding `virtualNodesPerWeightUnit` and `maxVirtualNodesPerNode` to the `slot` and `range`
strategy objects "would make the format wider for a code path with no known demand", and the
response was to ship the code path with the two constants hardcoded rather than to ship neither.

The constants are not the cost. `slot` with `derived` assignment is the source of the
`slotCount * V` product, which `SLOT-001` permits to reach 10^9 hash evaluations.
[`0039`](0039-placement-cost-model-and-warning-thresholds.md) measures that at about twenty-nine
seconds inside stage 6 of `TOPO-001`, serialised under `TOPO-041`, on a thread the integrator
supplied, and it added `derivedWarnEvaluations`, the `sharder.topology.derived_large` event, a row
of each `PLACE-070` table, and the `PLACE-072` escape clause to make that survivable. The most
expensive single operation the library performs existed for a configuration the design itself
records as having no known demand.

The conceptual objection is the stronger one. [`0002`](0002-placement-strategy-set.md) borrows
`slot` from Kafka's partition assignment and from Redis Cluster, where the point of a fixed slot map
is that an authority outside the library assigns it and publishes it. Deriving the assignment inside
the library is the library reinventing the thing the strategy was chosen to delegate.

With `range` withdrawn under [`0054`](0054-range-strategy-withdrawal.md), `slot` is the last kind
whose `derived` mode scores a shard identifier rather than a routing key, so withdrawing it also
retires the shard-scored ordering rule entirely.

## Decision

Derived assignment leaves the `slot` strategy. `explicit` is the only mode the kind accepts.

Withdrawn under the convention of [`0053`](0053-requirement-withdrawal-convention.md): `SLOT-020`
through `SLOT-023`, `PROP-022`, `PROP-033`, and `PLACE-072`. `SLOT-024` is amended rather than
withdrawn, because it still answers the question of what an absent `assignment` member selects; the
answer becomes `explicit`.

The `slotScore` function and its domain tag `sharder/slot-rendezvous/v1` leave `HASH-030`, and the
`s` field leaves `HASH-031`. `PLACE-050` loses its two hardcoded rows, so a derived virtual node
count is configurable under `rendezvous` and a derived token count is configurable under `ring`, and
no configuration takes a count the document cannot name.

`derivedWarnEvaluations` and the `sharder.topology.derived_large` event go with it. `ring` with
`derived` keeps `ringWarnTokens` and `sharder.topology.ring_large`, and `rendezvous` keeps
`rendezvousWarnVirtualNodes` and `sharder.topology.rendezvous_large`, so no surviving configuration
can reach the third threshold or emit the third event. `PLACE-073` compares two totals rather than
four products, and `PLACE-074` is restated over a total accumulated in sixty-four bits rather than
over a product formed in them.

The `assignment` member stays on the `slot` strategy object in
[`../topology-v1.schema.json`](../topology-v1.schema.json) with the single value `explicit` and the
default `explicit`. A document that names its mode stays valid, and `SLOT-024` keeps a member to
govern.

`OQ-08` leaves [`../90-open-questions.md`](../90-open-questions.md) under the withdrawal convention.
It asked when the collision search over `sharder/slot-rendezvous/v1` would return a pair, and that
domain no longer exists.

## Consequences

A `slot` document that named only `kind` and `slotCount` stops being valid. Such a document placed
every key by a rule the authority did not author, which is the opposite of what a slot map is for,
so it is refused at load rather than routed against a derivation.

The default an absent `assignment` selects changes from `derived` to `explicit`.
[`0043`](0043-assignment-mode-defaults.md) records that format version 1 could not make that change,
because a document omitting the member was already valid under the published schema. The format has
not published, so the window is open, and it closes at v0.1.

The trap [`0043`](0043-assignment-mode-defaults.md) documented is gone rather than stated loudly.
One member name no longer selects opposite modes under two kinds: `ring` carries `tokenAssignment`
with two modes and `slot` carries `assignment` with one.

The 10^9-evaluation preparation path is gone, and with it the only configuration in which stage 6 of
`TOPO-001` costs seconds. `PLACE-070`'s preparation table now has no entry whose cost is a product
of two unbounded counts.

`slot` keeps its whole authored surface. The covering entry rule, the slot range strings, the
coverage validation, the shard identifiers, and the enumeration order are unchanged, and no expected
value in a surviving `slot` vector moves.

## Alternatives

Keeping derived assignment and adding the two sizing fields to the `slot` strategy object, which
[`0011`](0011-derived-assignment-virtual-nodes.md) rejected as format width. Rejected again for a
stronger reason: it makes the expensive configuration configurable rather than absent, and the cost
it exposes is a load-time cost an operator cannot see until a topology push stalls.

Keeping derived assignment and bounding `slotCount * V` by a validation rule.
Rejected because [`0039`](0039-placement-cost-model-and-warning-thresholds.md) already rejected a
refusal on a cost figure: a validation rule over a cost would make the set of acceptable documents
depend on a caller-local setting, which `CFG-002` and `CFG-004` forbid between them.

Removing the `assignment` member from the `slot` strategy object entirely. Rejected because a
document that writes `"assignment": "explicit"` is legible and would become invalid under the rule
that an unknown member is a validation failure, and because `SLOT-024` would then be withdrawn for
no gain beyond one line of schema.

Withdrawing `PLACE-074` with `PLACE-072`. Rejected because `PLACE-073` still compares two totals
against thresholds, each of which is a sum over the placement set that grows with the node count and
with the cap in force. The requirement is restated over a sum rather than a product and keeps its
identifier, because it still answers the same question.
