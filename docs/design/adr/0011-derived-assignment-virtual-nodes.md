# 0011. Derived assignment virtual nodes

Status: withdrawn by [`0054`](0054-range-strategy-withdrawal.md) and
[`0055`](0055-slot-derived-assignment-withdrawal.md). Date: 2026-09-16.

Both configurations this record fixes the virtual node count for are withdrawn: the `range` kind
entirely, and derived assignment under `slot`. `PLACE-050` no longer carries the two hardcoded rows,
so every derived count in the design is configurable from the strategy object that governs it. The
Context below is why the record was written, and it is also why the code path went: it records that
the fields were omitted because the path had no known demand.

## Context

The `slot` and `range` strategies with `derived` assignment order the placement set by a rendezvous
score over the shard identifier. Rendezvous placement honours weight by giving a node of weight `w`
a count of scoring slots, and that count comes from `virtualNodesPerWeightUnit` and
`maxVirtualNodesPerNode`. Neither field exists on the `slot` or `range` strategy objects, so the
count is undefined unless this record fixes it.

Adding the two fields to both strategy objects would make the format wider for a code path with no
known demand, and would give an operator two more numbers to get wrong.

## Decision

Under `slot` with `assignment` of `derived` and under `range` with `assignment` of `derived`, a node
of weight `w` in the placement set has a virtual node count of `min(w, 1024)`. The multiplier is
fixed at one virtual node per weight unit and the cap is fixed at 1024, which are the defaults of
the `rendezvous` strategy. Neither is configurable.

## Consequences

An operator who wants finer weight ratios under derived assignment expresses them in larger integer
weights rather than in a multiplier, and pays the corresponding hash cost. A cluster of one standard
and one double node uses weights 1 and 2 and performs three hashes per shard.

The cost is per shard rather than per key. A `slot` topology precomputes the ordering for every slot
once per snapshot, so the total work at publication is `slotCount` times the summed virtual node
count, and a routing call performs one hash and one modular reduction. A `range` topology does the
same per range.

A cluster with a node of weight 1 and a node of weight 100000 sees the larger node clamped to 1024,
and the clamping event names both values. The resulting ratio is 1024 to 1 rather than 100000 to 1.

## Alternatives

Adding `virtualNodesPerWeightUnit` and `maxVirtualNodesPerNode` to the `slot` and `range` strategy
objects. Rejected for format version 1 because the fields would be present on three of five kinds
with subtly different cost models, and because the precomputed nature of derived slot and range
ordering makes the multiplier a load-time cost rather than a routing cost, which is the pressure
that makes the field useful under `rendezvous`.

Ignoring weight entirely under derived assignment. Rejected because a heterogeneous cluster is the
normal case, and a strategy that silently ignores a weight an operator set is a defect report.

Reading the multiplier from the `rendezvous` strategy object. Rejected because a document declares
exactly one `strategy` object and it is not of kind `rendezvous`.
