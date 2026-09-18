# 0051. Recovery from an undetermined cutover

Status: accepted, with the two carve-outs of `MOVE-233` withdrawn by
[`0054`](0054-range-strategy-withdrawal.md) and
[`0056`](0056-advisory-cutover-withdrawal.md). Date: 2026-09-17.

The row order of `MOVE-211` is stated by
[`0076`](0076-ordered-rows-in-a-precedence-table.md), and a fresh quiesce after a resumption at
`cutover` is bounded by [`0074`](0074-quiesce-lease-margin-and-clock-assumption.md).

Re-observation stands as this record decided it. Both exceptions it had to admit are gone with the
surfaces that forced them: `SPLIT-171` excluded a handoff whose local split had succeeded, and
`MOVE-235` refused a resumption at `cutover` under `advisory` hooks. Every handoff that reaches
`failed` with the kind `undetermined` is now admissible, which is what this record wanted.

## Context

Two paths reached `failed` with the kind `undetermined`. `MOVE-021` sent a handoff there when the
outcome of `commitCutover` was not established by `commitDeadlineMillis`, which defaults to 30
seconds. `MOVE-231` sent one there when `observe` was unavailable, or answered `undetermined`, for a
handoff in `cutover` during `recover`.

`MOVE-031` made `failed` terminal and required a new plan to retry the shard, and `MOVE-231` went
further by forbidding any later `cleanup`, `rollback`, or `commitCutover` for the handoff.
`MOVE-211` called `observe` only for handoffs whose state was not terminal. The library therefore
never looked again, including one second later, when the cutover store had recovered and `observe`
would have answered definitively.

What produces that state makes the rule expensive. The cutover store is on the critical path of
every migration under `MOVE-321`. Stores of that kind rarely stop; they slow down. An etcd cluster
under compaction, a throttled partition, or a region with an elevated tail turns a ten millisecond
conditional write into a timeout, across many handoffs at once. A hook author facing a timeout
genuinely does not know whether the write landed, and `undetermined` is the honest answer, so every
concurrent handoff in `cutover` failed together.

The `MOVE-231` path was worse because it was self-correlating. A coordinator restarts because of the
incident that made `observe` unavailable, so one `recover` call during a storage brownout converted
every in-flight cutover into a per-shard operator ticket. Each of those shards was left in an
unresolved ownership state: `MOVE-311` has both nodes refuse writes between `quiesce` succeeding and
`commitCutover` returning, the quiesce lease then expires and the source resumes, and the record may
or may not exist. The only available runbook was an operator reading the cutover store by hand, one
shard at a time.

The state's content is narrower than the rule treated it. `undetermined` says that the library does
not know whether the record was written, which is not the same as knowing it was not.

## Decision

Three changes, which separate what genuinely needs an operator from what needs another look.

An observation that could not be taken is distinguished from one that was taken and did not settle
the question. `observe` answers `ObserveResult`, which is `observed`, `unavailable`, or
`undetermined`. `unavailable` states that the hook could not read the integrator's durable state.
`undetermined` states that it read that state and the state does not establish whether a record
exists. Collapsing the two was the defect: an `observe` that is unreachable for 200 milliseconds was
treated as an answer.

`recover` retries an `unavailable` answer rather than concluding from it. The retry runs under
`maxAttemptsPerStep` with the backoff of `RATE-051`, across successive `recover` calls rather than
inside one, because the coordinator is passive and does not sleep, wait, or spin. `recover` returns
a report naming the handoffs it resolved, the handoffs it could not, the handoffs it failed, and the
backoff after which the integrator calls it again. Only a handoff in `cutover` whose attempts are
spent, or one whose `observe` answered `undetermined`, reaches `failed`. A handoff in any other
state stays where it is and is reported as unresolved, which is safe because every hook is
idempotent.

`failed` with the kind `undetermined` is re-enterable by one explicit call. `reobserve(id, clock)`
calls `observe` once, applies the mapping of `MOVE-211`, and returns. It is the single exception to
`MOVE-031`, it is made by the integrator for one named handoff, and no transition out of a terminal
state is automatic. It is the call an operator wants at three in the morning, and it costs nothing
when it is not used.

Preventing a second cutover is what fixes the shape of the call. `reobserve` calls `observe` and no
other hook, so no commit happens inside it; it assigns a state and returns, and the next `step`
calls the hook. A resumption at `cutover` follows an observation that reports no record, and
`MOVE-331` already requires a fresh successful `quiesce` before the next `commitCutover`, so no
commit runs against the expired lease. Where the hooks declare `cutoverGuarantee` of `advisory`, a
resumption at `cutover` is refused outright, because an advisory store cannot establish that no
record was written and `MOVE-161` is not guaranteed over it; such a handoff stays failed for an
operator. The other four mappings still apply under `advisory`, so a deployment on a weaker store
still recovers every case where the store named a record.

The rule that stops a handoff destroying the only surviving copy is untouched. A handoff resumed at
`verifying` reaches `cleanup` only after `verify` returns `matched`, which is `MOVE-181` without
variation, and a handoff resumed at `aborting` is admitted only where the observation that produced
the resumption established that no cutover record belongs to it, which is `MOVE-191`.

The three other failure kinds are not re-enterable. `unverified` names a destination copy that did
not match the source, `residue` names a source copy left in place after ownership moved correctly,
and `rollbackFailed` names a compensation that did not run. Each is a duty the library cannot
discharge and a further observation cannot change. `undetermined` alone names an outcome the library
did not establish, which a later observation may establish. A handoff sent to `failed` by
`SPLIT-171`, after a local split whose parent shard identity no longer names the data, is refused
for the same reason even though it carries that kind.

## Consequences

A storage brownout costs a `recover` call after a backoff rather than a per-shard runbook. The
common case, a store that is slow rather than down, no longer reaches `failed` at all, because
`recover` retries it.

`observe` gains a result type, which every hook implementation has to produce. An implementation
that has only a boolean for reachability maps its failure to `unavailable` and its uncertainty to
`undetermined`, and one that cannot tell them apart answers `undetermined`, which is the old
behaviour.

`recover` returns a report rather than a unit, so an integrator reads an outcome instead of assuming
one. A plan with unresolved handoffs is a state the integrator now has to handle, and the handling
is to call `recover` again after `retryAfterMillis`.

An operator has one call where there was none. `reobserve` resolves a handoff or reports that the
store is still silent, and repeating it is free because a call that resolves nothing changes
nothing.

The `advisory` refusal means a deployment on a store without a serialised write still has a manual
case. That is the guarantee level it declared under ADR 0018, stated rather than quietly widened.

## Alternatives

Retrying `observe` automatically out of `failed`, with no operator call. Rejected because it is an
automatic transition out of a terminal state, which makes `failed` no longer terminal, and because
a handoff that resolves itself hours later surprises an operator who has already acted on it.

Adding a fifth failure kind for the split case of `SPLIT-171`. Rejected because `MOVE-011` and
`ERR-052` close the set at four kinds and each of the four carries a distinct operator response,
and because the case is excluded by one sentence in the admissibility rule instead.

Having `reobserve` call `commitCutover` where the observation reports no record. Rejected because
the quiesce lease has expired by then, `MOVE-331` forbids a commit against an expired lease, and a
call that quiesced and committed inside one operator-driven call would be the one place the library
moved authority without a `step`.

Making `recover` block until `observe` answers. Rejected because the thread belongs to the
integrator under `MOVE-051` and `RATE-111`, and because a blocking `recover` during a brownout is
the coordinator holding the integrator's thread for the length of an incident.

Treating an `unavailable` observation as evidence that no record exists, and rolling back. Rejected
because it is the one inference that can destroy a committed cutover, and because `MOVE-201` makes
the integrator's durable state authoritative precisely where the two views disagree.
