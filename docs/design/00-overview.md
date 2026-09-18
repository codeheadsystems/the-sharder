# Architecture overview

The sharder library answers one question: given a key and a view of the world, which nodes handle
that key, in what order, and what happens when those nodes are unavailable. The library computes
routing decisions and sequences ownership changes. It moves no data, elects no leader, opens no
connection, and speaks no wire protocol.

The library is embedded in an application. It consumes a topology published by an external
authority, exposes a routing call over that topology, and exposes a coordination surface for the
period during which ownership of a shard moves from one node to another.

Status: the library is designed and not implemented. This document describes the shape of that
design, not the behaviour of any running code.

The vocabulary every document here uses is defined in [`05-glossary.md`](05-glossary.md), and a term
below carries the meaning that document gives it.

## Component model

Seven components make up the library, of which three are extension points that an integrator
implements and four are internal.

```
   +--------------------------+
   |   topology authority     |   external: control plane, etcd, a file, a human
   +------------+-------------+
                | topology document
   +------------v-------------+
   |  topology provider (SPI) |   pull: load(known)   push: watch()
   +------------+-------------+
                |
   +------------v-------------+
   |  loader and validator    |   schema, semantics, monotonicity
   +------------+-------------+
                | immutable snapshot, published as a whole
   +------------v-------------+
   |  topology snapshot       |
   +------------+-------------+
                |                       +-----------------------+
   +------------v-------------+         |  health view (SPI)    |<-- signals from the caller
   |  router                  |<--------+-----------------------+
   +------------+-------------+
      key transform, overrides, strategy, preference list, health filter
                | routing decision, metrics, events; explain record on request
   +------------v-------------+
   |  caller                  |
   +--------------------------+

   +--------------------------+        +-----------------------+
   |  handoff coordinator     +------->+  movement hooks (SPI) |
   +--------------------------+        +-----------------------+
        reads two snapshots                 integrator moves the bytes
```

The three extension points are the topology provider, the health view, and the movement hooks. An
integrator who routes a tenant identifier to one of five clusters implements none of them beyond a
static file provider, and never instantiates the handoff coordinator.

Four further hooks are optional and carry a default of doing nothing: a pressure gauge and a shard
metrics source for the handoff coordinator, a hint observer for substitution notices, and a metrics
registry and an event sink for reporting.

## Route call data flow

A routing call reads one snapshot and returns one decision. The snapshot in force at the start of
the call serves the whole call, so a decision is never assembled from two epochs.

```
  key
   |
   v
 key transform -------------------------------> routing key
                                                    |
                                  +-----------------v------------------+
                                  |  override match                    |
                                  |  exact, then longest prefix        |
                                  +---+---------------------------+----+
                                pin  |                            | none or constraint
                                     |                 +----------v----------+
                                     |                 |  eligible node set  |
                                     |                 |  placement set      |
                                     |                 |  minus constraint   |
                                     |                 +----------+----------+
                                     |                            |
                                     |                 +----------v----------+
                                     |                 |  placement strategy |
                                     |                 +----------+----------+
                                     |                            |
                                     +------------+---------------+
                                                  |
                                     +------------v-------------+
                                     |  candidate ordering      |  total order
                                     +------------+-------------+
                                                  |
                                     +------------v-------------+
                                     |  preference list builder |  distinctness, spread, n
                                     +------------+-------------+
                                                  |
                                     +------------v-------------+
                                     |  health filter           |  skips, never reorders
                                     +------------+-------------+
                                                  |
                                                  v
                             routing decision (explain record on request)
```

The health filter removes nodes from the walk and never changes the order of the nodes that remain.
Two callers with different health views therefore attempt different nodes but agree on the
preference list and on which node owns the shard.

## Rebalance data flow

```
  authority publishes epoch N+1
           |
           v
  provider delivers document
           |
           v
  +--------+--------+   invalid    +------------------+
  |  validate       +------------->+  keep snapshot N |
  +--------+--------+              +------------------+
           | valid
           v
  +--------+--------+  epoch <= N  +------------------+
  |  monotonicity   +------------->+  keep snapshot N |
  +--------+--------+              +------------------+
           | epoch > N
           v
  publish snapshot N+1 (atomic swap; in-flight calls finish on N), emit topology.installed
           |
           v
  the integrator decides whether to migrate; the library computes nothing further
           |
           v
  the integrator asks for the ownership delta between N and N+1, and pays for it there
           |
           v
  the integrator calls plan(from, to, hooks, policy) -> migration plan
           |
           v
  the integrator calls step() repeatedly; each call advances one handoff by one hook
           |
           v
  per moved shard:
     planned -> preparing -> transferring -> catchingUp -> cutover -> verifying -> cleanup
             -> complete

     abort, admitted while no cutover record exists:
        planned -> aborted
        preparing, transferring, catchingUp, cutover -> aborting -> aborted

     operator action required:
        cutover, verifying, cleanup, aborting -> failed

  a newer epoch arrives while the plan is in flight:
     the integrator calls onSnapshotInstalled(), which marks the plan rebase pending
     and starts nothing; step() then admits no new handoff and no new cutover
             |
             v
     the integrator calls rebase(to), and pays the per-shard placement there
             |
             v
     per handoff, planned through catchingUp:
        the shard, source, and destination still hold  -> rebased, state unchanged
        they do not                                    -> aborted, compensation runs
     per handoff, cutover onwards: unchanged, finishing under the epoch it began

  the cutover outcome was never established:
     the integrator calls reobserve(id), which calls observe() once
        failed(undetermined) -> verifying, aborting, cutover, transferring, preparing
        the store is still silent -> the handoff stays in failed(undetermined)
```

Installing a snapshot emits an event and starts nothing. The ownership delta is an operation the
integrator calls, not a product of installation, because walking every shard under both snapshots
costs a preference list evaluation per shard per snapshot and a caller that never migrates never
needs one. A migration begins when the integrator calls `plan`, and a plan may target a snapshot
that has been validated and not installed, which is how a handoff is prepared ahead of the epoch
that will route to it.

A rebalance large enough to matter outlives several epochs, because a fleet publishes an epoch
whenever a node dies, is drained, or changes weight. A plan therefore follows the topology rather
than pinning to the pair of epochs it was built from. A newer epoch marks the plan rebase pending,
which starts no work and holds the plan short of any new cutover, and a rebase moves the handoffs
whose shard, source, and destination still hold onto the newer snapshot while aborting the rest.
The two calls are separated so that the cost of evaluating placement per shard falls where the
integrator asks for it.

A provider that is unreachable produces no branch of its own: the snapshot in force stays in force,
an event is emitted, and the snapshot is marked stale once the staleness bound passes.

## Storage node placement

A Dynamo-style store partitions a keyspace across storage nodes with `n` replicas per shard, sends
writes to a preference list, and moves data when membership changes.

The topology declares `strategy.kind` of `ring` with derived tokens, `domainLevels` of `["zone",
"rack"]`, and `replication.factor` of 3 with spread at `zone`. Each node carries a weight in
proportion to its disk capacity, and the ring gives it `weight * tokensPerWeightUnit` tokens.

A routing call for a key hashes the key to a 64-bit position, finds the first token at or above that
position, and takes the token's owner as the first candidate. The candidate ordering continues
clockwise around the ring. The preference list builder walks that ordering, admitting a node only if
its identity is not already present and its zone is not already used, until three replicas are
chosen; the remainder of the ordering is the fallback tail.

Ownership is addressable because the shard is the token range rather than the key. When a node
joins, the ownership delta names the token ranges whose replica set changed, and the integrator
drives one handoff per range through the coordinator. The fencing token in each routing decision
lets a storage node reject a write computed against an epoch older than its own.

## Tenant cluster routing

Tenant routing sends a request carrying a tenant identifier to a service cluster, with some tenants
pinned by contract or by residency law, and frequently with no replication at all.

The topology declares `strategy.kind` of `rendezvous`, `domainLevels` of `["region"]`, and
`replication.factor` of 1. Each node is a service cluster rather than a single process. Contractual
pinning is an override with an explicit node list. Residency is an override with a constraint on the
`region` domain, which restricts the eligible node set before the strategy runs, so tenants subject
to residency stay balanced across the clusters that are legal for them.

A routing call returns a single node followed by a fallback tail. The caller attempts the head, and
on failure walks the tail, which is the same tail every other caller computes.

Where a tenant table is exhaustive, `strategy.kind` of `directory` names every tenant explicitly and
a key with no entry has no route.

## Cache affinity routing

Cache routing is sticky routing where the loss of a node disturbs as few keys as possible and a
briefly stale view of the world is tolerable.

The topology declares `strategy.kind` of `rendezvous` and `replication.factor` of 1 or 2. Rendezvous
placement moves only the keys of a departed node, and no others, which is the minimum any placement
function can achieve.

That movement property is bought with a routing cost that grows with the node set rather than with
the key. A rendezvous routing call scores every eligible node at every one of its virtual node
indices before any candidate is known, so its cost is the summed virtual node count, and that sum is
the number a cache topology is sized against. The cost of every strategy is tabulated in
[`10-specification.md`](10-specification.md#placement-cost-model), and the library emits
`sharder.topology.rendezvous_large` at publication where the sum crosses
`rendezvousWarnVirtualNodes`. A cache whose node set is large enough to cross it is placed by `ring`
instead, which costs one hash evaluation and a search per routing call at any node count, at the
price of a coarser movement bound.

Staleness is tolerated by configuration: the snapshot in force continues to serve when the provider
is unreachable, and the staleness policy that refuses to route against an old snapshot is not the
default. Cache callers carry the fencing token but need not act on it.

## Conflicting requirements

The three use cases pull against each other in four places, and the library resolves each by
configuration rather than by a single compromise. The reasoning is in
[`adr/0002-placement-strategy-set.md`](adr/0002-placement-strategy-set.md) and
[`adr/0004-topology-provider-contract.md`](adr/0004-topology-provider-contract.md).

- Addressable shards against minimal key movement. The `ring`, `slot`, and `directory` strategies
  name shards and support handoff. The `rendezvous` strategy moves fewer keys than any of them, and
  its shard is the key, so bulk handoff is not available under it.
- Deterministic ownership against health-aware routing. The candidate ordering is deterministic and
  identical for every caller. The health view only skips entries, so two callers diverge on
  reachability and never on ownership. Read affinity is the one reordering the library performs, it
  is requested explicitly on a separate read call, and it leaves ownership and the replica set
  unchanged.
- Balance against pinning. A pinned key is exempt from the balance bound and from the minimal
  movement bound. A constrained key keeps both bounds within its constrained node set.
- Serving a stale snapshot against refusing one. The snapshot in force keeps serving by default, and
  the fencing token lets a storage recipient refuse what the router was willing to compute.

## Out of scope

The library does not provide, and names here, the following.

- Consensus. The library implements no Raft and no Paxos, elects no leader, and holds no election.
  It consumes an externally agreed topology and enforces monotonicity over it.
- Transport, remote procedure call, connection pooling, wire-level retry, and the serialisation of
  caller payloads.
- Storage engines, replication of data content, conflict resolution, and vector clocks.
- Service discovery mechanics, health probing transports, and the production of liveness signals.
  The library owns the health state machine and consumes signals; it does not gather them.
- Data movement. The handoff coordinator sequences a migration and calls movement hooks; the bytes
  move through the integrator's code.
- Clocks and scheduling. The library reads a monotonic time source supplied by the binding and
  starts no thread that a binding has not given it.
