# 0074. Quiesce lease margin and clock assumption

Status: accepted. Date: 2026-09-17.

Amends [`0018`](0018-concurrent-ownership-during-handoff.md) and
[`0019`](0019-handoff-coordination-and-recovery.md). The cutover record, the refusal protocol,
and the passive coordinator all stand.

## Context

A handoff quiesces the source before it commits a cutover record. `quiesce` answers a
`QuiesceResult` carrying `leaseMillis`, and `MOVE-331` required the coordinator not to call
`commitCutover` after the lease had expired "on the supplied clock".

Two clocks are involved and the requirement named one of them. The source node grants the lease and
enforces it on the source's own monotonic clock: at the end of the lease the source stops refusing
writes, because a source that refused forever after a coordinator died would be an outage. The
coordinator measures the lease on the clock the integrator passes to `step`, under `MOVE-062`, which
runs on the coordinator's machine.

Monotonic clocks on two machines share no origin and no rate. Steal time on a virtual machine,
frequency scaling, and a hypervisor's handling of the timestamp counter each produce a few hundred
parts per million of divergence, and a live migration produces considerably more. Where the
coordinator's clock runs slow relative to the source's, the coordinator believes the lease is alive
for an interval after the source has already resumed serving writes, and it commits inside that
interval. `MOVE-311` states that both nodes refuse writes between the success of `quiesce` and the
return of `commitCutover`; across that interval the source does not, and a write it acknowledges is
lost by the cutover that follows.

Network latency happens to push the other way, because the coordinator started counting when the
answer arrived rather than when the source began. The design was relying on that without saying so.

[`0018`](0018-concurrent-ownership-during-handoff.md) rejected library-issued leases because
"issuing a lease requires a clock the library trusts". That reasoning was correct and the library
was nevertheless consuming a lease against a clock it had not reasoned about, which is the same
trust with the obligation moved onto an integrator who had not been told it was theirs.

A second defect sits underneath the first. `MOVE-062` forbade carrying a clock reading from one call
to `step` into the next, and the lease is necessarily measured across calls: `quiesce` succeeds in
one `step` and `commitCutover` is called in a later one. The `deferred` result of `MOVE-171` is
measured the same way. The requirement as written forbade the arithmetic the design depends on.

## Decision

The coordinator records a quiesce instant, subtracts a margin, and reserves room for the commit,
and `MOVE-332` names the two derived quantities. The quiesce instant is the reading taken
immediately before `quiesce` is called, not after it returns, so the request's transit and the
source's own processing fall inside the interval the coordinator measures rather than outside it.
The commit horizon is that instant plus `leaseMillis`, less `quiesceLeaseMarginMillis`.

`MOVE-333` refuses a call to `commitCutover` whose reading plus `commitDeadlineMillis` is above the
horizon. `MOVE-311` already bounds the cutover window by that deadline, so the test keeps the whole
window inside the interval the coordinator believes the source is quiesced for, including the case
where the commit's outcome is undetermined at the deadline. A refusal re-quiesces, under `MOVE-331`,
and emits `migration.quiesce_expired`.

`quiesceLeaseMarginMillis` joins `CFG-050` with a default of 1000 and may be set to 0.

The assumption the margin covers is stated rather than implied. `MOVE-335` says it as an inequality
over the two clocks: over any interval the source's clock measures as `leaseMillis`, the clock
supplied to `step` measures at least `leaseMillis` less `quiesceLeaseMarginMillis`. An integrator
whose clocks diverge by more than that raises the margin or shortens the lease. The library reads no
clock of its own and has no third party to arbitrate, so it establishes nothing here.

`MOVE-336` closes the degenerate case. A lease at or below the margin plus the commit deadline
admits no reading at which a commit is permitted, so it is treated as a failed quiesce and the
handoff aborts and compensates. Calling `quiesce` again would answer the same way and the handoff
would never progress.

`MOVE-062` is corrected to forbid what it meant to forbid, which is treating a reading from one call
as the current instant in a later one, and `MOVE-063` states that an instant a requirement names is
recorded and compared against a later reading of the same source. The integrator supplies one
monotonic source across the calls of one plan, because a comparison between readings of two sources
measures nothing.

`MOVE-381` names both things its first two guarantees rest on and the library does not supply: the
single-winner cutover the hooks implement under `MOVE-321`, and this clock assumption.

The passive coordinator is untouched. Every quantity here is integer arithmetic over readings the
integrator already supplies, no thread is started, no call sleeps, and `MOVE-181` is not reached:
`cleanup` still follows a successful `verify` without variation.

## Consequences

An integrator choosing `leaseMillis` now knows what they are choosing against, which is the sum of
the commit deadline, the margin, and the time their own loop takes to get from a successful
`quiesce` to a `commitCutover`. At the defaults a lease has to exceed 31 seconds to admit a commit
at all, which is a visible constraint rather than a silent one, and `MOVE-336` reports a lease that
does not meet it as a failed quiesce on the first attempt.

A cluster whose two clocks diverge more than the margin covers still loses the exclusion. The
library cannot detect that, and `MOVE-335` says so. The alternative is to claim a guarantee that
rests on an unstated assumption, which is what this record repairs.

A busy coordinator re-quiesces more often than before, because the admissible interval is shorter by
the margin and the commit deadline. `migration.quiesce_expired` is what an operator reads to see it,
and the response is a longer lease rather than a smaller margin.

The margin defaults to 1000 rather than to 0. A default of 0 would reproduce the behaviour this
record repairs for every integrator who did not read it, and the cost of the default is one second
of a lease.

## Alternatives

Leaving the lease measured on the coordinator's clock alone, as `MOVE-331` had it. Rejected because
the common case is safe by an accident of latency rather than by anything the design states, and
because the failure it admits is a lost acknowledged write found by post-mortem.

Having the source report its own clock reading in the `QuiesceResult` and the coordinator translate.
Rejected because a reading from a monotonic source is meaningful only to the process that took it,
so the translation needs a round-trip estimate the library has no way to bound, and because it adds
a member to a hook result for a quantity the integrator can fold into `leaseMillis` itself.

Requiring the source and the coordinator to share a clock. Rejected because the coordinator runs
wherever the integrator puts it, which for a thousand-shard rebalance is not the source of any of
them.

Having the library issue the lease rather than consume one. Rejected by record
[0018](0018-concurrent-ownership-during-handoff.md) for the reason that still holds: issuing a
lease requires a clock the library trusts and a party both nodes trust, which is an election.

Making the margin a fraction of the lease rather than a fixed duration. Rejected because clock
divergence over a short lease is dominated by the measurement offset rather than by the rate
difference, so a proportional margin is too small exactly where the lease is shortest, and because a
fraction is a division this specification would then have to fix the rounding of.

Deriving the margin from observed divergence. Rejected because the library measures nothing: it
reads the clock it is handed and holds no second reading to compare it against.
