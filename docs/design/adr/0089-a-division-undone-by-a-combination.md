# 0089. A division undone by a combination

Date: 2026-09-19

Status: accepted

## Context

[`0086`](0086-shard-lineage-derived-from-extent.md) makes a division plannable, and a division that
happens entirely on one node is the part with no precedent in the state machine. A node that holds
the parent under the earlier snapshot and the child under the later one moves nothing to anybody. It
has to divide its own copy so that what it holds matches the extent it now owns.

[`0022`](0022-range-split-lineage.md) had this case and answered it in a way
[`0054`](0054-range-strategy-withdrawal.md) was glad to be rid of. `SPLIT-171` required a handoff
whose local split had already succeeded to move to `failed(undetermined)` without a re-observation,
because the data no longer matched the parent's bounds and could not be put back. That was the
single carve-out in `MOVE-233`, and `0054` recorded its removal as a gain:
[`0051`](0051-recovery-from-an-undetermined-cutover.md) wanted `MOVE-233` unconditional, and with
`SPLIT` withdrawn it became so.

Reinstating the local step reopens the question. If a succeeded division cannot be undone, the
carve-out comes back with it.

## Decision

A division is reversible, and the inverse of a division is a combination.

`LIN-056` requires an aborted local step to be undone by the inverse hook: a division is undone by
`combine`, a fold by `divide`. A local step therefore reaches `aborting` like every other state that
does, `MOVE-421` gains `dividing` beside the three states it already names, and `MOVE-233` keeps the
unconditional form `0051` wanted. There is no carve-out.

The cost lands on the integrator, visibly. `LIN-053` refuses a plan that requires a local step where
`declare` answers `supportsLineage` of false, which is the shape
[`0085`](0085-hook-declarations-and-refused-aborts.md) established: a declaration is the
integrator's statement about their own storage, and the library refuses rather than calling a hook
that was never implemented.

A handoff whose inverse fails and whose attempts are exhausted reaches `failed` with the new kind
`undivided`. The four kinds `MOVE-011` already carries each name a condition of a copy that moved
between nodes, and this one never left the node it is on.

## Consequences

An integrator whose storage can divide a shard but not recombine two adjacent ones declares
`supportsLineage` of false and gets a refused plan rather than a handoff that can only fail. That is
a real restriction, and it is the honest one: a step that cannot be undone is not an abortable step,
and pretending otherwise is what produced `SPLIT-171`.

The state machine gains one state and four transitions. `MOVE-021` says "exactly these transitions
and no others", so this is the largest single edit in the batch, and it lands after the lineage
computation is settled and vector-backed rather than beside it.

A division whose children keep the parent's replica set is a local step alone, with no data crossing
the network, which is `0022`'s own observation and still true.

## Alternatives

A succeeded local step that cannot be undone, as `SPLIT-171` had it. Rejected. It reinstates the
carve-out in `MOVE-233` that `0051` argued against and `0054` removed, and it makes one state in the
machine behave unlike every other.

Requiring no local hook at all, and asking the integrator to divide its copy out of band. Rejected.
The library would have no way to know whether the division had happened, so it could not sequence a
handoff that draws from the divided parent, which `LIN-057` has to order.

Treating a local step as a handoff from the node to itself with the ordinary hook sequence.
Rejected. `prepare`, `transfer`, `catchUp`, `quiesce`, and `commitCutover` all describe two parties,
and a single-winner cutover between a node and itself is meaningless. The shorter path through
`dividing` says what actually happens.
