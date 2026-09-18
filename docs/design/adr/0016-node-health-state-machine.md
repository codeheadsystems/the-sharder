# 0016. Node health state machine

Status: accepted, with probation admission moved to the attempt by
[`0067`](0067-probe-admission-at-the-attempt.md) and the entry lifetime extended by
[`0066`](0066-health-reset-on-placement-reentry.md). Date: 2026-09-16.

The five states, the transitions between them, and the arithmetic below are unchanged. Where the
text says that a deterministic counter admits one attempt in `probationDivisor`, `HEALTH-017` now
places that counter on the attempt walk rather than on the routing call, so the share is measured in
attempts as the sentence already describes. `HEALTH-007` adds one way for an entry to leave the
machine, namely the identity re-entering the placement set, under a parameter that defaults to
false.

## Context

[`0007`](0007-administrative-state-and-health-state.md) separates health state from administrative
state and fixes what health state may do: filter a preference list, never reorder it. It does not
say what the states are, how a node enters and leaves them, or how a caller's observations turn into
a state.

The requirement pulls two ways. A health view that ejects too readily turns a slow node into an
unreachable one and, under a correlated failure, ejects most of the cluster. A health view that
ejects too reluctantly sends every request to a node that is already known to be dead. Between them
sits flapping, where a node alternates between ejected and restored faster than the traffic pattern
that caused the ejection changes.

The library is also constrained in what it can observe. It opens no connection and sends no probe,
so every signal arrives from the caller, at a time the caller chooses, with a classification the
caller made. A state machine that assumes a regular probe interval does not fit.

The determinism requirement applies here too, in a weaker form. Two callers may hold different
health views, and that is the point. Two implementations given the same signal sequence must reach
the same state, or the conformance suite cannot test the state machine at all.

## Decision

Five states: `unknown`, `available`, `suspect`, `probation`, and `unavailable`. None collides with
`active`, `joining`, `draining`, or `leaving`, and none is ever serialised into a topology document.

`unknown` is the state of a node no signal has been ingested for, and it is attemptable. A caller
that reports nothing therefore filters nothing, and the library behaves exactly as it would with no
health view at all. `available` and `suspect` are attemptable. `unavailable` is skipped. `probation`
is attemptable on an admitted probe and skipped otherwise.

`suspect` is the hysteresis. A single failure moves a node out of `available` and into `suspect`,
where it is still attempted, and only a threshold crossing moves it to `unavailable`. Three
conditions eject: a run of consecutive failures, a window failure percentage above a threshold, and
outlier status against peers.

Outlier ejection compares a node's failure percentage against the median failure percentage of its
peers with enough samples, and ejects at a fixed margin above that median. Envoy's success-rate
detector uses a standard deviation from the mean, which needs floating point. A median and an
integer margin need neither, and the specification requires unsigned integer arithmetic throughout
health aggregation, ejection, probation admission, and retry budgeting.

Recovery is timed rather than signalled. A node leaves `unavailable` only when its ejection interval
elapses, never on an incoming success, so a stale success cannot cancel an ejection. It then enters
`probation`, where a deterministic counter admits one attempt in `probationDivisor`, and reaches
`available` after a failure-free interval. A failure during probation returns it to `unavailable`
and increments its ejection count.

Flapping is contained by making the ejection interval a function of the ejection count, doubling
from a base and capped. A node that flaps is attempted less often with each cycle. The count resets
after a long continuous run in `available`.

A ceiling on the share of the placement set held `unavailable` guards every transition into that
state. Where the ceiling is reached, the transition is refused and the node holds `suspect`. The
health filter is separately forbidden from producing an empty attempt sequence from a non-empty
preference list.

The specification carries this as `HEALTH-001` through `HEALTH-055`.

## Consequences

A caller that supplies no signals gets deterministic routing with no health behaviour, which is the
right default for the tenant and cache use cases, where the caller's own client library often
handles failover already.

The state machine is testable from a data file. A signal sequence with timestamps, a parameter set,
and an expected state per step is a conformance vector, because every arithmetic step is integer and
every ordering across nodes is fixed by node identity.

Timed recovery means a node that returns quickly is still attempted at the probation rate for
`probationMillis` after its ejection interval elapses. The alternative, restoring on the first
success, cannot be implemented without probing, which the library does not do.

The ejection ceiling means a correlated failure larger than `maxEjectionPercent` of the cluster
leaves nodes in `suspect` and attempted. Under a genuine half-cluster outage the caller sees
failures it could have skipped. It also means the health view cannot turn a partial outage into a
total one, which is the failure mode that matters more.

Probation admission is a counter rather than a sample, so it introduces no randomness and needs no
random source, which suits an implementation in a language with no shared random state. It also
means two callers admit different probes, which the specification lists as a permitted variation.

The parameter set is large. Thirteen numbers is more configuration surface than a small, sharp core
would like, and the defaults are chosen so that an integrator who sets none of them gets behaviour
comparable to Envoy's outlier detection defaults.

## Alternatives

A phi accrual failure detector, as Cassandra and Akka use. Rejected because it needs floating point
in its core computation, because it assumes a regular heartbeat the library does not have, and
because its output is a suspicion level rather than a state, which would push the ejection decision
back to the caller.

Three states, with no `suspect` and no `probation`. Rejected because ejecting on the first failure
turns a single timeout into a skipped replica, and because restoring at full traffic sends a
recovering node the load that ejected it.

Restoring a node on an incoming success rather than on a timer. Rejected because the library has no
probe of its own, so the only successes that arrive for an ejected node are from callers that
ignored the ejection, and a state machine that rewards ignoring it is worse than none.

Ejecting on latency above a threshold, in addition to failures. Rejected for version 1 because a
latency threshold that suits a cache does not suit a storage node, because the library would need a
percentile estimator and therefore either floating point or a sketch, and because a caller that
wants it can classify a slow response as `timeout` and report that.

A standard deviation from the peer mean, as Envoy's success-rate outlier detection computes.
Rejected because it needs floating point. A fixed-point variance was considered and rejected as
harder to specify exactly across languages than a median.

Publishing health to a control plane that aggregates it across callers. Rejected in
[`0007`](0007-administrative-state-and-health-state.md).
