# 0050. Plan rebase onto a newer snapshot

Status: accepted. Date: 2026-09-17.

## Context

A migration plan was pinned to the pair of snapshots it was built from. `MOVE-091` superseded the
whole plan on any installed epoch that was neither the source nor the target, and supersession
aborted every handoff short of `cutover`, discarding the partial copies those handoffs had
accumulated.

That rule does not survive the fleet it was written for. A rebalance of 10000 shards under the
`CFG-050` defaults, with `maxConcurrentHandoffs` of 4 and per-node bounds of 1, runs for a day even
when an operator raises the concurrency. A fleet of 1000 nodes publishes an epoch several times a
day for entirely ordinary reasons: a node dies and is marked `leaving`, a node is drained, an
operator adjusts a weight. Each of those epochs aborted every pre-cutover handoff and forced the
integrator to build a new plan and restart the bulk transfers. The rebalance never converged, and
the failure grew worse with the fleet, because a larger fleet publishes epochs more often and takes
longer to rebalance. The only fleet that needs an orchestrated migration is the one on which the
rule made one impossible.

Every comparable system reconciles against the current view rather than against a version pair.
Cassandra, Dynamo, and Ceph all move data toward the membership in force and none of them discards a
migration because a third version arrived. ADR 0018 and ADR 0019 reason carefully about two owners
and about coordinator death, and neither considers a topology that simply keeps moving, which is the
normal state of a large fleet.

## Decision

Supersession is per handoff rather than per plan, and the unit that follows the topology is the
handoff and not the plan.

A handoff is rebasable onto a newer snapshot exactly when that snapshot enumerates its shard, its
destination is a member of that shard's replica set under the snapshot, and its source is not. The
triple the plan was built from still holds, so the copy already accumulated at the destination is
still the copy the newer topology wants. Rebasing advances the handoff's own target epoch and
changes nothing else: not its state, not its attempt count, not its budget, and no hook is called.

Only the states short of `cutover` rebase, that is `planned`, `preparing`, `transferring`, and
`catchingUp`. That is the same line `MOVE-431` already draws for an abort, and it is drawn there for
the same reason. In those four states no cutover record can exist for the handoff, so nothing has
moved authority and nothing is at risk from a change of target epoch. From `cutover` onward a record
either exists or may be being written, and the epoch in the handoff's context is the epoch that
record carries. Changing it mid-flight would break the precondition `MOVE-161` states for
`commitCutover` being idempotent in outcome, which is exactly how a second, different record gets
committed. A handoff at `cutover` or beyond therefore keeps the epoch it entered `cutover` with and
runs to a terminal state under it, which is what the rule already did.

A handoff whose triple no longer holds is aborted exactly as a supersession aborted it: free in
`planned`, compensated through `rollback` in the other three. `MOVE-481` already requires that
compensation to run under a higher epoch, because the higher epoch changes where traffic goes and
does not release the destination's partial copy.

The work is split across two calls. `onSnapshotInstalled` performs three comparisons, the
`topologyId`, the epoch, and the comparability rule of `TOPO-231`, and does nothing else. Where the
snapshot is comparable and above the plan's target it marks the plan rebase pending and evaluates no
preference list, which keeps the cost of an install where `TOPO-212` and ADR 0049 put it. The
integrator then calls `rebase`, which performs one replica set evaluation per pre-cutover handoff
and returns a report. An incomparable snapshot, or one under another identifier, supersedes the plan
as before, because no correspondence between the two shard spaces exists.

Between the mark and the rebase the plan is held short of new work. `MOVE-093` admits no handoff out
of `planned` and none from `catchingUp` into `cutover`, and every other transition stays available
so that a handoff already at `cutover` completes. That is the asymmetry ADR 0021 chose for
backpressure, for the same reason: withholding the finish of work already begun strands shards in
the window where two nodes hold a copy. The interlock is what makes the split into two calls safe
rather than merely cheap. An integrator who never rebases gets a plan that stalls and says so,
rather than one that cuts a shard over to a node the current topology does not route to.

Recovery reads an interval rather than a single epoch. A plan's rebase interval is the epochs above
its source epoch and at or below its target epoch, and a cutover record belongs to a handoff when
its owner is the handoff's destination and its epoch lies in that interval. `MOVE-211` and
`MOVE-191` read the interval, so a record committed before a rebase is still this plan's record
after one. The interval is derived from the plan's two epochs and needs no durable chain: a
coordinator that restarts rebuilds from its source snapshot and the latest snapshot it was rebased
onto, which is the pair it already had to persist, and `MOVE-212` has a handoff resumed at
`verifying` adopt the epoch of the record it observed.

A rebase moves no authority. It commits no cutover record, reverses none, and changes no fencing
token. A token is the pair of `topologyId` and `epoch` from the snapshot that produced a routing
decision, and `MOVE-041` and `MOVE-281` already state that no epoch decides authority for a shard
under handoff. Rebasing changes which epoch a handoff is heading for and changes nothing a recipient
compares.

## Consequences

A rebalance finishes on a fleet whose topology keeps moving, which is the only fleet that needs one.
The bulk transfers survive an unrelated epoch, and the cost of an epoch that touches nothing the
plan is moving is one comparison per install and one replica set evaluation per handoff at the
rebase.

An integrator has one more call to make. A loop that drives `step` now also handles a `rebase`
pending mark, which is a `rebase` call and a report. The alternative was for the library to rebase
inside `onSnapshotInstalled`, which would put a per-shard placement cost on a call an install makes
and would contradict the decision ADR 0049 records.

A plan's target epoch and a handoff's target epoch are distinct after a rebase. That is a second
epoch for an implementation to carry and for an operator to read, and it is what lets a handoff at
`cutover` finish under the epoch its record names while the rest of the plan moves on.

The rebase predicate is narrow. A handoff whose destination stayed and whose source changed, or
whose shard was split, is aborted rather than reinterpreted. Widening it would require the library
to decide that a differently shaped move is the same move, which it has no basis for.

`plan-superseded-by-new-epoch.json` changes, because the epoch it installs is now comparable and
marks the plan rather than superseding it. The scenario keeps its supersession coverage by
installing a foreign identifier afterwards.

## Alternatives

Leaving `MOVE-091` as written and stating in `99-roadmap.md` that orchestrated migration supports
only fleets that can freeze topology changes for the duration of a rebalance. Rejected because that
is an admission that the flagship storage use case does not work at the size that needs it, and
because the repair is free while the surface is unimplemented.

Rebasing inside `onSnapshotInstalled`, with no separate call. Rejected because it evaluates a
preference list per handoff on a call that follows every install, which is the cost ADR 0049 moved
out of the install path, and because a plan mutating as a side effect of a topology arriving is what
`MOVE-101` and `RATE-001` exist to prevent.

Rebasing a handoff in `cutover` by advancing the epoch in its context. Rejected because the context
is the idempotence key for `commitCutover` under `MOVE-161`, so a second call under a changed
context is a second, different record, which is the double cutover the whole design exists to
exclude.

Recomputing the plan's whole delta against the newer snapshot and reconciling it with the handoffs
in flight. Rejected because the reconciliation has no unique answer where a shard's move changed
shape, and because it costs a full delta per epoch rather than one evaluation per handoff in flight.

Carrying a durable chain of every epoch the plan has been rebased through, as a member of the
recovery input. Rejected because the interval of `MOVE-102` recovers the same answer from the two
epochs a restart already has, and because a second durable record that can disagree with the data
plane is the problem ADR 0019 rejected a journal to avoid.

Letting a rebase-pending plan continue unchecked until the integrator rebases it. Rejected because a
handoff would cut a shard over to a destination the current topology no longer routes to, and the
data would sit on a node nothing reaches.
