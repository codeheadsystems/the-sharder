# 0019. Handoff coordination and recovery

Status: accepted, with the clock discipline extended by
[`0074`](0074-quiesce-lease-margin-and-clock-assumption.md). Date: 2026-09-16.

The passive coordinator, the eleven states, and the recovery through `observe` are unchanged.
`MOVE-063` states that the clock the integrator supplies to `step` is one monotonic source
across the calls of one plan, because the quiesce lease and a `deferred` result are both
measured from an instant one call records and a later call reads.

## Context

A handoff is a sequence of long-running steps against an integrator's data plane. Something has to
decide which step runs next, retry the ones that fail, and know what has already happened after a
restart. Calling that thing a coordinator invites it to own a thread pool, a scheduler, and a
durable log, none of which a library that starts no thread and owns no storage can have.

The specification also has to say what happens when the process holding the coordinator dies halfway
through a thousand-shard rebalance, because that is the case that decides whether the design is
usable.

## Decision

The coordinator is a passive state machine. It holds the plan, the state of each handoff, and the
rate accounting, and it does nothing until the integrator calls `step`. Each `step` advances at most
one handoff by at most one hook call, and returns an outcome describing what it did. The integrator
supplies the threads, the scheduling, and the monotonic clock. A plan is never created or advanced
as a side effect of accepting a topology.

The state machine has eleven states. Eight are the provisional names from the overview, with
`catching up` spelled `catchingUp` so that it is an identifier. Three are new: `complete` as the
terminal success state, `aborting` as the state in which compensation runs, and `failed` as the
terminal state that requires an operator. `failed` carries one of four kinds, `unverified`,
`residue`, `undetermined`, and `rollbackFailed`, because the four need different operator responses
and collapsing them into `aborted` would tell an operator that nothing happened when something did.

Durability lives with the integrator, not with the coordinator. Every hook is idempotent, plan
construction is a pure function of the two snapshots and the policy, and a hook named `observe`
reports what the data plane durably knows: whether a cutover record exists, whether the destination
was prepared, whether the source was quiesced, and whether residue remains at the source. A restart
therefore rebuilds the plan from the same two snapshots, calls `recover`, and reads each handoff's
state back out of the data plane. The coordinator's own view is never the authority when the two
disagree.

Two orderings are hard requirements rather than conventions. `cleanup` never runs before `verify`
succeeds, which is what stops a handoff destroying the only surviving copy. `rollback` never runs
after a cutover record exists, which is what stops a compensation undoing a committed change of
ownership.

Abort admissibility follows from those orderings. An abort is free in `planned`, is compensated in
`preparing`, `transferring`, and `catchingUp` because the source is untouched throughout, and is
admitted in `cutover` only while `observe` reports no record. From `verifying` onwards ownership has
moved, so returning it is a new topology epoch above the target epoch, never an abort and never a
decrement.

## Consequences

An integrator that wants a background rebalance writes the loop, which is a handful of lines around
`step`, and an integrator that wants none pays nothing. The library remains free of a scheduler.

Recovery costs one `observe` call per non-terminal handoff and no durable state of the library's
own. The price is that `observe` is mandatory in practice: an integrator who implements it as a stub
gets a coordinator that cannot recover, and a handoff interrupted in `cutover` lands in `failed`
with the kind `undetermined` rather than guessing.

A `failed` handoff is never retried by the plan that produced it. Retrying is a new plan, which
forces the integrator to re-read the topology rather than to loop on a step that has already decided
it cannot proceed.

Concurrency is expressed as a contract on `step` rather than as a primitive, so a binding maps it to
whatever its language offers. The cost is that the specification cannot say how a port achieves it,
only what it must achieve.

## Alternatives

An active coordinator owning a thread pool. Rejected because `CORE-060` forbids the library
starting a thread it was not given, and because the scheduling policy that suits a storage cluster
suits nothing else.

A durable journal SPI owned by the library, written before and after each step. Rejected because two
durable records, the journal and the data plane's own state, can disagree, and reconciling them is a
second consistency problem. Reading the data plane through `observe` has one source of truth.

Collapsing `complete`, `aborted`, and `failed` into a single terminal state with a result code.
Rejected because the state is what an operator reads first, and because the transition table becomes
untestable when every edge ends at the same node.

Treating cutover as an instantaneous transition with no state of its own. Rejected because quiesce,
the grace window, and the commit all occur inside it, and a coordinator that dies between them has
to be resumable at a named point.

Making abort available from `verifying` by reversing the cutover record. Rejected because the record
is single-winner and durable, so reversing it reintroduces exactly the ambiguity it exists to
remove.
