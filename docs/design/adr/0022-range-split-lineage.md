# 0022. Range split lineage

Status: withdrawn by [`0054`](0054-range-strategy-withdrawal.md). Date: 2026-09-16.

The whole `SPLIT` prefix is withdrawn with the `range` strategy, which was the only kind under which
`SPLIT-001` admitted a split or a merge. The lineage classification, the two-epoch representation,
the decomposition into local steps, and the `splitLocal` and `mergeLocal` hooks leave the design
with it. The reasoning below is what a later minor version reads before adding the kind back.

Amended on 2026-09-19 by [`0086`](0086-shard-lineage-derived-from-extent.md). The lineage returns in
a different shape, under the `LIN` prefix and fresh identifiers, derived from each strategy's own
keyspace geometry rather than from authored range bounds. The `SPLIT` prefix stays withdrawn and
admits no further identifier. What this record got right and `0086` keeps: a lineage relates extents
rather than identifiers, a change in flight is two epochs and nothing else, an unaligned boundary
move is refused and republished as a division epoch followed by a fold epoch, and explicit lineage
members do not belong in the document. What `0086` does not take: the `range` strategy, the authored
bounds, and the rule that a division which has already succeeded cannot be undone.

## Context

The `range` strategy names shards by `shardId` and bounds them by a half-open interval. A split
replaces one range with two whose bounds meet at the split point, and a merge is the reverse. Both
are ordinary edits to a topology document, and both change the set of shard identifiers between one
epoch and the next.

The ownership delta compares the replica sets of shards across two snapshots, which needs a
correspondence between the shards of the old snapshot and the shards of the new one. After a split
that correspondence does not exist by name. An authority might name the children of `r3` as `r3a`
and `r3b`, or as `r7` and `r8`, or keep `r3` for the lower child and invent one name. The library
has no basis for preferring any of those conventions, and a topology written by hand will not follow
whichever one it picked.

A shard map also has to represent a split while it is happening, and in-flight requests routed
against the parent have to arrive somewhere sensible.

## Decision

Lineage is derived from bounds and never from `shardId`. A target shard is a child of a source shard
when the target's interval is contained in the source's, comparing bounds by the unsigned bytewise
ordering already fixed for `range`, with a null start below every key and a null end above every
one. A source shard is a parent of a target shard under the same condition.

Each source shard is then classified against the target snapshot as `unchanged`, `split`, `merged`,
or `unaligned`. The first three are plannable. `unaligned`, which is a boundary that moved without
being a clean split or a clean merge, is refused, and the specification says what an authority does
instead: publish the change as a split epoch followed by a merge epoch, each of which is plannable
on its own.

A split in flight is represented by two epochs and by nothing else. The source epoch names the
parent and the target epoch names the children, and there is no epoch in which both are addressable.
Both documents independently satisfy the contiguity and coverage rules, so a partially applied split
is not a document the library loads.

The handoff state machine handles the split by decomposition. A `split` becomes one local split step
followed by one handoff per child whose replica set differs from the parent's, with the local step
ordered first. A `merged` becomes one handoff per parent that has to move, with the local merge step
ordered last. The local steps are two further movement hooks, `splitLocal` and `mergeLocal`, each
idempotent, each retryable, and each sequenced only onto a node that already holds every shard it
names. A split whose children keep the parent's replica set is a local step alone.

In-flight requests need no special rule, because the recipient-side check evaluates ownership over
the routing key rather than over the shard identifier. A request routed against a parent arrives at
a node that has moved to the children; if that node owns the child covering the key it is the owner,
and if it does not the refusal names the child's owner. The key is the thing that survives a split,
so the key is what the check reads.

The decision to split belongs to the authority. The library accepts integer shard measurements from
the integrator, emits advice when a configured threshold is crossed, marks advice as not addressable
by a split where a single key dominates the traffic, and edits nothing.

## Consequences

An authority may name children however it likes, including reusing the parent's identifier for one
child, and the library still computes the correct delta. Nothing in the format has to change, so no
new member and no new version is needed for splits.

A boundary move that is neither a split nor a merge costs two epochs. That is a real restriction on
an authority that wants to slide a boundary in one edit, and it buys an unambiguous classification
in which every plannable case has one interpretation.

Deriving lineage from bounds is exact under the ordering already specified, so it is a conformance
vector over pairs of documents rather than a behaviour that has to be observed in a running cluster.

Splits are available under `range` and nowhere else. Under `slot` the shard count is fixed by
`slotCount`, and under `ring` a token addition divides a token range as a consequence of placement,
which the ownership delta already describes without a split step. Under `rendezvous` there are no
shards to split.

A local split that has already succeeded cannot be rolled back, because the data no longer matches
the shard identity of the source snapshot. The specification sends the remaining handoffs of such a
plan to `failed` with the kind `undetermined` rather than attempting a compensation that would have
to reconstruct the parent.

## Alternatives

Explicit lineage members in the document, such as `parentShardId` or `splitOf`. Rejected because
they duplicate information the bounds already carry, because a hand-edited document would omit them,
and because two members that can disagree need a rule for which one wins.

Matching shards by identifier and treating a renamed shard as a removal plus an addition. Rejected
because it reports a whole-shard move where the data did not move at all, which inflates the delta
past the minimal movement bound and schedules copies that are not needed.

Representing a split in flight inside one epoch, with the parent and its children both present.
Rejected because a routing call would have two shards covering the same key, which breaks the
glossary's statement that every key belongs to exactly one shard under a given topology.

Accepting an `unaligned` boundary move by decomposing it internally into a merge and a split.
Rejected because the decomposition is not unique, because the intermediate shard it implies is
addressable by nothing, and because an authority that wanted that sequence can publish it.

Letting the library choose split points from its own measurements. Rejected because the library does
not author topology documents and does not assign epochs, and because a split point derived from a
sampled key distribution is a control plane's decision rather than a routing library's.
