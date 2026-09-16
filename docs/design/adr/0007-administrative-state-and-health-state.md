# 0007. Administrative state and health state

Status: accepted. Date: 2026-09-16.

## Context

A node can be unavailable for two quite different reasons. An operator may have decided to remove
it, which is a fact about intent that every caller should agree on. Or it may have stopped answering
one caller's requests, which is an observation, is local to the observer, and may be wrong.

Collecting both into one state machine, with states such as healthy, draining, down, joining, and
quarantined in a single enumeration, is the obvious shape and it produces a contradiction. States
that describe intent belong in the topology document, where they are agreed and versioned by an
epoch. States that describe observation cannot go there, because a caller cannot publish a new epoch
every time a connection times out, and because two callers observing differently would then be
holding two different topologies.

## Decision

Node state is two orthogonal things with separate vocabularies, separate owners, and separate
lifetimes.

Administrative state is authored by the topology authority, carried in the topology document, and
identical for every caller at a given epoch. Its values are `active`, `joining`, `draining`, and
`leaving`. The placement set at an epoch is the nodes whose administrative state is `active` or
`draining`; `joining` and `leaving` nodes appear in the document and in no candidate ordering.

Health state is derived by each caller from signals it observes, is held outside the topology, and
may differ between callers. Its state machine, its signal ingestion, its hysteresis, and its
probation behaviour are specified in [`10-specification.md`](../10-specification.md). Health state
is never serialised into a topology document.

The two combine by filtering, never by reordering. Placement produces a preference list from the
administrative view alone, so every caller computes the same list for the same key at the same
epoch. The health view then decides which entries of that list a caller attempts, in the list's
order, skipping entries it believes unavailable. A caller with a stale or wrong health view attempts
a different set of nodes; it does not compute a different owner.

A draining node continues to own and serve its shards. Draining is a signal to the authority and to
the migration planner that this node's shards should be moved, and ownership changes only when a
later epoch reassigns them. Draining is not a way to stop traffic.

## Consequences

Ownership and reachability are separable in every discussion downstream. The question "who owns this
shard" has one answer per epoch, and the question "who will answer right now" has one answer per
caller.

A node that is down cannot be taken out of placement quickly. Editing the topology to remove it
moves its keys to new owners, which for a storage cluster means a migration, so the correct fast
response to a failure is the health view, not a topology edit. The slow, agreed response is a drain
followed by removal.

Two callers cannot disagree about ownership, which is what makes deterministic fallback safe for the
storage use case. They can and do disagree about which replica they are currently talking to, which
is an efficiency question.

An operator reading the topology document cannot see whether a node is up. Liveness is reported
through metrics and events instead, and the topology document is a statement of intent rather than a
status page.

## Alternatives

A single state enumeration covering both, with `down` and `quarantined` alongside `draining` and
`joining`. Rejected because it either forces health into the topology document, which makes every
transient failure an epoch, or leaves administrative state caller-local, which destroys agreement on
ownership.

Health published in the topology document by a control plane that aggregates it. Rejected because it
adds a control plane round trip to every failure detection, because aggregated health is stale by
construction, and because it makes availability of the routing decision depend on availability of
the authority.

Expressing unavailability as a weight of 0. Rejected because a weight change is a placement change:
it moves the node's keys to other nodes, which for a storage cluster means data movement in response
to a transient failure. Skipping a node and reassigning its keys are different operations and need
different mechanisms.

Health reordering the preference list, so a caller prefers the healthiest replica. Rejected as the
default because it makes two callers disagree about the primary, which for a storage cluster is a
correctness problem rather than an efficiency one. Read-side preference for a local replica is
expressed as a separate, explicitly requested behaviour rather than as a reordering of the list that
defines ownership.
