# 0085. Hook declarations and refused aborts

Date: 2026-09-19

Status: accepted

## Context

Writing the public migration surface of the Java port turned two sentences of the specification into
code that had to answer questions the specification does not.

The first is `supportsRollback`. `MOVE-111` declares it in `HookDeclaration` beside
`supportsVerify`, and `MOVE-181` spells out what `supportsVerify` of false means: `cleanup` may
follow a committed cutover record directly, because there is no verification to wait for. No
requirement reads `supportsRollback` at all. `MOVE-421` states that an abort in `preparing`,
`transferring`, or `catchingUp` moves the handoff to `aborting` and calls `rollback`, with no
carve-out, so a coordinator that took `MOVE-421` literally would call a hook the integrator declared
it does not implement, and a coordinator that took the declaration literally would leave the handoff
in `aborting` for ever.

The second is a refused abort. `MOVE-431` requires the coordinator to call `observe` before
admitting an abort in `cutover` and to refuse the abort where the answer is `unavailable` or
`undetermined` or where a record belonging to the handoff exists. `MOVE-441` forbids admitting an
abort in `verifying`, `cleanup`, or a terminal state at all. Neither names the condition the refusal
reports, and the closed set of `ERR-010` carries none that fits: `planRefused` is closed over the
six causes of `ERR-050`, every one of which is a property of a plan rather than of one abort, and
the recipient and migration blocks name nothing else.

Both were found by a review of the port rather than by the conformance suite, because the suite
drives the handoff machine by trigger name and never calls a hook.

## Decision

A hook implementation that declares `supportsRollback` of false waives compensation: the handoff
leaves `aborting` for `aborted` with no hook called, exactly as `MOVE-411` leaves a handoff aborted
from `planned` with no hook called. The declaration is read as the integrator's statement that there
is nothing to release.

A refused abort raises `invalidArgument` carrying the requirement that refused it. The caller named
a handoff whose state admits no abort, or asked for an abort the durable state does not permit, and
in both cases the call is a caller error rather than a property of the plan.

Both readings are recorded here rather than in the specification, because changing the specification
changes the suite, and neither reading is settled by a requirement today. The next specification
revision states them: `MOVE-421` gains the sentence `MOVE-181` already has, and the abort refusal
gains a condition or a cause. `OQ` carries neither, so this record is where they are tracked.

## Consequences

The port is implementable without guessing, and the guess it does make is written down where a
reader of the port finds it.

A port that reads `MOVE-421` literally and calls `rollback` whatever the declaration says is
conforming today, and so is this one. That is what an unstated rule costs: two conforming ports
answer differently, and the suite cannot tell them apart because no vector reaches a hook. The
divergence is bounded to the abort path of hooks that declare no rollback, which is a deployment
that has told the library it has nothing to compensate.

An integrator catching `invalidArgument` from `abort` cannot distinguish a refused abort from a
malformed one without reading the message. The message names the requirement, which is enough for
an operator and not enough for a program, and a condition of its own is what the next revision
settles.

## Alternatives

Calling `rollback` whatever the declaration says. Rejected because `MOVE-111` lets an integrator
declare the hook unimplemented and the library would then call it, which is the one thing a
declaration exists to prevent. An implementation that answers a permanent failure from an
unimplemented `rollback` leaves the handoff in `failed` with the kind `rollbackFailed`, which
reports a compensation failure where there was nothing to compensate.

Leaving a handoff in `aborting` where no rollback is supported. Rejected because `aborting` is not
terminal, so the handoff would sit in the plan's summary for ever and `MOVE-011` would never name a
kind for it.

Raising `planRefused` for a refused abort. Rejected because `ERR-050` closes its cause set over
plan properties, and reporting one of those six for an abort refusal names a cause that did not
hold. A wrong cause is worse than a general condition, because a caller switching on the cause acts
on it.

Adding a leaf to `ERR-010` for the refusal. Rejected here rather than on the merits: `ERR-062`
closes the leaf set at sixteen and forbids a binding adding one of its own, so the leaf is the
specification's to add and not this port's.
