# 0063. Decision API surface boundaries

Status: accepted. Date: 2026-09-17.

## Context

[`0058`](0058-conformance-surfaces.md) made every requirement belong to one conformance surface and
declared the surface of a requirement through its prefix rather than one requirement at a time. It
recorded that "no other requirement paragraph changes, because the prefix rule reaches the rest".
The prefix rule does not reach the rest. Five requirements under the `routing` prefixes state their
content in terms of a surface an implementation is free to decline, so a port exposing `routing`,
`rendezvous`, and `directory`, and no more, is bound by rules of `failover`, `readAffinity`, and
`fencing`.

`CORE-030` gives the router one shape, and that shape names `routeForRead`, which is the
`readAffinity` surface, and `attempts` and `health`, which are the `failover` surface.

`CORE-040` gives the routing decision one shape, and that shape carries `token`, which the surfaces
table assigns to `fencing`, `attemptLimit`, which `FAIL-022` resolves, and a `health` state and an
`attemptable` flag on every entry, which `FAIL-013` and `HEALTH-005` state.

`CORE-045` makes `attemptLimit` the limit `FAIL-022` resolves, while `CORE-111` makes an
implementation that does not expose `failover` refuse the `attemptLimit` setting of `CFG-020`. The
required value has no resolution for such an implementation.

`CORE-046` makes the length of the materialised prefix depend on the same resolution, and that one
decides vectors. The `place` level is mandatory, 28 of its vector files assert `materialisedEntries`
on every case, and the reference computes that count as `n + 2` where nothing is configured and
nothing is supplied. A port that cannot resolve `FAIL-022` cannot pass a level the specification
says it cannot decline.

`TOPO-151` requires every routing decision to carry the fencing token, and `SPREAD-016` and
`REPL-022` require events that carry it, while the surfaces table names the token as part of
`fencing`.

## Decision

The `routing` surface owns the whole of the routing decision, including the attempt limit that fixes
its length and the fencing token it carries. The `failover` surface owns the attempt walk, the
health view, and the retry budget. Five changes put the boundary there.

`CORE-048` states the resolved attempt limit: the value `RouteOptions` supplies, otherwise the
configured `attemptLimit` of `CFG-020`, otherwise the effective replication factor plus 2. It
belongs to `routing`, so every implementation resolves it. `CORE-045` and `CORE-046` name it.
`FAIL-022` keeps the clamp to the length of the attempt sequence, which is a property of the walk
and applies to the walk alone.

The `attemptLimit` row of `CFG-020` and the whole of `CFG-021` move from `failover` to `routing` in
the surface table of the Conventions section. The setting decides the length of `entries` on every
decision, which is `routing` behaviour, so an implementation with no attempt walk accepts it.

`CORE-030` gains a table naming the surface each member of the router belongs to, and requires the
shape with the members of a declined surface omitted. `route`, `explain`, `snapshot`, `refresh`, and
`close` are `routing`, `routeForRead` is `readAffinity`, and `attempts` and `health` are `failover`.

`CORE-040` states what `health` and `attemptable` hold where no health view exists: `unknown` and
attemptable, which is what `HEALTH-004` and `HEALTH-005` give a caller that has ingested no signal.
It also states that `token` is required of every implementation by `TOPO-151`.

The surfaces table names the fencing token encoding as part of `fencing` rather than the token
itself. A recipient interprets a token, and the surface is the interpretation.

No expected value changes. The `defaultAttemptLimit` and `resolvedAttemptLimit` cases of
`conformance/vectors/formulas/failover.json` name `CORE-048` alongside the identifiers they already
named, which moves the suite revision and moves no computed value.

## Consequences

The claim of [`../30-conformance.md`](../30-conformance.md#conformance-levels), that the four
optional levels are optional because each tests a surface `CORE-110` leaves to the implementation,
is now true of the surfaces as well as of the levels. A port declaring `routing`, `rendezvous`, and
`directory` walks the specification and meets no requirement of a surface it declined. That is the
six-week path to `place` that the level exists to open.

`CORE-048` is a new identifier in a live prefix and takes the next free number, as the Conventions
section requires. No identifier is renumbered and none is reused. The twelve formula cases that
already covered the resolution name it, so it is covered on the revision that states it rather than
joining the uncovered set. Those cases sit at the `failover` level, and the default resolution is
covered at `place` as well, through the `materialisedEntries` that `CORE-046` bounds.

[`0045`](0045-attempt-limit-resolution-order.md) decided that the resolution has one home and warned
against giving `n + 2` two. It still has one home, and the home moves from `FAIL-022` to `CORE-048`.
The two-level order that record decided, the call before the setting, is unchanged, and the setting
stays reachable.

An implementation that exposes `failover` sees no change at all: it resolves the same limit from the
same inputs, clamps it the same way, and materialises the same entries.

`CORE-111` still makes a surface a unit of declaration. What changes is which requirements sit
inside which unit, not the rule that a unit is whole.

## Alternatives

Scoping `CORE-030` by splitting the router into one interface per surface. Rejected because the
member-to-surface table states the same partition without giving a binding four types where it needs
one, and because `CORE-030` is the definition every requirement naming `route` refers to; splitting
it would make each of those references ambiguous.

Leaving the attempt limit in `FAIL-022` and stating a separate default for an implementation that
does not expose `failover`. Rejected because it gives `n + 2` two homes, which is the arrangement
[`0045`](0045-attempt-limit-resolution-order.md) repaired, and because two implementations would
then resolve the same limit by two different requirements.

Removing `materialisedEntries` from the `place` vectors, so that a mandatory level asserts nothing
that depends on the attempt limit. Rejected because the materialised prefix is the bound that
[`0046`](0046-bounded-routing-decision-surface.md) exists to state, a port that materialises the
whole preference list is the defect those vectors catch, and dropping the assertion would lose the
catch rather than move it.

Making `failover` mandatory. Rejected because an integrator who routes a tenant identifier to one of
five clusters ingests no health signal and walks no attempt sequence, and requiring the health state
machine of `HEALTH-055` before such a port can declare anything is the cost
[`0058`](0058-conformance-surfaces.md) set out to remove.
