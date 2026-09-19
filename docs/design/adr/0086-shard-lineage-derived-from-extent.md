# 0086. Shard lineage derived from extent

Date: 2026-09-19

Status: accepted

## Context

[`0054`](0054-range-strategy-withdrawal.md) withdrew the `range` strategy and with it the whole
`SPLIT` prefix, on the ground that `SPLIT-001` admitted a split only under `range`. One sentence of
its Consequences does not hold. It reads that a token addition under `ring` divides a token range as
a consequence of placement, which `SPLIT-001` already distinguished from a split. The distinction is
real about who decides and false about what a coordinator has to do. A division that is a
consequence of placement still moves contents from one extent into another, and the machinery that
named the parent went out with the prefix.

What that costs is visible in the port. Two `ring` snapshots at `factor` 2, with nodes `a` at tokens
`...1000` and `...5000` and `b` at `...3000` and `...7000`, gain a node `c` at the single token
`...2000`. The later snapshot enumerates a shard `...2000` that the earlier one does not, and the
contents of that shard are held by the replicas of `...3000` under the earlier snapshot. The
ownership delta reports that `...2000` appeared and that two nodes gained it. It cannot report where
the contents are, because `...3000` did not change owner and therefore has no delta entry at all.
The plan the port builds from that delta emits two handoffs whose source and destination are the
same node, and names neither node that holds the data.

The reverse case is worse for being quieter. Removing `c` folds `...2000` back into `...3000`, whose
replica set is unchanged, so the shard that must receive the contents appears in no delta entry and
receives no handoff.

This is reachable whenever a node joins or leaves a `ring` topology, which is the central operation
of the storage walkthrough in [`../00-overview.md`](../00-overview.md). It is not a missing feature
so much as a hole that split and merge are the names for.

`TOPO-211` answers which shards changed owner. Nothing in the design answers which shards changed
contents, and the two questions have different answers exactly when an epoch changes which shards
exist.

## Decision

A **lineage** is added: the correspondence between the extents of two snapshots, where the extent of
a shard is the set of routing keys `shardOf` maps to it. Lineage relates extents and never
identifiers, which is [`0022`](0022-range-split-lineage.md)'s central insight carried forward
without the machinery that was specific to `range`.

The lineage is derived from the topology document. No document member records it, no new strategy
kind is introduced, `formatVersion` stays at 1.0, and the JSON Schema is untouched. `0022`'s
Alternatives section rejected explicit lineage members on three grounds that all still hold: they
duplicate what the bounds, or here the tokens and the matchers, already carry; a hand-edited
document would omit them; and two members that can disagree need a rule for which one wins.

The new requirements take the `LIN` prefix, in the Topology change and rebalancing section, on the
`migration` surface. `SPLIT` stays withdrawn and admits no further identifier, under
[`0053`](0053-requirement-withdrawal-convention.md).

Lineage sits beside the ownership delta rather than inside it. `TOPO-211`, `TOPO-213`, and the
`ShardChange` shape are unchanged, and no `TOPO-*` requirement moves surface.

## Consequences

An epoch that divides or folds an extent is now plannable. A handoff for a divided extent names a
source drawn from the parent's replica set, and a fold produces a handoff for the shard that
receives the contents even where its replica set did not change, which `LIN-044` states because a
plan built over the delta alone omits it.

The property the whole change rests on is that where two snapshots enumerate the same shard set the
lineage is the identity. Under every strategy the shard identifier of `PLACE-031` renders the
geometry that fixes the extent, so an equal shard set is an equal extent set. Every pair the suite
carried before this change is such a pair, so the regenerated tree is additions only, and a changed
expected value anywhere would mean the identity case had leaked.

Lineage is kept out of the `routing` surface deliberately. Adding a parent to `ShardChange` would
put extent arithmetic at the `core` level, which [`../30-conformance.md`](../30-conformance.md)
says a port cannot decline, and would oblige every future port to implement ring interval
containment and directory precedence before declaring anything at all, for behaviour an integrator
routing tenant identifiers never uses.

`range` does not return. Nothing here reinstates an ordered keyspace, a scan, or the `RANGE` prefix,
and a further strategy kind remains the minor format version that
[`../99-roadmap.md`](../99-roadmap.md) prices.

## Alternatives

Reinstating a range-like ordered strategy. Rejected. It costs a minor format version, a fifth entry
in a closed set, a fresh prefix of some forty identifiers, and the `assignment` default trap
[`0043`](0043-assignment-mode-defaults.md) chose to state loudly rather than remove, and at the end
of it split would be available only under the one kind that none of the three validated use cases
reaches. The `ring` hole would still be open.

Making `slot` splittable by doubling `slotCount`. Rejected, and recorded here because the arithmetic
is more encouraging than the conclusion and should not be rediscovered. Writing a key hash as
`kn + i`, its remainder modulo `2n` is `i` where `k` is even and `i + n` where `k` is odd, so
doubling sends every key of slot `i` to slot `i` or slot `i + n` and nowhere else, for any `n`, with
no power-of-two requirement and no bitmask. A doubling is therefore a clean binary refinement of
every slot at once and a halving is its fold. It is still not worth building: under `slot` the
extent of a shard never changes while `slotCount` is held equal by `TOPO-231`, the delta and the
plan already work, and doubling addresses only a `slotCount` chosen too small, which an authority
avoids by choosing generously.

A lineage layer above the strategies, with parent and child recorded per shard. Rejected for
`0022`'s reasons, restated above.

Deriving a lineage by sampling routing keys and observing which shard each falls in. Rejected. It is
unsound at any sample size, and it would make a lineage depend on something outside the two
documents, which `LIN-005` forbids for the same reason `TOPO-221` forbids it of the delta.
