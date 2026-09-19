# 0090. No rebase across a lineage boundary

Date: 2026-09-19

Status: accepted

## Context

`MOVE-096` makes a handoff rebasable onto a newer snapshot exactly when that snapshot enumerates the
handoff's shard, its source, and its destination.
[`0050`](0050-plan-rebase-onto-a-newer-snapshot.md) settled that rule before a lineage existed, when
the only way a shard could leave a snapshot was for the authority to stop naming it.

With [`0086`](0086-shard-lineage-derived-from-extent.md) a shard can leave a snapshot because its
extent was divided again. A handoff moving contents into a shard that a third epoch has since
divided has a shard the newer snapshot does not enumerate, so `MOVE-096` drops it.

The question is whether the rebase should instead follow the lineage: recognise that the shard's
extent still exists under new names, and rewrite the handoff onto the children.

## Decision

It should not. A handoff whose shard the newer snapshot does not enumerate is aborted and
compensated, exactly as `MOVE-096` already has it. No `MOVE-09*` requirement changes.

## Consequences

A split overtaken mid-flight by a further split of the same region costs the work already done. The
destination's partial copy is released by `rollback` and the newer plan starts the move again, which
is correct but not free.

The behaviour is what the port already does, so nothing in the coordinator changes and no scenario
moves.

Relaxing a refusal later is cheap and withdrawing a clever rebase that proved wrong is not. A
transitive rebase would have to decide which child inherits a partially transferred copy, whether a
quiesce lease taken against the parent still binds the children, and what a cutover record naming
the parent means once the parent is gone. None of those has an obvious answer, and getting one wrong
loses data rather than time.

## Alternatives

Rewriting a handoff onto the children of its shard. Rejected above: it is a larger change than it
looks, it is not reversible once shipped, and the case it optimises is an authority publishing two
divisions of one region in quick succession, which is rare and already correct if slower.

Refusing the rebase outright rather than aborting the handoff. Rejected. `MOVE-096` classifies each
handoff independently and a plan may contain many, so refusing the whole rebase because one handoff
cannot be carried would abandon the ones that can.
