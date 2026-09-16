# 0002. Placement strategy set

Status: accepted. Date: 2026-09-16.

## Context

The three use cases the sharder library serves want different things from placement. A Dynamo-style
store wants an addressable shard, because a migration moves a shard rather than a key, and it wants
a preference list spread over failure domains. A tenant router wants a small node set, contractual
pinning, and frequently no replication at all. A cache wants the smallest possible key movement when
a node leaves and tolerates a stale view.

No single placement function serves all three. Consistent hashing gives addressable shards and moves
roughly `k/n` keys when one node of `n` leaves, but its balance over a small node set is poor
without many virtual nodes. Rendezvous hashing moves exactly the departed node's keys and nothing
else, which is optimal, but its shard is the key, so there is nothing to name in a handoff. A fixed
slot count gives a control plane an explicit, auditable map at the cost of a fixed shard granularity
chosen up front. Range partitioning is the only option for an ordered keyspace with scans.

The topology document has to encode the configuration of every strategy that ships, so the set is
decided before the format is fixed.

## Decision

Five strategy kinds ship in core: `ring`, `rendezvous`, `slot`, `range`, and `directory`.

| Kind | Shard | Intended use | Movement on node loss |
|---|---|---|---|
| `ring` | token range | storage clusters | the lost node's token ranges |
| `rendezvous` | the key | caches, tenant routing | the lost node's keys only |
| `slot` | slot index | control-plane clusters | as the authority assigns |
| `range` | key interval | ordered keyspaces | as the authority assigns |
| `directory` | matched entry | exhaustive tenant tables | none, the table is explicit |

The `ring` kind carries large node counts at a bounded lookup cost, and `rendezvous` carries small
ones with optimal movement.

Strategy is both a closed core set and a pluggable interface. The five kinds above are named in the
schema, covered by conformance vectors, and implemented identically by every conforming port. An
implementation may additionally register a strategy of its own; a topology naming an unregistered
kind is invalid, and a custom kind is validated against a locally extended schema rather than the
published one. Conformance is claimed over the core kinds only.

The strategy interface, in language-neutral pseudocode.

```
interface PlacementStrategy:
    name() -> string
    validate(config, nodes, domainLevels) -> list<ValidationError>
    prepare(snapshot) -> PreparedPlacement

interface PreparedPlacement:
    shardOf(routingKey: bytes) -> ShardId
    candidates(routingKey: bytes, eligible: NodeSet) -> ordered iterator<NodeId>
    shards() -> iterator<ShardId>
    candidatesForShard(shard: ShardId, eligible: NodeSet) -> ordered iterator<NodeId>
```

`prepare` is a pure function of the snapshot and is called once per snapshot, so a strategy that
benefits from a precomputed structure, such as the ring's sorted token array, builds it there.
`candidates` produces a total ordering lazily, so the preference list builder consumes only the
prefix it needs. `eligible` carries the placement set after any override constraint has been
applied, which is what lets a residency constraint preserve the strategy's balance within the
constrained subset.

The `slot` and `range` kinds with `derived` assignment delegate their candidate ordering to
rendezvous over the shard identifier rather than over the key. One ordering rule therefore covers
three kinds.

Directory lookup is expressed as an exhaustive matcher table. It shares its matching rules with the
override layer, which is described in
[`0009-override-composition.md`](0009-override-composition.md), so the same precedence rule governs
both.

## Consequences

Five kinds is more surface than a single algorithm, and each one carries conformance vectors,
validation rules, and a schema branch. The cost is bounded because three of the five share the
rendezvous ordering rule, and because two of them are lookups over an authored map rather than
algorithms.

A `rendezvous` topology cannot drive the handoff coordinator, since there is no shard to name. A
deployment that starts on rendezvous for cache routing and later needs migration changes strategy,
and a strategy change is a full data movement.

`candidates` returning a lazy ordering means a strategy over a large node set does not pay to order
every node for a preference list of three. A ring implementation walks its sorted token array; a
rendezvous implementation maintains a bounded heap.

Registering a custom strategy places its author outside the conformance suite, and two ports of a
custom strategy are only as compatible as its author makes them.

## Alternatives

Jump consistent hash. Minimal code, excellent balance, no memory. Rejected because it maps a key to
a bucket index in `0..n-1` and offers no way to remove a node other than the last, no weights, and
no failure domain awareness. It solves the wrong problem for a topology whose membership changes
arbitrarily.

Maglev hashing. Even balance and a table lookup per key. Rejected for core because the lookup table
is sized from the node count and rebuilt on every change, and because its disruption on node loss
exceeds rendezvous. It remains a plausible future kind.

CRUSH as a placement algorithm. Rejected as an algorithm because its bucket types and its tunables
are a large specification surface. Its hierarchical failure domain model is borrowed, and is
described in [`0006-failure-domain-model.md`](0006-failure-domain-model.md).

Consistent hashing without virtual nodes. Rejected because balance over fewer than a few hundred
nodes is unacceptable and because node removal cannot be compensated.

Kafka-style partition assignment, where a fixed partition count is assigned to members by a
coordinator on rebalance. Borrowed as the `slot` kind with explicit assignment, which is the same
shape with the coordinator outside the library.

Cassandra vnodes and token ranges. Borrowed directly as the `ring` kind. The design diverges by
deriving tokens from the node identity rather than storing randomly generated tokens in gossip, so
that a topology document is reproducible from node identities and weights alone, with explicit
tokens available for a cluster that has already chosen them.

Envoy ring hash and its `minimum_ring_size`. Borrowed as the sizing fields of the `ring` kind. The
design diverges by expressing ring size per node from weight rather than as a cluster-wide minimum,
so that sizing is stable as nodes join.

A single strategy with modes, rather than five kinds. Rejected because the configuration of a range
map and the configuration of a ring share no fields, and a union of all of them validated by
convention is worse than five schema branches.
