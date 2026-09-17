# Design brief for the sharder library

The task this brief describes is the design and normative specification of `sharder`, not an
implementation. The specification is precise enough that two independent teams, working in two
different languages, implement it and produce byte-identical routing decisions for the same inputs.

Read the whole brief before starting, then read [`docs/maintain/style.md`](docs/maintain/style.md),
which governs every document produced. Where this brief asks for a decision, make it, and record the
rejected alternatives in a decision record. Where a decision cannot be reached without the project
owner, put the question in the open-questions register with a recommended default rather than
assuming an answer silently.

## Documentation style

[`docs/maintain/style.md`](docs/maintain/style.md) binds every file in the repository. Read it
first.

The rules that a design document strains hardest against, and how they resolve here:

- Third person, present tense, about the library. Not about the reader, not about the author, not
  about the document. A design document that says "we chose rendezvous hashing because we wanted"
  is wrong; "the library places keys by rendezvous hashing" is right.
- Bold marks a defined term on first use and nothing else. A specification is dense and the pull
  towards bolding the important clause is constant. Position carries emphasis; typography does not.
- No capitalised stress. The one exception is the RFC 2119 vocabulary (`MUST`, `MUST NOT`,
  `SHOULD`, `SHOULD NOT`, `MAY`) inside `docs/design/10-specification.md`, where those words carry
  their RFC 2119 meaning. Every other document says "refuses" rather than "MUST refuse".
- No justification in the reference documents. This brief repeatedly asks for a justified decision,
  a stated trade-off, and named prior art. All of that lands in the decision records under
  `docs/design/adr/`, which the style guide carves out for the purpose. The overview and the
  specification state what the library does and link to the record; they do not argue for it, do not
  defend it against imagined objections, and do not certify how carefully it was considered.
- No em dashes. Serial commas. British spelling in prose, source spelling for identifiers. Prose
  wrapped at 100 columns. Noun-phrase headings under eight words, with no verbs of judgement.

An assessment of the design's weak points belongs in the open-questions register and in the report
that closes the task, not in the prose of the documents.

## Purpose

`sharder` answers one question well: given a key and a view of the world, which resources handle it,
in what order, and what happens when those resources are unavailable.

Three use cases are in scope, and the design is validated against all three:

1. Storage nodes, Dynamo-style. A keyspace is partitioned across storage nodes with N replicas per
   partition. Writes go to a preference list; reads may prefer a local replica. Nodes join and
   leave, and data moves when they do.
2. Customer and tenant routing to services. Requests carrying a tenant identifier route to a service
   cluster. Some tenants are pinned to specific clusters by contract or by residency law. There may
   be no replication at all, only an ordered failover pool.
3. Cache and shard-affinity routing. Sticky routing where losing a node disturbs as few keys as
   possible, and where a briefly stale view of the topology is not catastrophic.

The three pull in different directions. Where they conflict, the overview states what the design
does about it and the decision record states why, rather than asserting that one design serves all
three.

## Scope

In scope, the full stack:

- The topology model: keys, shards, nodes, replicas, failure domains, weights, versions.
- Shard assignment: the placement function from key to an ordered list of nodes.
- Failover: node health states, how an unavailable node is skipped, how far down the preference list
  a caller walks, and what the caller is told when the list runs out.
- Health: the model of node liveness, how signals are ingested and aggregated, outlier ejection, and
  recovery and probation. The library owns the state machine; it does not own the transport that
  produces the signals.
- Topology change orchestration: the lifecycle of adding, removing, draining, splitting, and
  merging, including epochs and fencing, and how in-flight requests behave across a change.
- Data movement hooks: the ownership handoff protocol and the callbacks an integrator implements to
  move bytes. The library coordinates and sequences the handoff; it never moves data itself and
  never speaks a storage protocol.
- The observability contract: the metrics, events, and routing-decision explainability that every
  conforming implementation exposes.

Out of scope, and named as such in the overview:

- Consensus. `sharder` does not implement Raft or Paxos and does not elect leaders. It consumes an
  externally-agreed topology and enforces monotonicity over it.
- Transport, remote procedure call, serialisation of caller payloads, connection pooling, and
  wire-level retries.
- Storage engines, replication of data content, conflict resolution, vector clocks.
- Service discovery mechanics.

## Topology ownership

Topology is supplied through a pluggable provider interface, not owned by the library.

The design covers:

- A `TopologyProvider` contract with both pull (snapshot and reload) and push (watch and subscribe)
  semantics, and how an implementation that offers only one of the two adapts.
- A canonical serialisation format for a topology document, with a schema. This is the interchange
  format between languages and the input format for conformance vectors. Versioning and
  compatibility rules are part of it.
- Reference providers that ship in core: in-memory and static file. Adapters for ZooKeeper, etcd,
  Consul, DynamoDB or a control-plane API are third-party and are writable without forking the
  library. Demonstrate this by sketching one such adapter against the interface.
- The behaviour when the provider is unreachable, returns a topology that fails validation, or
  returns an older epoch than the one already loaded.

## Decisions to record

For each of the following, pick an answer, state the trade-off in a decision record, and record the
rejected alternatives there. The overview and specification carry the decision; the record carries
the argument.

### Placement

1. Which placement strategies ship in core. Evaluate at minimum: consistent hashing with virtual
   nodes, rendezvous (highest-random-weight) hashing, jump consistent hash, fixed slot or partition
   count, range partitioning with split and merge, and explicit directory lookup. Some of these
   serve the storage use case and some serve the tenant use case; a single strategy is unlikely to
   serve all three.
2. Whether the strategy is a pluggable interface, a closed set, or both. The interface is defined
   either way.
3. How heterogeneous node capacities (weights) are expressed and honoured.
4. How overrides and pinning work (tenant X lives on cluster Y, data residency), and how they
   compose with the underlying hash strategy without breaking its balance or movement guarantees.
5. How the key is hashed. Specify the exact algorithm, the byte encoding of the key, and the
   endianness. Placement arithmetic avoids floating point, or specifies exact semantics, because
   cross-language determinism is a hard requirement.

### Replication and failover

6. How replica count is configured: globally, per shard, or per tenant class. The behaviour when
   replica count exceeds the number of available nodes or failure domains.
7. How the preference list guarantees distinct nodes and spreads across failure domains (rack,
   availability zone, region). The documented degradation order when a perfect spread is impossible.
8. Deterministic fallback (every caller independently computes the same next node, so no
   coordination is needed) against stateful fallback (health-informed, caller-local, potentially
   divergent). Which is the default. For the storage use case, divergence between callers about who
   owns a shard is a correctness problem rather than an efficiency one.
9. The node state machine: the states (candidates include healthy, degraded, suspect, draining,
   down, joining, leaving, quarantined) with transitions, triggers, hysteresis, and probation on
   recovery. How flapping is contained.
10. Read routing against write routing: whether they share a preference list, how a preference for a
    replica in the local availability zone is expressed for reads, and how that interacts with
    consistency expectations.
11. Retry and exhaustion: how many nodes deep a caller walks, who decides, and the exact result when
    the list is exhausted. Retry amplification is addressed.
12. Hinted handoff and sloppy quorum: in core, out of scope, or a hook.

### Topology change and rebalancing

13. Epochs and fencing. How a node or a peer rejects a request computed against a stale topology,
    and what token travels with a request.
14. The shard ownership handoff protocol as an explicit state machine (candidates include planned,
    preparing, transferring, catching up, cutover, verifying, cleanup, aborted). Who drives it, what
    is idempotent, what is retryable, and what happens when the coordinator dies mid-handoff.
15. The minimal movement guarantee, stated formally. When a node is added to or removed from a
    topology of size N, what fraction of keys may move and what must not. This is a testable
    property and belongs in the conformance suite.
16. Split-brain and concurrent ownership. Whether two nodes can believe they own a shard during
    migration. If so, the safe window and the rules governing it (dual write, read from source until
    cutover). If not, what enforces it.
17. Rate limiting and backpressure on migration, so a rebalance does not take down the cluster it is
    rebalancing.
18. Abort and rollback semantics for a migration in progress.
19. Range splits and merges, if range partitioning is in core: who decides, on what signal (size,
    load, key skew), and how the shard map represents a split in flight.

### Cross-cutting concerns

20. The concurrency model, expressed language-neutrally. Immutable topology snapshots with atomic
    swap is the expected shape. Specify the visibility guarantee a caller gets: whether a single
    request is guaranteed one consistent snapshot for its lifetime.
21. The error taxonomy: a closed, named, numbered set of failure conditions that every language
    binding maps to its own idioms.
22. Observability: required metrics with names and label sets, required events, and an explain
    surface that answers why a given key routed where it did. Hot-shard and key-skew detection
    belong here.
23. The configuration surface and its defaults. Every default is defensible for a first-time
    integrator who reads no documentation.
24. Security and multi-tenancy concerns worth naming: whether a crafted key can force a hot shard,
    whether tenant pinning leaks topology to tenants, and whether the hash function needs to resist
    an adversary.

## Deliverables

Write these into the repository at these exact paths. The style guide already exists and is not
part of the task.

1. `docs/design/00-overview.md`. The architecture. Concepts and vocabulary, as a glossary the rest
   of the corpus uses consistently, the component model, the data flow for a route call and for a
   rebalance, and a walkthrough of each of the three use cases against the design. Diagrams as ASCII
   or Mermaid. The reader is deciding whether to adopt the library.

2. `docs/design/10-specification.md`. The normative specification. Every requirement carries a
   stable identifier (`PLACE-001`, `FAIL-014`, `TOPO-007`) and an RFC 2119 keyword. Interfaces in
   language-neutral pseudocode, never in Java. The reader is writing an implementation, and this is
   the document the conformance suite tests against. Requirement identifiers are the join key
   between the specification and the tests.

3. `docs/design/20-topology-format.md`, plus a schema file. The canonical topology document format,
   with a machine-readable schema, versioning rules, validation rules, and worked examples for each
   of the three use cases.

4. `docs/design/30-conformance.md`, plus `conformance/`. The conformance suite design and a starter
   set of real vectors. This is the mechanism that keeps the Java, Go, Rust and Python ports honest,
   so it is a first-class deliverable rather than an appendix. It covers:
   - Golden vectors: language-neutral data files of topology, key, expected ordered node list and
     expected epoch. Include the adversarial cases: empty topology, single node, replica count
     greater than node count, all nodes in one failure domain, duplicate node identifiers, and
     colliding keys.
   - Property tests: determinism, minimal movement under add and remove, balance within a stated
     bound, replica distinctness, failure-domain spread, monotonic epochs, and idempotent handoff
     steps. Each property is stated formally enough to implement.
   - Simulation scenarios: seeded, deterministic, reproducible cluster simulations for failover and
     rebalancing, in a data-driven scenario format so every language runs the same scenarios.
     Include the hard ones: a node dies mid-migration, a topology rolls back, a caller is three
     epochs stale, and half the cluster sees a different topology.
   - The rule by which a new port declares conformance, and the conformance levels that exist (core
     against optional feature sets).

   Every golden vector is computed by a reference implementation, never authored by hand. A vector
   holding an expected ordering that was reasoned to rather than executed is worse than no vector,
   because it creates a suite no correct implementation passes and every implementer assumes the
   fault is theirs. The reference implementation's hash is verified against its algorithm's
   published test vectors before any vector is generated, and the generator is committed beside the
   vectors it produced.

5. `docs/design/40-java-binding.md`. Java is the first implementation. Sketch how the
   language-neutral specification lands in Java: package layout, the public interface set, the
   minimum JDK version, dependency policy (near-zero is the target), thread-safety contracts, and
   how the conformance vectors are driven from JUnit. This is the binding, not the implementation.
   The group is `com.codeheadsystems`, every artifact name starts with `sharder`, and the top-level
   package is `com.codeheadsystems.sharder`.

6. `docs/design/adr/NNNN-<slug>.md`. One record per significant decision above, with Context,
   Decision, Consequences and Alternatives sections. Terse is fine. These exist so nobody
   relitigates a decision in six months without the reasons, and they are the only documents in the
   corpus where argument is the content.

7. `docs/design/90-open-questions.md`. Everything unresolved, each with a recommended default and
   what evidence would settle it.

8. `docs/design/99-roadmap.md`. A staged plan. What lands in v0.1 to be useful, what is deferred,
   and the one-way doors: the hash function, the topology format, and the error taxonomy.

9. `docs/README.md`. The entry point that routes a reader to the right document. Routing belongs
   here and nowhere else, because no document under `docs/` explains its own existence.

## Constraints

- Language-agnostic means it. No Java types, no JVM assumptions, and no language-specific
  concurrency primitives anywhere except `docs/design/40-java-binding.md`. A construct that cannot
  be expressed in C, Go, Rust, Python and Java alike does not belong in the core specification.
- Determinism is a hard requirement. Two implementations in two languages, given the same topology
  and key, produce the same ordered node list, always. This constrains hash choice, integer width,
  rounding, sort stability and tie-breaking. Tie-breaking is specified explicitly everywhere
  ordering could otherwise be ambiguous.
- For the storage use case, a wrong routing decision can mean data loss. The specification is
  explicit about which guarantees are real and which are best-effort.
- A small, sharp core with well-defined extension points, rather than a framework. An integrator who
  wants only to hash a tenant identifier to one of five clusters does not pay for the migration
  state machine.
- Prior art is named in the decision records, with what is borrowed: Dynamo preference lists,
  Cassandra vnodes and token ranges, Redis Cluster hash slots, Kafka partition assignment, Envoy
  ring hash, Maglev and outlier detection, Ceph CRUSH failure domains, and rendezvous hashing. Note
  where the design diverges.
- A performance or balance property is stated as a bound a test can check, not as an adjective.

## Anti-goals

- No implementation code beyond interface sketches and pseudocode.
- No consensus protocol.
- No single giant document. The file split above is part of the deliverable.
- No deferring the hard parts (rebalancing, split-brain, stale callers) to future work. Those are
  the reason this library is worth building.
- No vague sentence standing in for an unresolved question. It goes in the register.

## Working order

Start with the glossary and the three use-case walkthroughs, because the vocabulary decisions
constrain everything downstream. Then the topology model, then placement, then failover, then the
change and rebalance lifecycle, then conformance. Write each decision record as the decision is
made, not at the end.

The report that closes the task names which parts of the design are solid, which are thinner than
they should be, and what wants reviewing first. That assessment belongs in the report and in
`docs/design/90-open-questions.md`, not in the prose of the other documents.
