# 0057. Step budget adjustment withdrawal

Status: accepted. Date: 2026-09-17.

## Context

[`0021`](0021-migration-backpressure-control.md) decided that the migration step budget is a count
in the integrator's own unit, declared by the hooks as an opaque string, and that the library passes
it to `transfer` and `catchUp` without interpreting it. `RATE-031` states that obligation and
`MOVE-141` repeats it for `budgetUnit`, `unitsMoved`, `bulkRemaining`, `residue`, and the opaque
member of a cutover record.

The same record then ran a control loop over that number. `RATE-041` required additive increase by
`budgetIncrement` up to `maxStepBudget` after a successful step, and multiplicative decrease by
unsigned halving down to `minStepBudget` after a deferral or under any pressure level above `none`.
Four of the migration policy's members existed to configure it, and one clause of `CFG-051`
explained the resulting behaviour as a migration that starts slowly and finds its rate.

Halving a quantity whose meaning the specification declines to know is arithmetic on an
uninterpreted unit. The integrator, who does know the unit, already holds two levers that work
without it: `deferred(retryAfterMillis)` from any hook throttles that handoff, and the pressure
gauge of `RATE-061` withholds admission at `soft` and withholds the bulk hooks at `hard`. Neither
depends on the budget's meaning.

The loop also put a hidden variable into a state machine that otherwise has none. A handoff's
budget depended on the whole history of its step outcomes, so `MOVE-097` had to say that a rebase
leaves it unchanged, and `migration.step_budget` existed as a gauge to make it observable.

## Decision

The additive-increase, multiplicative-decrease loop leaves the design. `RATE-041` is withdrawn under
the convention of [`0053`](0053-requirement-withdrawal-convention.md).

`minStepBudget`, `maxStepBudget`, and `budgetIncrement` leave the migration policy of `RATE-011`
and `CFG-050`. `RATE-021` refuses a policy whose `initialStepBudget` is zero, which is the floor
`minStepBudget` used to carry, and keeps its refusal of a
`reTransferResidualThreshold` at or below `catchUpResidualThreshold`.

`RATE-031` is amended rather than withdrawn. The budget an implementation passes is
`initialStepBudget`, passed unmodified to every step of every handoff, never converted and never
adjusted between steps. An integrator whose rate varies computes the variation in the hook that owns
the unit.

`RATE-081` keeps its primary effect, which is that no handoff leaves `planned` under `soft`, and
loses the clause that reduced the budget. `RATE-091` is untouched, so `hard` still withholds
`transfer` and `catchUp` while letting the six completing hooks run. `RATE-101` treats a `deferred`
result as a signal for one handoff and now forbids it delaying a step of another rather than
reducing another's budget.

The `sharder.migration.step_budget` gauge leaves the required metrics of `OBS-010`, because it would
report a configured constant. `MOVE-097` no longer names a step budget among the state a rebase
leaves unchanged, and `CFG-051` states the default rather than the loop it fed.

## Consequences

The concurrency bounds are the rate control. `maxConcurrentHandoffs`,
`maxConcurrentPerSourceNode`, and `maxConcurrentPerDestinationNode` bound how much work is in
flight, the gauge decides whether more is admitted, and a hook that wants less returns `deferred`.
None of the three needs to know what a unit is.

A migration no longer starts slowly and finds its rate out of the box. An integrator who wants that
behaviour writes it in `transfer`, where the unit is theirs and a measurement of what the last step
cost is available, and they are better placed to than the library is.

A handoff has no per-handoff hidden variable. Its observable state is its state, its attempt count,
and its accumulated measurements, each of which is an output a conformance scenario can assert.

The migration rate vectors lose the budget arithmetic and keep the retry backoff of `RATE-051` and
the policy refusals of `RATE-021`. The `migration` conformance level is otherwise unchanged.

An integrator who had set `maxStepBudget` to bound a step's cost sets `initialStepBudget` instead,
which is the same bound without the ramp.

## Alternatives

Keeping the loop and removing the pressure-driven decrease alone, so that the budget grows and never
shrinks. Rejected because an increase that nothing reverses is a slow saturation, which is the
failure the record was written to avoid.

Keeping the loop and typing `budgetUnit` so the library can interpret it.
Rejected because [`0021`](0021-migration-backpressure-control.md) rejected a rate in bytes per
second for the reason that still holds: the library does not know that a unit is a byte, has no
clock of its own, and would have to divide by an interval.

Keeping `minStepBudget` and `maxStepBudget` as validation bounds on `initialStepBudget`. Rejected
because a floor and a ceiling on a constant are two settings that say what one setting already says,
and `RATE-021` refusing a zero budget is the only bound a constant needs.

Keeping the `sharder.migration.step_budget` gauge. Rejected because `OBS-020` fixes an event name
permanently and `OBS-010` states the metrics an implementation reports; a gauge whose value is a
setting is read once from `ConfigurationView` under `CFG-007` and never sampled.
