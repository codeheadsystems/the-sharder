# 0004. Topology provider contract

Status: accepted. Date: 2026-09-16.

## Context

Topology comes from somewhere: a file on disk, a control plane, etcd, ZooKeeper, Consul, a DynamoDB
table, or a static literal in a test. The library routes against it and does not decide it. Every
one of those sources has a different retrieval model, and only some of them can push.

A provider contract that assumes push excludes a file. A contract that assumes pull wastes the
notification that etcd and ZooKeeper already provide, and leaves a cluster routing against an old
topology for the length of a poll interval. The contract also has to say what happens when the
source misbehaves, because a routing library that stops routing when its configuration source is
briefly unreachable is worse than one that serves a slightly old view.

## Decision

The provider contract carries both models and requires at least one.

```
interface TopologyProvider:
    capabilities() -> { pull: boolean, push: boolean }   # at least one is true
    load() -> Result<TopologyDocument, ProviderError>    # present when pull is true
    watch(sink: TopologySink) -> Subscription            # present when push is true
    close()

interface TopologySink:
    onDocument(document: TopologyDocument)
    onError(error: ProviderError)

interface Subscription:
    cancel()
```

A provider delivers documents and never snapshots. Parsing, schema validation, semantic validation,
monotonicity, and digesting belong to the sharder library, so a provider that reads a corrupt file
cannot place a corrupt topology into service.

A pull-only provider is adapted by polling. The library calls `load` at startup and then every
`pollInterval`, which defaults to 30 seconds, and raises a change notification when the topology
digest differs from the snapshot in force.

A push-only provider is adapted by waiting. The library subscribes at startup and waits up to
`initialTimeout`, which defaults to 10 seconds, for the first document. Until a first valid document
arrives the router has no snapshot and routing calls answer with an unready condition rather than
with an empty preference list.

A provider that offers both is subscribed for currency and polled at a longer `reconcileInterval`,
which defaults to 5 minutes, so that a dropped notification is repaired without operator action.

Two reference providers ship in core. The in-memory provider holds a document supplied
programmatically and pushes on replacement, which is what tests and conformance vectors use. The
static file provider reads a document from a path, pulls on demand, and polls for modification.
Every other source is a third-party adapter and needs no change to the library.

Provider failure behaviour is specified as a closed set.

- Provider unreachable, or `load` returning an error. The snapshot in force stays in force, an error
  event is emitted, and retries back off exponentially from 1 second to a 60 second cap with jitter.
- Document failing validation. The document is rejected whole, the snapshot in force stays in force,
  and a validation event naming the first failing rule is emitted.
- Epoch lower than the snapshot in force. The document is rejected and a stale-topology event is
  emitted.
- Epoch equal with an equal digest. The document is accepted as a no-op and the freshness timestamp
  is refreshed.
- Epoch equal with a different digest. The document is rejected and a conflict event is emitted,
  because a topology authority has reused an epoch.
- `topologyId` different from the one in force. The document is rejected and a conflict event is
  emitted.

Staleness is separate from failure. A snapshot whose freshness timestamp is older than `staleAfter`,
which defaults to unbounded, is marked stale. `stalePolicy` decides what a stale snapshot does:
`serve`, the default, routes against it and reports staleness in the routing decision; `refuse`
answers routing calls with an unready condition.

The router is configured with an expected `topologyId`. Where none is configured, the identifier of
the first accepted document is adopted and every later document is checked against it.

An etcd adapter against this contract, in pseudocode.

```
class EtcdTopologyProvider(client, key):
    capabilities() -> { pull: true, push: true }

    load():
        response = client.get(key)
        if response.empty: return Err(NOT_FOUND)
        remember(response.revision)
        return Ok(response.value)

    watch(sink):
        stream = client.watch(key, fromRevision = lastRevision + 1)
        on event in stream:
            if event.type == PUT:      sink.onDocument(event.value)
            if event.type == DELETE:   sink.onError(NOT_FOUND)
            if event.type == COMPACTED: sink.onDocument(load())
            remember(event.revision)
        on stream failure as e:
            sink.onError(e)          # the library backs off and resubscribes
        return Subscription(stream.cancel)
```

The etcd revision is not the epoch. The epoch comes from the document, because it orders topologies
rather than writes, and because a ZooKeeper adapter, a file, and an etcd cluster have to agree on
the ordering of the same topology sequence.

## Consequences

An adapter author implements one method and declares the other absent. A file provider is a dozen
lines and a control plane adapter is a subscription plus a parse.

A provider cannot reject a topology on the library's behalf, and a provider that filters or repairs
documents is outside the contract. Validation happens in one place.

Serving a stale snapshot by default means a cache keeps working through a control plane outage and a
storage cluster keeps routing against a view that may be wrong. The fencing token makes the second
case safe at the recipient rather than at the router, and an operator who wants the router to refuse
sets `stalePolicy` to `refuse`.

Equal epoch with a different digest is a defect in the authority. The library reports it and holds
its ground rather than choosing between two versions of the same epoch.

## Alternatives

A pull-only contract with polling everywhere. Simpler, one method. Rejected because it caps
convergence at the poll interval, and because sources that push are common enough that discarding
the notification is a real cost during a rebalance.

A push-only contract with the library supplying a polling wrapper for the rest. Rejected because the
wrapper is then the only way to read a file, and because the first read at startup is naturally a
pull.

A provider returning a validated snapshot. Rejected because validation would live in every adapter
and two adapters would validate differently.

Provider-supplied versioning, using the store's revision or modification time as the epoch. Rejected
because it couples the topology's ordering to one store's internals, breaks when the topology is
copied between stores, and offers no meaning when the source is a file.

Failing routing calls when the provider is unreachable. Rejected because it converts a control plane
outage into a data plane outage, which is the opposite of what a routing library is for.
