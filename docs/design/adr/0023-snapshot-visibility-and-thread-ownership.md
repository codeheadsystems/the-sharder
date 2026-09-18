# 0023. Snapshot visibility and thread ownership

Status: accepted. Date: 2026-09-16.

## Context

`CORE-060` forbids the library from starting a thread it was not given, and `TOPO-121` requires a
routing call to see exactly one snapshot for its whole lifetime. Neither states how a snapshot
becomes visible to a routing call running concurrently with an installation, and a specification
that leaves that to each port produces two ports that disagree under load rather than under test.

The library is language-agnostic. Java, Go, Rust, C, and Python express publication, atomicity, and
ordering with primitives that have no common name and no common strength. A specification written
around a volatile field, an atomic pointer, or a release store would name a mechanism four of the
five ports do not have.

Three consumers pull in different directions. A routing call is on the hot path and must not take a
lock. Topology installation is rare and may be expensive. The handoff coordinator wants a scheduler,
a retry timer, and a thread pool, and cannot have any of them, because the deployments that embed
the library already own their execution model and a library that starts threads inside them becomes
an operational surprise rather than a dependency.

Provider polling is where the tension becomes concrete. A `pollInterval` implies something that
wakes up, and nothing in the library is allowed to wake up.

## Decision

Visibility is specified as an ordering requirement rather than as a primitive. Construction of a
snapshot, including every product of its placement preparation, is ordered before the update of the
reference a routing call reads, for every unit of execution that observes the update. A reader that
observes the updated reference observes the whole snapshot. Each port satisfies this with the
mechanism its memory model offers, and a port that publishes by an unordered write is
non-conforming whether or not a test catches it.

A snapshot is immutable after publication, so a reader needs no further synchronisation and the
routing path takes no lock. Installation is a single atomic replacement of one reference, never a
mutation of the snapshot in force, and a snapshot stays readable until every call that acquired it
completes.

The specification states what is safe to share rather than leaving it to a binding. A `Router`, a
`TopologySnapshot`, a `PreparedPlacement`, a `RoutingDecision`, an `ExplainRecord`, a
`FencingToken`, a `HealthView`, and a `MigrationPlan` are usable by any number of units of execution
at once. An `AttemptSequence` and a lazy candidate iterator belong to one walk and are not made safe
by a lock, because that lock would cost every routing call to serve a case that does not arise.

The library creates no unit of execution of any kind. Everything it does runs on a call the
integrator makes, or on an executor the integrator supplied at construction. Where no executor is
supplied the library does not poll and does not advance a timer: `refresh`, `HealthView.advance`,
and `MigrationPlan.step` are the calls through which an integrator drives those effects, and
`pollIntervalMillis` describes the period of an executor the integrator provides rather than a
period the library keeps on its own.

The library holds no lock across a call into an extension point, and a routing call blocks on no
input, no output, no installation, and no extension point other than the health view.

## Consequences

An integrator who supplies no executor gets a library that never refreshes a topology by itself.
That is a trap for a first-time integrator, and the configuration surface answers it by making
`executor` an explicit setting whose absence has a documented meaning rather than a silent one. A
binding is free to supply a default executor of its own, because a binding is a component the
integrator chose.

The visibility rule is testable only by review and by a race detector, not by a conformance vector.
A vector runs a deterministic computation, and a publication that is unordered produces the right
answer in almost every run. The conformance suite therefore covers the observable consequence, that
a routing decision names one epoch, and leaves the mechanism to each port's own concurrency testing.

Refusing a lock on the read path means a port cannot implement the snapshot as a mutable structure
guarded by a reader-writer lock, which is the obvious first implementation in several languages.
The cost falls on the port once and on every routing call never.

Declaring an `AttemptSequence` single-walk pushes a correctness obligation onto a caller that fans
one request out across threads. The failure mode is a miscounted retry budget rather than a wrong
node, which is why the trade was taken in this direction.

## Alternatives

Copy-on-write with a reader-writer lock on the routing path. Rejected because the lock is acquired
on every routing call to serve an installation that happens every few minutes, and because lock
acquisition under contention is the kind of cost that only appears at the load where it matters.

Specifying the Java memory model and requiring other ports to emulate it. Rejected because the
corpus is language-agnostic by design and because the emulation would be nominal: a Go port would
satisfy it by reasoning about happens-before anyway, so naming Java buys nothing and excludes the
languages whose models are stated differently.

Sequence-locked reads, where a routing call reads a version counter before and after and retries on
a mismatch. Rejected because it solves a problem the design does not have. Snapshots are immutable,
so there is nothing for a reader to be torn by once publication is ordered.

An internal thread for polling, started lazily and stopped on `close`. Rejected against `CORE-060`.
The deployments in view run their own executors, size their own pools, and account their own
threads, and a library thread that appears in a flight recording without an owner is a support
burden out of proportion to the convenience.

A callback the library invokes to request that the integrator schedule work. Rejected because it is
an executor with a worse name, and because it leaves the library holding a queue of pending work
whose failure modes are the ones a thread pool has without the tooling a thread pool has.
