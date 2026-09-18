# 0043. Assignment mode defaults

Status: accepted, with the `range` default withdrawn by
[`0054`](0054-range-strategy-withdrawal.md) and the `slot` default changed by
[`0055`](0055-slot-derived-assignment-withdrawal.md). Date: 2026-09-17.

The defect this record repaired was real and the repair holds for `ring`. The asymmetry it chose to
state loudly rather than remove is gone instead: `range` is withdrawn, and `slot` accepts `explicit`
alone, so `SLOT-024` says an absent member selects `explicit`. That change was unavailable to format
version 1 at the time this record was written and is available now only because the format has not
published. `RANGE-022` and the `range-explicit-missing-nodes` document are withdrawn with the kind.

## Context

Three strategy kinds carry a member that selects between a derived assignment and an authored one:
`ring` carries `tokenAssignment`, and `slot` and `range` each carry `assignment`. All three members
are optional, and the mode an absent member selects appeared nowhere in
[`../10-specification.md`](../10-specification.md) and nowhere in the prose of
[`../20-topology-format.md`](../20-topology-format.md). It appeared only as a `"default"` annotation
in [`../topology-v1.schema.json`](../topology-v1.schema.json), which is an annotation rather than an
assertion: a JSON Schema validator does not apply it, and
[`../40-java-binding.md`](../40-java-binding.md) says the Java structural validator "enforces
exactly what `topology-v1.schema.json` declares", which by the letter enforces nothing here.

The annotations are not symmetric. `ring` and `slot` default to `derived` and `range` defaults to
`explicit`, so the same member name under two kinds selects opposite modes. `SLOT-020`, `SLOT-032`,
`RANGE-030`, and `RANGE-042` each say "according to the configured assignment mode" and never say
what mode an absent member configures.

No vector caught it. Every `slot` and `range` document in the conformance suite wrote `assignment`
out, and the only document omitting an assignment member was `defaults-omitted`, which is a `ring`
document. A port defaulting `range` to `derived` passed the whole suite and computed a different
candidate ordering in production for any document that omitted the member.

## Decision

The three defaults are stated normatively, and the asymmetry is kept.

`RING-006`, `SLOT-024`, and `RANGE-022` each say what mode an absent member selects: `derived`,
`derived`, and `explicit`. Each says that the document behaves exactly as one that names the mode,
and each forbids inferring the mode from the members the document carries, so a port does not guess
from the presence of `assignments` or of a range's `nodes`.
[`../20-topology-format.md`](../20-topology-format.md) tabulates the three together, where a reader
comparing the kinds meets the asymmetry rather than discovering it.

The asymmetry follows the shape of a document that carries nothing beyond the members its kind
requires. A `ring` strategy requires `kind` alone and a `slot` strategy requires `kind` and
`slotCount`, so neither names an owner and the owners are derived. A `range` strategy requires
`ranges`, and a range is authored with the `nodes` member that names its owners, so the absent
member leaves that authored list in force.

One validation rule was missing for that argument to hold. A `slot` document that meant `explicit`
and omitted the member carries `assignments`, which the derived rule already refuses. A `range`
document that meant `derived` and omitted the member carries `nodes`, which the derived rule already
refuses under the other reading, but a `range` document that meant `explicit` and carried no `nodes`
was accepted and produced an empty candidate ordering for every key.
[`../20-topology-format.md`](../20-topology-format.md) now requires a `nodes` member on every range
under `explicit`, mirroring the `ring` rule that requires a token on every placement-set node of
non-zero weight under `explicit`. The mismatched document is therefore rejected under either mode
rather than routed against the wrong one.

`conformance/vectors/placement/document-defaults.json` gains a case per kind, each pairing a
document that omits the member with one that names the mode the default supplies and asserting that
the two route identically. The invalid-document set gains `range-explicit-missing-nodes`.

## Consequences

An integrator reading the format document sees all three defaults in one table and reads the shape
argument next to it. An integrator reading the specification meets each default inside the section
for the strategy it governs.

Nothing a shipped document computes changes. Every topology in the suite named its mode, and the
two documents the new vector pairs are generated rather than authored, so no expected value moved.

The new validation rule makes a `range` document invalid that was previously valid and routed to no
candidate for every key. That document was already broken in the only way that matters, so the rule
converts a silent failure at every routing call into a rejection at load, which
`TOPO-011` rejects whole and which leaves the snapshot in force unchanged.

Two members named `assignment` with opposite defaults remains a trap, and the repair here is to
state it loudly rather than to remove it. A future format version is free to rename one member or
to make both required; format version 1 cannot, because a document omitting the member is already
valid under the published schema and changing the mode it selects would move data.

## Alternatives

Defaulting both `slot` and `range` to `derived`. Rejected because a `range` document that authors
bounds and owners in one array and omits the member is the common shape, and the change would make
every such document invalid at load. It would also make the required `ranges` member carry an
authored `nodes` list that the strategy ignores, which no other kind does.

Defaulting both to `explicit`. Rejected because a `slot` document requires only `kind` and
`slotCount`, and under `explicit` such a document covers no slot and fails validation, so the
minimal `slot` document would stop being a valid one.

Making the member required under both kinds. Rejected for format version 1 because the schema
publishes it as optional and a document omitting it is already valid, so the change would refuse
documents an authority may already be serving. The reading here records it as the repair a future
format version should take.

Leaving the defaults to the schema annotation. Rejected because an annotation is not an assertion,
because two of the three annotations select opposite modes for one member name, and because the
conformance suite cannot enforce an annotation that no requirement identifier names.
