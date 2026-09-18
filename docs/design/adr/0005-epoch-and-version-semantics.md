# 0005. Epoch and version semantics

Status: accepted, with the acceptance rows ordered by
[`0076`](0076-ordered-rows-in-a-precedence-table.md). Date: 2026-09-16.

The monotonicity rule and the acceptance outcomes are unchanged. `TOPO-061` now states that its
rows are evaluated in order, and carries the identity row and the `minEpoch` row above the row
for a first document, which is the order a document under a foreign identifier needs.

## Context

Several callers route against the same cluster and none of them changes topology at the same
instant. During a change some callers hold the old view and some hold the new one. For a cache that
is an efficiency problem; for a storage cluster, two callers disagreeing about who owns a shard is a
correctness problem, and the recipient of a request has to be able to tell that the sender computed
its decision against a different world.

The library does not run consensus. Agreement on what the topology is happens outside it, in a
control plane, in etcd, or in a human editing a file. What the sharder library owns is the ordering
of the topologies it is given and the token that lets a recipient detect disagreement.

## Decision

A topology is versioned by the pair of `topologyId` and `epoch`.

`topologyId` names a sequence of topologies over one cluster. Epochs are ordered within one
identifier and are incomparable across two, so a document whose identifier differs from the one in
force is rejected rather than compared.

`epoch` is a non-negative integer, assigned by the topology authority, strictly increasing within a
`topologyId`. The library enforces monotonicity and nothing more: it accepts a document whose epoch
exceeds the snapshot in force, treats an equal epoch with an equal digest as a no-op, and rejects
everything else. It never assigns an epoch, never increments one, and never infers one from a
timestamp or a store revision.

The topology digest is the SHA-256 of the canonical form of the document. It identifies content, not
order. Two documents with the same identifier and epoch are the same topology exactly when their
digests match, and an equal epoch with a differing digest is reported as an authority defect.

The fencing token is the pair of `topologyId` and `epoch`, carried in every routing decision. A
caller passes it with the request it routes, and a recipient compares it against its own epoch. The
comparison rules for a recipient are specified in [`10-specification.md`](../10-specification.md).

Monotonicity is enforced within one library instance, from the first document it accepts. A process
that restarts has no memory of the epoch it last saw, so a configuration value supplies a floor: an
optional `minEpoch` below which no document is accepted, which an integrator persists where a
restart must not route backwards.

A rollback is expressed as a new higher epoch carrying the earlier content. There is no mechanism
for lowering an epoch, and a topology authority that wishes to undo epoch 42 publishes epoch 43 with
the content of epoch 41.

`formatVersion` is orthogonal to the epoch. It versions the document schema and is described in
[`20-topology-format.md`](../20-topology-format.md).

## Consequences

The authority is responsible for assigning epochs correctly, and an authority that reuses an epoch
for different content produces a cluster in which some callers hold one version and some hold
another with no way to converge. The library detects this and reports it; it cannot repair it.

Rollback costs an epoch. An operator undoing a change sees the epoch counter continue upwards, which
makes the epoch a reliable ordering and an unreliable indicator of how many distinct topologies have
existed.

Monotonicity resetting on restart is a real gap for a storage deployment, since a restarted caller
accepts whatever the provider offers first, including an old document from a lagging replica of the
source. `minEpoch` closes it, at the cost of an integrator persisting a value.

Comparing epochs across topology identifiers is impossible by construction, so splitting one cluster
into two under new identifiers is safe, and renaming a cluster's identifier is a full restart of the
ordering.

## Alternatives

Content digest as the only version. Rejected because a digest gives equality and not order, so a
caller could not tell an old topology from a new one and the fencing token would carry no
information.

Wall-clock timestamps as epochs. Rejected because clocks move backwards, because two authorities
with unsynchronised clocks produce an ordering that is not an ordering, and because the value would
have to be compared with a tolerance.

Vector clocks over the node set. Rejected because the library is not the thing that decides
topology, so it has nothing to attach a vector to, and because comparing concurrent topologies would
require a merge rule that is exactly the consensus the library does not implement.

The store's own revision, such as an etcd `mod_revision` or a ZooKeeper `zxid`. Rejected in
[`0004-topology-provider-contract.md`](0004-topology-provider-contract.md), because it ties the
ordering to one store.

Accepting a lower epoch when the digest matches a previously seen topology. Rejected because it
makes the accepted sequence non-monotonic, which defeats a recipient comparing fencing tokens.

Enforcing monotonicity across restarts by persisting the last epoch inside the library. Rejected
because it would require the library to own durable storage, which is outside its scope.
