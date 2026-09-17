# 0045. Attempt limit resolution order

Status: accepted. Date: 2026-09-17.

## Context

Three requirements described the attempt limit and two of them disagreed. `CORE-045` made
`attemptLimit` on a routing decision "the value `RouteOptions` supplied, or the default of
`FAIL-022`". `FAIL-022` made that default `n + 2`, clamped to the length of the attempt sequence.
`CFG-020` listed `attemptLimit` as a configuration setting whose default is the factor plus 2.

Read together, an integrator who configures `attemptLimit` of 5 and a caller who passes no limit get
`n + 2`, because `CORE-045` resolves straight to `FAIL-022` and never consults the setting. The
setting is then unreachable: no call path reads it, and `CFG-007`, which requires an implementation
to expose the values in force, would report a number that decides nothing.

The resolution order is observable. `attemptLimit` is a field on `RoutingDecision`, `CORE-045`
forbids `attempts` resolving it again, and a caller reads it.

## Decision

The setting is reachable, and the resolution has two levels rather than three.

`FAIL-022` now states the whole resolution in one place: the limit in force is the value
`RouteOptions` supplies where it supplies one, and otherwise the configured `attemptLimit` of
`CFG-020`. Where the integrator configures no value, `CFG-020` supplies `n + 2` against the
decision's effective replication factor. The resolved limit is clamped to the length of the attempt
sequence. `FAIL-022` also forbids consulting the setting where the call supplies a limit, and
forbids bypassing the setting where it does not.

`CORE-045` no longer restates the rule. It says `attemptLimit` is the limit `FAIL-022` resolves for
the decision, and keeps its own requirement that `attempts` takes the limit from the decision rather
than resolving it again. `CFG-020`'s row keeps the default of the factor plus 2 and now says the
setting is the limit a call inherits where it supplies none.

`conformance/vectors/formulas/failover.json` gains six `resolvedAttemptLimit` cases covering a
supplied limit, a configured limit, both together, neither, and the clamp against a short attempt
sequence.

## Consequences

An integrator who wants every caller in a process to walk two attempts deep sets the setting once at
construction, which is what a setting is for and what
[`0026`](0026-configuration-defaults-and-locality.md) puts in configuration rather than in the
topology document. A caller that knows its own deadline overrides it per call.

`n + 2` remains the number an implementation produces where nothing is configured and nothing is
supplied, so no shipped behaviour changes and no vector moved. The formula the suite already carried
as `defaultAttemptLimit` still holds, and the new cases sit beside it rather than replacing it.

`CFG-004` is satisfied: the setting decides how far a caller walks and changes no candidate
ordering, no preference list, and no effective replication factor. Two routers configured with
different limits over one snapshot still compute the same preference list for a key, and differ only
in how much of it the attempt sequence offers.

A three-level resolution, with the setting sitting between the call and a separate `FAIL-022`
default, was avoided. It would have given `n + 2` two homes, one in `FAIL-022` and one in
`CFG-020`'s default column, and the defect being repaired is exactly that arrangement.

## Alternatives

Dropping `attemptLimit` from `CFG-020`. Rejected because the per-call override is the wrong place to
express a process-wide policy: every call site would have to pass the same `RouteOptions`, and an
integrator who missed one would get a different depth with no signal. `CFG-021`, which refuses a
configured limit of 0, also reads as a construction-time check on a setting rather than on an
argument.

Leaving `CORE-045` as the statement of record and making `CFG-020` refer to it. Rejected because the
resolution belongs with the requirement that owns the attempt sequence, and because `CORE-045`
states a field on a record rather than a policy. The routing decision surface is also the surface
most likely to be restated by a binding, and a resolution rule restated in a binding is a resolution
rule that drifts.

Resolving the limit inside `attempts` rather than at route time. Rejected because `CORE-045` already
forbids it and because the decision carries the limit to the caller, which would otherwise read a
number that the attempt sequence might not honour.
