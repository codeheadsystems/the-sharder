# 0048. Conditional fetch in the provider contract

Status: accepted. Date: 2026-09-17.

## Context

`CORE-090` polls a pull-only provider every `pollIntervalMillis`, 30 seconds by default, and
`CORE-083` requires every document a provider delivers to run through `TOPO-001` in full: decode,
schema, semantic validation, RFC 8785 canonicalisation, and SHA-256. Only at stage 5 does
`TOPO-061` compare the epoch and the digest against the snapshot in force and find the document
identical to the one already installed.

A topology document grows with the node set. A 5000-node document is roughly 750 KB and a
50000-node document roughly 7.5 MB. Every caller in a fleet pulls the whole of it on every interval,
whether or not the authority has published anything. The per-caller CPU is survivable. The control
plane's egress is not, and it is correlated: it fails for every caller at once, and it fails hardest
at the moment an operator pushes the change that the fleet is polling for.

Every source the contract is meant to cover already answers this question cheaply. An HTTP source
has an entity tag and a last-modified instant, a file has a modification time and a size, etcd has a
revision, and a gRPC watch has a resume token. `load` had nowhere to put any of them and no way to
answer "nothing has changed", so a provider that implemented an entity tag internally still had to
return its cached octets, and the library paid the parse and the digest regardless.

The cost of leaving it is not the 30 seconds of staleness. It is that
[`../99-roadmap.md`](../99-roadmap.md) schedules a third-party control plane adapter as a later
item, written against the provider contract without forking. The first such adapter fixes
`CORE-080`, and every widening after that is a breaking change to a published extension point.

## Decision

`load` takes what the library already knows and may answer that there is nothing to send.

```
load(known: SourceVersion | none) -> Result<Loaded, Error>

Loaded = one of:
    document(document: Document, version: SourceVersion | none)
    unchanged
```

A source version is an opaque octet string the provider chooses, at most 4096 octets, which the
library stores, returns unchanged on the next `load`, and never reads. `CORE-084` states the
opacity: the library does not parse it, order it, compare one against another, or let it reach an
acceptance decision, and `TOPO-051` still names the epoch and the `topologyId` as the only values
acceptance reads.

Opacity is what makes one member serve four unrelated sources. An HTTP adapter puts the entity tag
in it and sends `If-None-Match`; where the origin offers no entity tag it puts the last-modified
instant in it and sends `If-Modified-Since`. A file provider puts the modification time and the size
in it and compares both, which catches a rewrite that preserves the timestamp. An etcd adapter puts
the revision in it and re-reads from that revision, which
[`0004`](0004-topology-provider-contract.md) already distinguishes from the epoch: the revision
orders writes to one store and the epoch orders topologies across every store that may hold them. A
typed member naming any one of those would have forced the other three to misuse it, and a member
the library could read would have invited a second ordering beside the epoch.

`CORE-085` fixes which version the library hands back: the one that accompanied the most recent
document the provider delivered and `TOPO-061` accepted. A document that `TOPO-001` abandons or
`TOPO-061` rejects leaves the retained version alone. A rejected document is therefore requested
again on every poll, and the rejection event fires again with it.

`CORE-086` gives `unchanged` the effect of the no-op row of `TOPO-061` and no other: freshness is
refreshed, `topology.unchanged` is emitted, and no stage of the load pipeline runs. Answering
`unchanged` to a `load` that was given no version is a provider defect and is reported as
`providerError` under `ERR-033`, because the library holds no document that answer could refer to.

`CORE-087` keeps conditional fetch optional. A provider that supplies no version is passed none for
ever, is never required to answer `unchanged`, and is never refused, and the library does not
synthesise a version from the document, the digest, or the epoch on its behalf.

`onDocument` carries a version for the same reason `load` returns one. A provider declaring both
models is polled on `reconcileIntervalMillis` under `CORE-092`, and without the version from the
push path that reconciling poll would quote whatever the last poll saw and transfer the whole
document each time.

The new `unchanged` outcome is visible: `OBS-011` adds it to the `topology.documents` outcome set,
so an operator reads whether conditional fetch is working rather than inferring it from a traffic
graph.

## Consequences

A pull-only fleet against a provider that supports conditional fetch transfers a topology document
when the topology changes, and a few octets otherwise. The `TOPO-001` pipeline runs as many times
per caller as the authority publishes, rather than twice a minute.

A provider that cannot support conditional fetch behaves exactly as it did. Both reference providers
of [`0004`](0004-topology-provider-contract.md) are in that position at v0.1, so the change moves no
conformance vector: the suite carries no provider, and nothing a data file can hold observes a
source version.

The retention rule of `CORE-085` costs a full transfer per poll for as long as a source serves a
document the library refuses. That is the intended trade. The alternative, retaining the version of
a rejected document, makes the library quiet and the snapshot look fresh while the authority is
publishing something unusable, which is the failure an operator most needs to see.

A provider now holds state between calls, which a purely stateless `load` did not require. The state
is one octet string and the library supplies it on every call, so a provider that recreates its
connection, or is itself recreated, loses nothing it cannot rebuild from the argument.

The contract widens once, before v0.1, rather than never. `CORE-080` gains one parameter, one result
union, and one type; [`../99-roadmap.md`](../99-roadmap.md) gains the provider contract as a named
one-way door, which it did not carry.

## Alternatives

Handing the provider the fencing token and the digest, as `{ topologyId, epoch, digest }`. Rejected
because none of the four sources can act on it. An HTTP origin cannot turn a digest into an
`If-None-Match`, a file cannot compare one against an inode, and etcd cannot compare one against a
revision. Each adapter would have had to keep its own side table keyed by the epoch, which is the
state the contract was trying to spare it, and the shape would have implied that the library's
notion of identity and the source's are the same notion.

A typed union of the known conditional fetch mechanisms, with an entity tag arm, a timestamp arm,
and a revision arm. Rejected because it is a closed set that is wrong within a release: a resume
token, a generation number, and a content hash are all in use, and each would arrive as a format
change to an extension point rather than as a provider's own business.

Leaving `load` alone and letting a provider return the same octets it returned before, with the
library short-circuiting on a cheap comparison of the octets. Rejected because the transfer is the
cost. The comparison saves the parse and the digest, which are the affordable half, and saves
nothing of the control plane egress, which is the half that fails.

A `since: Epoch` parameter, letting a provider filter by the epoch the caller holds. Rejected
because it makes the epoch a query parameter against the source, which `0004` rejects for the same
reason it rejects provider-supplied versioning: the epoch orders topologies and not the writes of
one store, and a file has no way to answer such a query at all.

Polling less often. Rejected because it trades the control plane's egress for convergence time on
exactly the axis an operator cannot tune per fleet: the interval that makes a 50000-node document
affordable is measured in minutes, and a rebalance then begins minutes after the authority
publishes it.
