# 0067. Probe admission at the attempt

Status: accepted. Date: 2026-09-17.

## Context

`HEALTH-051` gives a node in `probation` a probe counter and admits one call in
`probationDivisor`, sixteen by default. `HEALTH-055` describes the parameter as one attempt in that
many, which is what the ramp is for: a recovering node takes a sixteenth of the traffic it would
take healthy, and reaches `available` only after `probationMillis` without a failure.

`HEALTH-017` did not deliver that. It required `route` and `routeForRead` to call `admitProbe` once
for each entry in `probation` they reach in preference list order, and a routing call cannot know
how far a caller will walk. `attemptable` is a field on every entry a decision carries and
`attempts(decision)` is a separate call, so a routing call consumes a probe for each entry in
`probation` it examines while building the attempt sequence under `FAIL-014`, whether or not the
caller attempts that entry or attempts anything at all. The divisor therefore meant one routing
decision in sixteen rather than one attempt in sixteen, and a caller that routed twice for one
request spent two probes on a node it attempted once.

The same reading reached the fail-open rule. `FAIL-012` fails the health filter open where every
entry of the preference list is skipped, and an entry in `probation` whose probe was declined
counted as skipped. `HEALTH-034` caps the share of the placement set held `unavailable` at
`maxEjectionPercent`, so the state a fleet reaches coming out of a correlated failure is half
`unavailable` and half `probation`, and for most keys every entry is then skipped: fifteen times in
sixteen by a declined probe. Every caller failed open at the same moment, onto the whole preference
list, which put the full fleet load on the nodes in `probation`. `HEALTH-046` ejects a node in
`probation` on a single failure and `HEALTH-034` does not refuse that transition, so the recovering
nodes were re-ejected and the cluster oscillated instead of recovering. The retry budget does not
damp it, because `FAIL-032` permits every first attempt and the load is first attempts.

## Decision

The probe is consumed at the attempt, and the health filter reads health state alone.

`HEALTH-017` gives the call to `next` of `FAIL-023`. The attempt walk calls `admitProbe` once for
each entry in `probation` it reaches, answers that entry where the probe is admitted, and continues
to the following entry of the attempt sequence where it is not. None of `route`, `routeForRead`, and
`explain` calls it, and all three report `attemptable` for an entry in `probation` as the value the
table of `HEALTH-005` gives, which no probe decides.

`HEALTH-005` therefore leaves an entry in `probation` attemptable, and `FAIL-012` fails the filter
open only where every entry is skipped on its health state, which is where every entry is
`unavailable`. A declined probe no longer reaches the fail-open test at all.

`FAIL-015` closes the case the two changes open. Where the walk reaches the end of the attempt
sequence having answered no entry, because every entry in `probation` it reached declined, it
answers the first entry of the sequence rather than `exhausted`, and consumes no further probe for
it. A caller always holds one node to attempt where the preference list is non-empty, which is the
guarantee `FAIL-012` gives for the sequence and `FAIL-015` now gives for the walk.

The attempt sequence is consequently a pure function of the preference list and the health states,
with no counter in it. Two callers holding the same health states compute the same sequence, and the
variation `FAIL-010` permits moves from the sequence to the walk.

## Consequences

`probationDivisor` means what `HEALTH-055` says it means. A node in `probation` takes one attempt in
sixteen of those that reach it, whatever the shape of the caller's preference list and however many
times the caller routes.

A fleet emerging from a correlated failure ramps. The entries in `probation` stay in the attempt
sequence, so the filter does not fail open, and the recovering nodes take the admitted share rather
than the whole load of every caller at once. Where a preference list holds nothing but entries in
`probation`, `FAIL-015` sends the request to the head of the sequence, so the ramp shapes the load
and does not refuse it.

`filterFailedOpen` on a routing decision keeps its type and its meaning. The two-valued field was
sufficient once the declined probe stopped reaching the test, so `CORE-040` is unchanged and no
caller reading the field reads it differently.

The contended write of `OQ-14` moves off the routing call. `route` writes no probe counter, and the
increment happens once per attempt that reaches a node in `probation`, which is the same rate the
retry budget window is already written at. That register entry is amended.

The attempt limit of `FAIL-021` counts attempts, so an entry whose probe declines consumes no slot
and the walk continues. A caller that reads `remaining()` sees a bound on attempts rather than a
prediction of them, which was already true of an exhausted preference list.

An implementation can no longer compute the attempt sequence and the probe decisions in one pass. A
port that materialises the sequence eagerly holds it as identities and admits at the walk, which is
one traversal more than before on the path where a node is in `probation`.

## Alternatives

Defining what "reach" means and leaving the probe on the routing call. Rejected because no
definition makes the divisor a share of attempts: the routing call does not know what the caller
will attempt, and every definition prices the probe against something the caller did not do.

Making `filterFailedOpen` an enumeration of `none`, `probation`, and `full`, with the fail-open
re-derived in two stages. Rejected because it repairs the stampede while leaving the divisor
measured in routing calls, and because the field's second value exists only to report a stage that
the health states on the decision's entries already describe under `FAIL-013`.

Answering `exhausted` where every probe declines, instead of `FAIL-015`. Rejected because it turns a
recovering fleet into an unroutable one for fifteen requests in sixteen, and because
[`0017`](0017-failover-depth-and-substitution.md) rejects an empty attempt sequence for the same
reason: a caller's local, possibly wrong observation should not make a key unroutable.

Leaving an entry in `probation` out of the attempt sequence and admitting it only where nothing else
remains. Rejected because the node then takes no traffic at all under normal load, reaches
`available` on the timer of `HEALTH-045` without having served a request, and arrives at full load
untested, which is the outcome [`0016`](0016-node-health-state-machine.md) rejects.

Admitting probes randomly rather than by a counter. Rejected in
[`0016`](0016-node-health-state-machine.md), and moving the counter does not revive it: a random
admission is not reproducible in a conformance vector.
