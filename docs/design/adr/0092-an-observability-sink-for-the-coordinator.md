# 0092. An observability sink for the coordinator

Date: 2026-09-19

Status: accepted

## Context

`OBS-020` tables ten events under `migration.`, and an implementation emits the events of the
surfaces it exposes. The Java port declared all ten in its inventory, which is what the
`migration` inventory vector reads, and emitted none of them. Nothing detected that, because no
driver asserts an emitted event for any surface.

The reason the port emitted none is structural rather than an oversight at a call site. A router is
built from a `RouterConfig`, which carries the metrics registry and the event sink, so
`DefaultRouter` holds a `MetricsHolder` and emits the `routing` events through it. A coordinator is
built by `Sharder.coordinator()`, which takes nothing, so there is no sink to emit through and
nowhere for one to come from.

The coordinator is deliberately stateless between calls under `MOVE-061`, and a plan is a pure
function of the two snapshots and the policy under `MOVE-221`. Neither of those is in tension with
reporting what happened, but both rule out the coordinator acquiring a sink by holding onto a
router.

## Decision

`Sharder.coordinator(RouterConfig)` is added beside `Sharder.coordinator()`. The configuration
carries the metrics registry and the event sink the router already reads, and the coordinator reads
the same two and nothing else from it.

`Sharder.coordinator()` stays, and means a coordinator that reports nothing. It is the right call
for an integrator that has not configured observability, and removing it would make the simple case
carry a configuration it has no other use for.

`MigrationPolicy` does not carry the sink. It is a record of tuning values that a plan reports as a
payload member of `migration.planned`, and putting a dependency inside it would make two plans
incomparable that differ only in where their events go.

`plan` and `lineage` do not take a sink per call. A sink is a property of the process rather than of
one migration, and threading it through every call would put it in the signature of every future
operation.

## Consequences

The precedent is `Sharder.loader(RouterConfig)`, which already takes the whole configuration to
answer a narrower question. An integrator that has a `RouterConfig` for its router passes the same
one, and the coordinator reads the two observability members from it.

A coordinator built from a configuration whose observability is unset behaves exactly as one built
from none, because `MetricsHolder` already treats an absent registry and an absent sink as counting
by name and discarding.

The events a running coordinator emits are still not asserted by the suite, for any surface. This
record does not change that, and
[`../30-conformance.md`](../30-conformance.md#observability-vectors) now says so plainly rather than
claiming an assertion that no driver performs.

## Alternatives

A sink on `MigrationPolicy`. Rejected above: the policy is data a plan reports, not a dependency.

A sink parameter on `plan` and `lineage`. Rejected: it is per process, not per call.

A coordinator that takes a `Router` and reads its sink. Rejected. It would tie the coordinator's
lifetime to a router's and give it a snapshot it must not read, when what it needs is two members of
a configuration.

Leaving the coordinator silent and recording the ten events as unimplementable. Rejected. They are
implementable, the contract is published in `OBS-020`, and the withdrawal register makes each name
permanent, so a port that emits nothing has broken a contract rather than deferred one.
