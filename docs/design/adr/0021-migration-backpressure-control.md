# 0021. Migration backpressure control

Status: accepted, with the budget adjustment withdrawn by
[`0057`](0057-step-budget-adjustment-withdrawal.md). Date: 2026-09-16.

The concurrency bounds, the three pressure levels, the asymmetric response at `soft` and at `hard`,
the `deferred` route, and the confinement of measurement to what the hooks report all stand. The
additive increase and multiplicative decrease do not: they are arithmetic on a quantity the same
record requires the library to treat as opaque, so `RATE-041` and the three policy members that
configured it are withdrawn and `initialStepBudget` is passed unchanged to every step.

## Context

A rebalance competes with production traffic for the resources of the cluster it is rebalancing. A
copy that saturates a disk, a network link, or a storage engine's compaction budget turns a planned
topology change into an outage, and the operator's only recourse is to stop the migration entirely
and start again later.

The library sequences the copy and does not perform it, so it cannot measure what the copy costs. It
does not know whether a unit is a byte, a record, or a file, it opens no connection, and it probes
nothing. Any rate control it offers therefore has to be driven by numbers the integrator reports.

## Decision

The control surface is a migration policy passed at plan construction, with three kinds of member:
concurrency bounds, a step budget with its adjustment parameters, and deadlines with retry bounds.
Every member is a non-negative integer. No member is a rate in units per second, because the library
has no clock of its own and because a rate in a unit it cannot interpret is not a number it can
check.

The budget is a count in the integrator's own unit, declared by the hooks as an opaque string. The
library passes it to `transfer` and `catchUp` unmodified, scales it by integer arithmetic, and never
converts it. Increase is additive by a configured increment up to a ceiling; decrease is by unsigned
integer halving down to a floor of at least one. That is additive increase with multiplicative
decrease, expressed without a single division that is not exact.

Backpressure arrives by two routes. A hook returns `deferred` with a retry delay, which throttles
that handoff alone. A pressure gauge that the integrator supplies answers `none`, `soft`, or `hard`
for a scope, and the coordinator takes the highest of the cluster scope, the source node, and the
destination node before each step. Three levels rather than a number, because a number would invite
arithmetic on a quantity whose meaning the library does not know.

The response to pressure is asymmetric, and the asymmetry is the decision worth recording. At
`soft`, no handoff leaves `planned` and budgets shrink. At `hard`, no handoff leaves `planned` and
neither `transfer` nor `catchUp` is called, but `quiesce`, `commitCutover`, `verify`, `cleanup`,
`rollback`, and `observe` all continue. Those six complete work that is already begun. Withholding
them would hold shards in the window where two nodes hold a copy, which is the state a migration
should spend the least time in, and would leave a quiesced shard refusing writes for as long as the
pressure lasts. Backpressure slows the start of work and never the finish of it.

The coordinator returns `idle` when nothing is admissible. It does not spin, wait, or sleep inside
`step`, because the thread belongs to the integrator.

Measurement is confined to what the hooks and the supplied monotonic clock report. The library
derives no pressure level from its own measurements, so the gauge is the only input to admission.
Floating point is permitted in what is reported and forbidden in anything that decides.

## Consequences

An integrator who supplies no gauge gets a migration running at the configured budget that throttles
only on `deferred`, which is the behaviour of a library with no rate control and is a defensible
starting point.

The quality of the control is the quality of the gauge. An integrator who wires it to a queue depth
or a compaction backlog gets a migration that yields to production traffic; one who returns `none`
unconditionally gets none of the protection, and the library cannot tell the difference. That
dependency is stated rather than hidden.

Budget adjustment is fully deterministic given a sequence of step outcomes, so it is a property test
rather than a benchmark: the same outcomes produce the same budgets in every port.

Three pressure levels are coarse. A gauge that wants to express a gradient expresses it by moving
between levels over time, and the halving rule gives a geometric response to a sustained `soft`.

Allowing completion under `hard` means a cluster under severe pressure still performs cutovers,
verifications, and cleanups. Those are cheap relative to a bulk copy, and the alternative leaves the
cluster in the concurrent-holding window, which is worse.

## Alternatives

A rate limit in bytes per second inside the library. Rejected because the library does not know that
a unit is a byte, has no clock of its own, and would have to divide by an interval, which invites
floating point into a control path.

A token bucket the library refills from a clock it reads. Rejected for the same reason and because
refilling requires the library to run something between the integrator's calls, which it has no
thread to do.

A single numeric pressure value between zero and one. Rejected because it is a floating point number
in an admission decision, and because scaling a budget by it requires rounding rules that two ports
would get subtly different.

Pausing every hook under `hard`, uniformly. Rejected because it strands shards in the concurrent
window and keeps quiesced shards refusing writes, which converts backpressure into an availability
loss.

Inferring pressure from step latency the library already measures. Rejected because a slow step and
a loaded cluster are not the same thing, because the library would then be guessing about a system
it cannot see, and because an inference that is wrong stops a migration an operator wanted.
