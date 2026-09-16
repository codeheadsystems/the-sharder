# Architecture overview

The sharder library answers one question: given a key and a view of the world, which nodes handle
that key, in what order, and what happens when those nodes are unavailable. The library computes
routing decisions and sequences ownership changes. It moves no data, elects no leader, opens no
connection, and speaks no wire protocol.

The library is embedded in an application. It consumes a topology published by an external
authority, exposes a routing call over that topology, and exposes a coordination surface for the
period during which ownership of a shard moves from one node to another.

## Glossary

The corpus uses these terms as defined here and does not redefine them in passing.

- **key**. The octet sequence a caller presents to a routing call. The library treats a key as
  opaque and applies no character encoding, no normalisation, and no case folding of its own.
- **routing key**. The octet sequence a placement strategy actually hashes, derived from the key by
  the key transform. Where no key transform is configured, the routing key is the key.
- **key transform**. A configured, byte-level function from key to routing key, used to co-locate
  keys that share a prefix or a bracketed tag.
- **keyspace**. The set of all octet sequences a topology accepts as keys.
- **shard**. The unit of ownership. A shard is the smallest extent of the keyspace that the sharder
  library names, assigns to nodes, and moves between nodes. Under a given topology a key belongs to
  at most one shard, and to none where a `directory` table matches no entry for it. Under
  `rendezvous` a shard's extent is one routing key and its identifier is that key's octets in
  lowercase hexadecimal, so the strategy enumerates no shard extents.
- **shard identifier**. The stable name of a shard within a topology, unique across the topology
  document.
- **partition**. A synonym for shard in external literature. The corpus uses shard.
- **slot**. A shard produced by dividing the keyspace into a fixed count of numbered parts by
  modular arithmetic over the key hash.
- **range**. A shard whose extent is a half-open interval of the keyspace under unsigned bytewise
  ordering.
- **token**. A 64-bit position on the ring, owned by a node, that terminates one token range. Where
  two nodes derive the same position, both entries stand and the lower node identity is walked
  first.
- **token range**. The half-open interval of hash space ending at a token and beginning at the
  preceding token in ascending order, wrapping at the top of the space. Under the `ring` strategy a
  token range is a shard.
- **node**. A member of a topology that can own shards and serve keys. A node is an addressable unit
  of capacity and failure, not a process, a host, or a container.
- **node identity**. The `id` field of a node, a non-empty string that is unique within a topology
  and stable across epochs. Node identity is the only node attribute a placement strategy hashes;
  weight, administrative state, and the failure domain path shape the strategy's input without
  entering its hash. Reusing an identity for a different node is a topology authoring defect.
- **address**. An opaque string carried on a node for the caller's benefit. Placement never reads
  it.
- **replica**. A node that holds or serves a copy of a shard. The first replica in a preference list
  is the primary; the rest are secondaries.
- **primary**. The first replica of a shard in its preference list.
- **replication factor**. The number of distinct replicas a shard is placed on, written `n`. An
  override entry's `factor` supersedes the document-level factor for the keys that entry matches.
- **preference list**. The ordered list of nodes for a key, produced by applying distinctness,
  failure domain spread, and replication factor to the candidate ordering. The first `r` entries are
  the replicas, where `r` is the achieved replica count and is at most `n`; the entries after them
  are the deterministic fallback tail.
- **candidate ordering**. The total ordering over eligible nodes that a placement strategy produces
  for a routing key, before replication rules are applied.
- **fallback tail**. The portion of a preference list beyond position `r`, the achieved replica
  count, walked when a replica is unreachable. Under a replication shortfall `r` is below `n`, so
  the tail begins earlier than the replication factor alone would place it.
- **placement strategy**. The named, configured function from routing key and node set to candidate
  ordering. The core strategies are `ring`, `rendezvous`, `slot`, `range`, and `directory`.
- **strategy configuration**. The `strategy` object of a topology document, whose `kind` field
  selects the strategy and whose remaining fields configure it.
- **rendezvous hashing**. Highest-random-weight placement, where each node scores the routing key
  and the ordering is by descending score.
- **ring**. Consistent hashing placement, where nodes own tokens on a 64-bit circle and a key is
  owned by the first token at or above its hash.
- **virtual node**. One of several tokens or scoring slots that a single node contributes, used to
  smooth distribution and to express weight. Abbreviated vnode.
- **weight**. A non-negative integer expressing a node's share of capacity relative to its peers, in
  weight units. A weight of zero places no keys on the node.
- **weight unit**. The abstract unit of `weight`. Weight units have no dimension and are meaningful
  only in ratio to the weights of other nodes in the same topology.
- **failure domain**. A set of nodes expected to fail together, such as a rack, an availability
  zone, or a region.
- **domain level**. A named tier of the failure domain hierarchy, declared once per topology. Levels
  are ordered from coarsest to finest.
- **failure domain path**. The tuple of a node's domain identifiers, one per declared level, from
  coarsest to finest. Two nodes share a failure domain at a level when their paths agree at that
  level and at every coarser level.
- **region**, **zone**, **rack**. Conventional names for domain levels. The library attaches no
  meaning to them beyond their position in the declared level order.
- **spread**. The requirement that the replicas of a shard occupy distinct failure domains at a
  named level.
- **degradation order**. The sequence in which spread requirements are relaxed when no placement
  satisfies all of them.
- **topology**. The complete view of the world that the library routes against: the node set, their
  weights and domains, the placement strategy, the replication rules, and the epoch.
- **topology document**. The canonical serialised form of a topology, as specified in
  [`20-topology-format.md`](20-topology-format.md). The topology document is the interchange format
  between implementations and the input format for conformance vectors.
- **canonical form**. The single byte sequence a topology document reduces to under the
  canonicalisation rules, used for digesting and for comparison.
- **topology digest**. The SHA-256 digest of the canonical form of a topology document, in lowercase
  hexadecimal.
- **topology identifier**. The `topologyId` field, a stable name for a sequence of topologies over
  the same cluster. Epochs are ordered within one topology identifier and are incomparable across
  two.
- **epoch**. A non-negative integer no greater than 9007199254740991 that versions a topology within
  a topology identifier, assigned by the topology authority and strictly increasing.
- **fencing token**. The pair of topology identifier and epoch that accompanies a request, allowing
  a recipient to detect that the sender routed against a different view of the world.
- **topology authority**. The external component that decides topology content and assigns epochs.
  The authority is outside the library.
- **topology provider**. The plugin, conforming to the provider contract, that delivers topology
  documents from an authority to the library. Abbreviated provider.
- **topology snapshot**. An immutable, validated topology held in memory and replaced as a whole.
  Abbreviated snapshot.
- **stale snapshot**. A snapshot the library continues to serve after the provider has failed to
  confirm or refresh it within the configured staleness bound.
- **administrative state**. The lifecycle intent an authority records for a node in the topology
  document: `active`, `joining`, `draining`, or `leaving`. Administrative state is authored, agreed,
  and identical for every caller.
- **placement set**. The nodes eligible for placement at an epoch: those whose administrative state
  is `active` or `draining`.
- **health state**. The runtime liveness classification a caller holds for a node, derived from
  locally observed signals. Health state is observed, caller-local, and may differ between callers.
- **health view**. The mapping from node identity to health state that a routing call consults.
- **attempt sequence**. The health-filtered subsequence of a preference list, in preference list
  order, that one caller attempts. The preference list is agreed between callers and the attempt
  sequence is local to one of them, because health state is local.
- **probation**. The interval during which a node that has returned to a healthy state receives a
  restricted share of traffic before full participation resumes.
- **read affinity**. An explicitly requested reordering of the replicas of a preference list towards
  a named failure domain path, available on a separate read call. It changes neither ownership nor
  the replica set, and the write path keeps the unreordered list.
- **routing decision**. The result of a routing call: the preference list with each entry's role and
  position, the shard the key was selected for, the effective replication factor, the achieved
  replica count, and the fencing token of the snapshot used.
- **explain record**. The structured account of how a routing decision was reached, listing the
  routing key, the matched override, the strategy inputs, the candidate ordering, and every node
  excluded with the reason for exclusion.
- **override**. A topology-level rule that matches keys and either pins them to an explicit node
  list or constrains the node set the strategy may choose from.
- **pin**. An override that replaces the candidate ordering with an explicit ordered node list.
- **constraint**. An override that restricts the eligible node set by domain or tag before the
  strategy runs.
- **directory**. An exhaustive override table, in which a key with no matching entry has no route.
- **caller**. The application that embeds the library.
- **integrator**. The person who embeds the library in an application and implements its extension
  points.
- **operator**. The person who runs the resulting system and edits topologies.
- **rebalance**. The whole process of moving from one epoch to the next, including the ownership
  delta and every handoff it implies.
- **ownership delta**. The set of shards whose replica set differs between two epochs, with the
  nodes gained and lost for each.
- **migration**. The movement of one shard's contents from its former replicas to its new ones.
- **handoff**. The coordinated protocol by which one shard's ownership passes from a source node to
  a destination node, sequenced by the library and executed by the integrator's movement hooks.
- **movement hook**. An integrator-supplied callback that performs one step of a handoff, such as
  copying a shard's contents or verifying a copy.
- **handoff coordinator**. The component that drives a handoff through its states. Abbreviated
  coordinator.
- **cutover**. The handoff state in which the source quiesces and the cutover record is committed,
  after a grace window where the hooks are advisory. The cutover instant is the transition out of
  it, from `cutover` to `verifying`, at which the destination becomes the authoritative replica for
  a shard and the source ceases to be.
- **drain**. The administrative act of marking a node so that the authority moves its shards away
  before the node leaves the topology. A draining node continues to own and serve its shards until a
  later epoch reassigns them.
- **hinted handoff**. The practice of writing to a substitute node while a replica is unavailable
  and replaying to the replica on its return. The library exposes it as a hook and implements no
  part of it.
- **minimal movement**. The bound on how many keys change owner when a node is added to or removed
  from a topology.
- **balance**. The spread of keys across nodes relative to their weights, measured as the ratio of a
  node's observed share to its weighted expected share.
- **hot shard**. A shard receiving a share of traffic far above its share of the keyspace.
- **conformance vector**. A language-neutral data file pairing inputs with the exact output every
  conforming implementation produces.

## Component model

Seven components make up the library, of which three are extension points that an integrator
implements and four are internal.

```
   +--------------------------+
   |   topology authority     |   external: control plane, etcd, a file, a human
   +------------+-------------+
                | topology document
   +------------v-------------+
   |  topology provider (SPI) |   pull: load()   push: watch()
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
  publish snapshot N+1 (atomic swap; in-flight calls finish on N)
           |
           v
  ownership delta between N and N+1, published with an event
           |
           v
  the integrator reads the delta and decides whether to migrate
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
```

Installing a snapshot publishes the ownership delta and an event, and starts nothing. A migration
begins when the integrator calls `plan`, and a plan may target a snapshot that has been validated
and not installed, which is how a handoff is prepared ahead of the epoch that will route to it.

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

Staleness is tolerated by configuration: the snapshot in force continues to serve when the provider
is unreachable, and the staleness policy that refuses to route against an old snapshot is not the
default. Cache callers carry the fencing token but need not act on it.

## Conflicting requirements

The three use cases pull against each other in four places, and the library resolves each by
configuration rather than by a single compromise. The reasoning is in
[`adr/0002-placement-strategy-set.md`](adr/0002-placement-strategy-set.md) and
[`adr/0004-topology-provider-contract.md`](adr/0004-topology-provider-contract.md).

- Addressable shards against minimal key movement. The `ring`, `slot`, and `range` strategies name
  shards and support handoff. The `rendezvous` strategy moves fewer keys than any of them, and its
  shard is the key, so bulk handoff is not available under it.
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
