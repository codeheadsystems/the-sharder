# sharder

A language-agnostic sharding and routing library. Given a key and a view of the world, `sharder`
answers which nodes handle that key, in what order, and what happens when those nodes are
unavailable.

Two implementations in two languages, given the same topology and the same key, produce the same
ordered list of nodes. Determinism is a hard requirement, and a conformance suite of
language-neutral data files is what holds every port to it.

## Status

Design complete. No implementation exists.

The specification, the topology document format and its schema, the conformance suite with its
reference generator, the Java binding design, and the decision records are written. Java is the
first implementation and is under way: the hash construction, the opaque identifier types, the error
codes, the JSON reader, and the conformance harness are written, and the port reaches the `hash`
conformance level. Every implementation sits under [`ports/`](ports/), and
[`docs/design/35-port-conventions.md`](docs/design/35-port-conventions.md) states what each one
carries whatever its language.
Nothing is published. The release comes after every port passes the conformance suite in its own
test harness, under
[`docs/design/adr/0083`](docs/design/adr/0083-publication-as-the-last-stage.md).
[`docs/design/99-roadmap.md`](docs/design/99-roadmap.md) gives the stages before it, and
[`docs/design/90-open-questions.md`](docs/design/90-open-questions.md) gives what is still
unresolved.

## The problem

An application that spreads work across many nodes has to decide, on every request, which nodes are
responsible for it. Every caller has to reach the same answer, or two of them write to different
places. The answer has to survive a node failing, a node joining, and a caller holding a view of the
cluster that is a few seconds behind. It has to spread replicas across racks and zones, and to say
what it does when there are not enough of them. When ownership of a shard moves from one node to
another, something has to sequence the move so that no request is served by both nodes at once, and
no request is served by neither.

Most systems solve this once, inside themselves, in a form nobody else can reuse. `sharder` solves
it as a library, in a specification precise enough that a Java service and a Go service route
identically against one topology.

## Capabilities

- Places a key on an ordered list of nodes by one of four strategies: a hash ring, rendezvous
  hashing, a fixed slot count, or an explicit directory table.
- Honours heterogeneous node capacities as integer weights, and pins or constrains keys to named
  nodes or named failure domains.
- Builds a preference list with distinct replicas spread across failure domains, and degrades that
  spread by a stated ladder when a perfect spread is impossible.
- Skips unavailable nodes without ever reordering the list, so two callers with different views of
  node health attempt different nodes and still agree on who owns the shard.
- Runs a node health state machine with hysteresis, outlier ejection, and probation on recovery.
- Loads topologies through a pluggable provider, enforces epoch monotonicity over them, and hands
  every routing decision a fencing token that a recipient uses to refuse a stale request.
- Sequences ownership handoff through an eleven-state machine with abort, recovery, rate control,
  and backpressure, calling hooks the integrator implements.
- Reports metrics, events, hot-shard and key-skew detection, and an explain record that says why a
  key routed where it did.

It does not implement consensus, does not elect a leader, does not move data, does not open a
connection, and does not speak a storage or transport protocol.

## Use cases

Storage nodes, Dynamo-style. A keyspace partitioned across storage nodes with `n` replicas per
shard, writes sent to a preference list, reads preferring a local replica, and data moving when
membership changes. A wrong routing decision here is data loss rather than latency, so the
specification is explicit about which guarantees are real and which are best-effort.

Customer and tenant routing. Requests carrying a tenant identifier routed to a service cluster, with
some tenants pinned by contract and others constrained to a region by residency law, and frequently
with no replication at all. An integrator serving only this case never constructs a handoff
coordinator and never pays for the migration state machine.

Cache and shard-affinity routing. Sticky routing where losing a node disturbs as few keys as
possible and a briefly stale view of the cluster is tolerable rather than catastrophic.

The three pull against each other in four places.
[`docs/design/00-overview.md`](docs/design/00-overview.md#conflicting-requirements) names each and
says how configuration resolves it.

## Reading order

[`docs/README.md`](docs/README.md) routes by reader: evaluating the library, implementing a port,
writing a provider or a strategy, operating a cluster, changing a document or the conformance suite,
or contributing to the Java implementation. Each path names sections rather than whole documents.

Otherwise, [`docs/design/00-overview.md`](docs/design/00-overview.md) is the front door. It carries
the component model, the data flow for a routing call and for a rebalance, and a walkthrough of each
of the three use cases. The vocabulary the rest of the corpus uses is in
[`docs/design/05-glossary.md`](docs/design/05-glossary.md).

[`CONTRIBUTING.md`](CONTRIBUTING.md) gives the contributions that are possible today: a change to a
document, to the specification, or to the conformance suite through its generator, and starting a
port.

## Coordinates

| Coordinate | Value |
|---|---|
| repository | `codeheadsystems/the-sharder` |
| group | `com.codeheadsystems` |
| artifact | `sharder`, one module carrying the library, under [`adr/0081`](docs/design/adr/0081-single-java-module.md) |
| top package | `com.codeheadsystems.sharder` |
| JDK floor | 21 |
| runtime dependencies | none beyond `java.base`, outside the conformance harness |

The `the-` prefix belongs to the repository name alone. It appears in no artifact, module, or
package name.

## Repository layout

| Path | Contents |
|---|---|
| [`docs/README.md`](docs/README.md) | the documentation entry point |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | how a document, the specification, or the suite is changed |
| [`docs/design/`](docs/design/) | the overview, the glossary, the specification, the format, the conformance design, the Java binding, and the decision records |
| [`docs/maintain/style.md`](docs/maintain/style.md) | the documentation style guide, binding on everything under `docs/` |
| [`conformance/`](conformance/) | the conformance suite: vectors, topologies, properties, scenarios, and the reference generator |
| [`conformance/declarations/`](conformance/declarations/) | what each port declares it reaches, against a suite revision |
| [`ports/`](ports/) | one directory per implementation, each rooted in the build its ecosystem expects |
| [`build.sh`](build.sh) | builds every port, or the ports named; each port carries its own `build.sh` |
| [`.github/`](.github/) | the workflows that build every port and verify the suite, and the weekly dependency updates |
| [`bench/`](bench/) | measurement sources a decision record cites, built with a system compiler |
