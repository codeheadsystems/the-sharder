# Normative specification

This document specifies the behaviour every conforming implementation of the sharder library
reproduces: how a key becomes a routing key, how a routing key becomes an ordered list of nodes, how
that list degrades when nodes are unhealthy or failure domains are scarce, how a topology document
becomes a snapshot, how ownership of a shard moves from one node to another, and what the library
reports while it does any of it.

Its reader is an implementer writing a port, an integrator deciding what the library guarantees, and
a conformance author turning a requirement into a test. A term carries the meaning the glossary in
[`00-overview.md`](00-overview.md) gives it. A quoted document member names a member of the format
in [`20-topology-format.md`](20-topology-format.md).

## Conventions

### Requirement keywords

The keywords `MUST`, `MUST NOT`, `SHOULD`, `SHOULD NOT`, and `MAY` carry the meanings given in
RFC 2119, and carry them only where they appear in capitals. `MUST` and `MUST NOT` mark an absolute
requirement of conformance. `SHOULD` and `SHOULD NOT` mark a requirement an implementation departs
from only for a stated reason whose consequences it has weighed. `MAY` marks a genuine option, and
an implementation that takes the option and one that declines it are equally conforming.

A statement without one of these keywords is a definition, an input to a requirement that carries
one, or an explanation of a term.

### Requirement identifiers

Every requirement carries an identifier of the form `PREFIX-NNN`, where the prefix names the subject
area and `NNN` is a three-digit number. An identifier names one requirement permanently. A
requirement is withdrawn by marking it withdrawn and never by reassigning its identifier, and a new
requirement takes the next free number in its group rather than a number a neighbour has vacated.

Numbers are assigned in groups of ten within a prefix, so that a requirement added to an existing
group keeps the group contiguous. A gap in the numbering carries no meaning.

A reference of the form `PREFIX-*` names every requirement under that prefix.

### Requirement prefixes

The section named in each row is a level-two heading of this document.

| Prefix | Covers | Section |
|---|---|---|
| `CORE` | shared types, the provider contract, visibility, thread ownership | Core model |
| `HASH` | the keyed hash, its framing, and its domain tags | Core model |
| `KEY` | key opacity and the key transforms | Routing keys and placement |
| `PLACE` | rules common to every strategy | Routing keys and placement |
| `RING` | tokens, ring order, and the ring walk | Routing keys and placement |
| `RV` | rendezvous scores and ordering | Routing keys and placement |
| `SLOT` | slot index and slot assignment | Routing keys and placement |
| `RANGE` | bound comparison and covering range | Routing keys and placement |
| `DIR` | directory evaluation and no-match | Routing keys and placement |
| `OVR` | override matching, pins, constraints | Routing keys and placement |
| `PROP` | determinism, movement, balance bounds | Routing keys and placement |
| `REPL` | the factor and the preference list | Replication and failover |
| `SPREAD` | failure domain spread and degradation | Replication and failover |
| `HEALTH` | health states, signals, ejection | Replication and failover |
| `FAIL` | attempt sequence, depth, retry budgets | Replication and failover |
| `READ` | read routing and read affinity | Replication and failover |
| `TOPO` | load pipeline, snapshots, ownership delta | Topology change and rebalancing |
| `FENCE` | fencing tokens and recipient verdicts | Topology change and rebalancing |
| `MOVE` | handoff states, hooks, coordination | Topology change and rebalancing |
| `RATE` | migration concurrency and backpressure | Topology change and rebalancing |
| `SPLIT` | range splits, merges, and lineage | Topology change and rebalancing |
| `ERR` | the closed set of failure conditions | Error taxonomy |
| `OBS` | metrics, events, skew, the explain API | Observability |
| `CFG` | every setting, default, and meaning | Configuration surface |
| `SEC` | adversarial keys, seed, disclosure | Security and multi-tenancy |

### Notation

Interfaces, records, and algorithms are given in pseudocode. The pseudocode names a shape and an
ordering; it is not the signature of any binding, and a binding renders it in the idiom of its
language without changing the shape.

`u32` and `u64` are unsigned integers of 32 and 64 bits. `bytes` is a sequence of octets. `boolean`
is a two-valued type. `X | none` is a value of type `X` or its absence. `list<X>` is an ordered
sequence and `set<X>` is an unordered collection of distinct values. `one of { a, b }` is a closed
enumeration whose member names are spelled exactly as given. `Result<X, Error>` is either a value of
type `X` or a failure condition from the taxonomy of `ERR-*`.

Arithmetic is over unsigned integers unless the text says otherwise. Division truncates towards
zero. No requirement outside `OBS-*` permits a floating-point value.

## Core model

### Shared types

`CORE-001`. An implementation MUST use these types throughout, with these meanings, wherever this
specification names them.

```
Key           = bytes            # the octets a caller supplies to a routing call
RoutingKey    = bytes            # the octets a key transform produces
NodeId        = bytes            # a node identity, the UTF-8 octets the document spells
ShardId       = bytes            # a shard identifier as PLACE-031 renders it
NodeSet       = set<NodeId>      # unordered; every ordering over one is computed, never stored
Epoch         = u64              # 0 through 9007199254740991
Instant       = u64              # milliseconds from the binding's monotonic source
FencingToken  = { topologyId: bytes, epoch: Epoch, digest: bytes | none }
MonotonicClock = a source of Instant values, supplied by the binding at construction
Node          = one entry of the document's `nodes` array, with its `id`, `state`, `weight`,
                `domains`, `address`, `tags`, and `tokens` members
ValidationError = { path: string, rule: string, detail: string }
```

`CORE-002`. `NodeSet` MUST carry no order. Every ordering this specification defines is computed
from a `NodeSet` by a rule stated here, and an implementation MUST NOT let the iteration order of a
`NodeSet`, of the document's `nodes` array, or of any collection reach a result.

`CORE-003`. `NodeId` and `ShardId` MUST be compared as unsigned octet sequences under `PLACE-020`.
An implementation MUST NOT compare either as text.

`CORE-004`. `Instant` MUST be read from the monotonic time source the binding supplies at
construction. An implementation MUST NOT read a wall clock, MUST NOT read a process-wide default
clock, and MUST NOT require the source to be shared with any other component.

`CORE-005`. Where this specification states a comparison between two products of integers, the
comparison MUST hold over the exact products. An implementation MUST evaluate it in an integer type
wide enough to hold both products, or by a rule that agrees with the exact comparison over the whole
range of its operands, and MUST NOT let a product wrap. `PLACE-051`, `HEALTH-034`, `FAIL-031`,
`OBS-031`, and `SPLIT-041` each state such a comparison, as does every inequality of `PROP-*`. The
products of `OBS-031` and `SPLIT-041` exceed 64 bits within the declared range of their operands,
because `SPLIT-021` types every member of a `ShardReport` as a u64, so a 64-bit multiplication is
not sufficient for either.

### Hash construction

Every placement decision rests on one keyed hash function applied to a framed, domain-tagged input.
A strategy section names one of the five functions this section defines and performs no hash
arithmetic of its own.

`HASH-001`. The hash function MUST be SipHash-2-4 as Aumasson and Bernstein publish it: a keyed
pseudorandom function over a 128-bit key and a message of octets, with two compression rounds per
message word, four finalisation rounds, and 64 bits of output. `hash.algorithm` names it, and
`SEC-015` forbids substituting another function.

`HASH-002`. The output MUST be interpreted as an unsigned 64-bit integer. The little-endian loading
of message words and the little-endian assembly of the output are part of SipHash-2-4. An
implementation MUST NOT vary either to match the big-endian encoding `HASH-020` defines.

`HASH-003`. An implementation MUST verify its SipHash-2-4 against the reference vectors published
with the algorithm before it evaluates any conformance vector. The reference set is the 64 outputs
for the key `000102030405060708090a0b0c0d0e0f` and, for `i` from 0 to 63, the message of `i` octets
holding the values 0 through `i - 1`.

#### Hash key derivation

`HASH-010`. The SipHash key MUST be the sixteen octets that `hash.seed` decodes to, in document
order, used directly. `hash.seed` is 32 lowercase hexadecimal digits. An implementation MUST NOT
stretch, expand, truncate, reverse, or otherwise derive the key from those octets.

`HASH-011`. Where the document carries no `hash` member, or a `hash` member with no `seed`, the key
MUST be sixteen zero octets.

`HASH-012`. The key MUST be constant for the lifetime of a snapshot. An implementation MUST NOT vary
it by strategy, by domain tag, by node, by shard, or by call, and MUST NOT derive it from any source
other than `hash.seed` under `SEC-012`.

#### Input framing

`HASH-020`. `u32be(n)` MUST be the four octets of the unsigned 32-bit integer `n`, most significant
first.

`HASH-021`. A hash input MUST be framed. `frame` MUST be the concatenation, in order, of each
field's length as `u32be` followed by the field's octets. `H` MUST be SipHash-2-4 over the framed
message and the key of `HASH-010`.

```
u32be(n)              = the four octets of n, most significant first
frame(f_1, ..., f_j)  = u32be(len(f_1)) || f_1 || ... || u32be(len(f_j)) || f_j
H(f_1, ..., f_j)      = siphash24(key, frame(f_1, ..., f_j))              -> u64
```

`HASH-022`. An implementation MUST NOT hash a field outside a frame, MUST NOT concatenate two fields
without their lengths, and MUST NOT insert a separator octet between two framed fields.

`HASH-023`. The first field of every frame MUST be the domain tag `HASH-030` gives for the use,
framed as its ASCII octets with no terminating octet.

`HASH-024`. A field of zero octets MUST be framed as `u32be(0)` followed by no further octets. A
field MUST NOT exceed 4294967295 octets.

#### Domain-tagged functions

`HASH-030`. The five hash functions this specification uses MUST be exactly these, and an
implementation MUST NOT introduce a sixth, MUST NOT reuse a tag for a second purpose, and MUST NOT
omit a tag.

| Function | Domain tag | Framed fields after the tag |
|---|---|---|
| `keyHash(rk)` | `sharder/key/v1` | `rk` |
| `ringToken(id, i)` | `sharder/ring-token/v1` | `id`, `u32be(i)` |
| `rvScore(rk, id, i)` | `sharder/rendezvous/v1` | `rk`, `id`, `u32be(i)` |
| `slotScore(s, id, i)` | `sharder/slot-rendezvous/v1` | `u32be(s)`, `id`, `u32be(i)` |
| `rangeScore(sh, id, i)` | `sharder/range-rendezvous/v1` | `sh`, `id`, `u32be(i)` |

`HASH-031`. The fields MUST carry these octets, and `i` MUST be zero-based under `PLACE-053`.

| Field | Octets |
|---|---|
| `rk` | the routing key, after the key transform of `KEY-*` |
| `id` | the node identity, as `CORE-001` defines it |
| `sh` | the shard identifier octets, as `PLACE-034` gives them |
| `s` | the slot index of `SLOT-001`, framed as `u32be(s)` |
| `i` | the token index under `ring`, the virtual node index elsewhere, framed as `u32be(i)` |

`HASH-032`. Each function MUST answer the unsigned 64-bit value `H` produces over its frame. An
implementation MUST NOT post-process that value by folding, masking, rotating, or truncating it.

#### Integer discipline

`HASH-040`. Hash values, ring tokens, and scores MUST be unsigned 64-bit integers, and every
comparison between two of them MUST be an unsigned comparison over the full 64-bit range. An
implementation in a language with no unsigned 64-bit type MUST compare by a rule that agrees with
unsigned comparison at every pair of values.

`HASH-041`. Indices, counts, weights, slot identifiers, and replication factors MUST be unsigned
32-bit integers. An epoch MUST be an integer from 0 through 9007199254740991 under `CORE-001`.

`HASH-042`. `keyHash(rk) mod slotCount` under `SLOT-001` MUST be the only division placement
performs, and MUST be an unsigned remainder.

`HASH-043`. An implementation MUST NOT use a floating-point value in computing, comparing, reducing,
or rendering a hash value.

`HASH-044`. Where a hash value, a ring token, or a score is written as text, in a topology document,
a conformance vector, an event, or an explain record, it MUST be sixteen lowercase hexadecimal
digits, most significant first, with leading zeros retained.

### Prepared placement

`CORE-010`. An implementation MUST expose the placement extension point with this shape. The
requirements that constrain it are `PLACE-*` and the per-strategy prefixes.

```
interface PlacementStrategy:
    name()                                   -> string
    validate(config, nodes, domainLevels)    -> list<ValidationError>
    prepare(snapshot: TopologySnapshot)      -> PreparedPlacement

interface PreparedPlacement:
    shardOf(routingKey: RoutingKey)                        -> ShardId | none
    candidates(routingKey: RoutingKey, eligible: NodeSet)  -> ordered iterator<NodeId>
    shards()                                               -> iterator<ShardId>
    candidatesForShard(shard: ShardId, eligible: NodeSet)  -> ordered iterator<NodeId>
```

`CORE-011`. A `PreparedPlacement` MUST be produced by `prepare` before the snapshot that carries
it is installed, MUST be immutable once produced, and MUST be a pure function of the snapshot
under `PLACE-012`.

`CORE-012`. `candidates` and `candidatesForShard` MAY be lazy under `PLACE-015`. An iterator they
return MUST be usable by one unit of execution at a time and MUST NOT be shared across two.

### Topology snapshot

`CORE-020`. An implementation MUST expose the snapshot with this shape.

```
interface TopologySnapshot:
    topologyId()      -> bytes
    epoch()           -> Epoch
    digest()          -> bytes                  # SHA-256 of the canonical form, 32 octets
    token()           -> FencingToken
    nodes()           -> list<Node>             # every node, in document order
    placementSet()    -> NodeSet                # active plus draining, PLACE-001
    placement()       -> PreparedPlacement
    domainLevels()    -> list<string>
    factor()          -> u32                    # replication.factor, REPL-002
    seedIsDefault()   -> boolean                # SEC-011
    installedAt()     -> Instant | none         # absent for a snapshot from TOPO-191
```

`CORE-021`. A `TopologySnapshot` MUST be immutable under `TOPO-101`, and MUST carry no freshness
state, no staleness state, and no health state under `TOPO-141`.

`CORE-022`. `nodes()` MUST expose every node the document carries, including `joining` and `leaving`
nodes, so that a caller can resolve the address of a node named by a redirect under `FENCE-171`.

### Router interface

`CORE-030`. An implementation MUST expose the routing surface with this shape, and every requirement
of this specification that names `route`, `routeForRead`, `RoutingDecision`, or `AttemptSequence`
refers to this definition.

```
interface Router:
    route(key: Key, options: RouteOptions)          -> Result<RoutingDecision, Error>
    routeForRead(key: Key, affinity: AffinityRequest, options: RouteOptions)
                                                    -> Result<RoutingDecision, Error>
    attempts(decision: RoutingDecision)             -> AttemptSequence
    explain(key: Key)                               -> Result<ExplainRecord, Error>
    snapshot()                                      -> TopologySnapshot | none
    health()                                        -> HealthView   # HEALTH-010
    refresh()                                       -> Result<unit, Error>
    close()

record RouteOptions:
    attemptLimit: u32 | none      # none takes the default of FAIL-022
    explain:      boolean         # false by default; OBS-046 forbids computing one unasked
```

`CORE-031`. `route` MUST serve writes and MUST NOT reorder the preference list. `routeForRead` MUST
serve reads under `READ-*` and MUST differ from `route` only in the reordering `READ-013` defines.

`CORE-032`. `snapshot` MUST answer with the snapshot in force, or with none where no document has
been accepted. It MUST NOT block waiting for one.

`CORE-033`. `refresh` MUST ask the provider for a document and MUST NOT install one that
fails `TOPO-061`. It is the only call that performs provider work where the integrator
supplies no scheduler under `CFG-012`.

`CORE-034`. `close` MUST release the provider subscription and MUST NOT invalidate a
`RoutingDecision` a caller already holds.

### Routing decision

`CORE-040`. An implementation MUST expose the result of a routing call with this shape. Every
requirement that constrains a field of a routing decision constrains this record.

```
record PreferenceEntry:
    node        : NodeId
    position    : u32                             # zero-based, REPL-017
    role        : one of { replica, fallback }    # REPL-017
    health      : HealthState                     # at the instant of the decision, FAIL-013
    attemptable : boolean                         # HEALTH-005

record RoutingDecision:
    routingKey      : RoutingKey
    shard           : ShardId | none              # none under DIR-020
    token           : FencingToken                # TOPO-151
    factor          : u32                         # the effective replication factor n, REPL-003
    replicaCount    : u32                         # the achieved replica count r, REPL-020
    primary         : NodeId                      # the head of entries, CORE-042
    attemptLimit    : u32                         # resolved at route time, FAIL-021 and FAIL-022
    entries         : list<PreferenceEntry>       # the preference list, unreordered, READ-016
    ordered         : list<PreferenceEntry>       # after read affinity; equals entries otherwise
    relaxedLevels   : list<string>                # SPREAD-015
    shortfall       : one of { none, nodes, domains }   # REPL-021
    filterFailedOpen: boolean                     # FAIL-012
    matchedOverride : { index: u32, mode: one of { pin, constrain, both } } | none
    explain         : ExplainRecord | none        # present only where requested, OBS-046
```

`CORE-041`. A `RoutingDecision` MUST be immutable once returned.

`CORE-042`. `primary` MUST be the head of `entries` under `FAIL-004`, whatever its health state and
whatever `ordered` holds. `entries` is never empty on a decision, because an empty candidate
ordering raises the no-candidate condition of `ERR-020` instead of producing one.

`CORE-043`. A `RoutingDecision` MUST NOT carry the key, and MUST NOT carry a node's `address`, a
node's `tags`, an override's `note`, or the document's `metadata`. A caller resolves an address
from `CORE-020`.

`CORE-044`. `entries` and `ordered` MUST hold the same entries in the same roles at the same
positions, differing only in sequence, and `ordered` MUST equal `entries` unless `routeForRead`
produced the decision.

`CORE-045`. `attemptLimit` MUST be the limit in force for the decision: the value `RouteOptions`
supplied, or the default of `FAIL-022` resolved against this decision's effective replication
factor. `attempts` MUST take the limit from the decision and MUST NOT resolve it again.

### Snapshot visibility

`CORE-050`. Construction of a snapshot, including every product of its placement preparation, MUST
be ordered before the update of the reference a routing call reads, for every unit of execution that
observes the update. An implementation MUST NOT publish a snapshot by an unordered write and MUST
NOT rely on a reader never observing a partially constructed value.

`CORE-051`. A reader that observes the updated reference MUST observe the whole snapshot. No unit of
execution may observe a snapshot field, a prepared placement, or a derived index in an uninitialised
state.

`CORE-052`. Because a snapshot is immutable after publication under `TOPO-101`, a reader MUST
require no synchronisation beyond `CORE-050` to read one. An implementation MUST NOT take a lock on
the routing path to read the snapshot in force.

`CORE-053`. A routing call MUST read the reference once at entry under `TOPO-121` and MUST compute
every part of its result from that one snapshot. A `RoutingDecision`, an `AttemptSequence`, and an
`ExplainRecord` derived from a call MUST remain consistent with that snapshot for as long as the
caller holds them, whatever is installed afterwards.

`CORE-054`. Installation MUST be the single atomic replacement of `TOPO-111`. An implementation MUST
NOT install a snapshot by mutating the one in force.

`CORE-055`. An implementation MUST keep a snapshot readable until every call that acquired it has
completed, under `TOPO-131`, and MUST NOT require a caller to release one explicitly.

`CORE-056`. These values MUST be safe for any number of units of execution to use at once, without
external synchronisation.

| Value | Concurrent use | Note |
|---|---|---|
| `Router` | any number | the intended usage is one instance for the life of the process |
| `TopologySnapshot`, `PreparedPlacement` | any number | immutable after `CORE-050` |
| `RoutingDecision`, `ExplainRecord`, `FencingToken` | any number | immutable under `CORE-041` |
| `HealthView` | any number | `report` and `advance` serialise internally |
| `MigrationPlan` | any number | `step` is bounded by `MOVE-071` |
| `AttemptSequence` | one at a time | one walk belongs to one request |
| `ordered iterator<NodeId>` | one at a time | `CORE-012` |

`CORE-057`. An `AttemptSequence` and a lazy candidate iterator MUST NOT be shared across units of
execution. An implementation MUST NOT make either safe by taking a lock, because the cost would fall
on every routing call.

### Thread ownership

`CORE-060`. The library MUST NOT create, start, own, or schedule a thread, a task, a coroutine, a
fibre, a timer, or any other unit of execution. Every computation the library performs MUST run on a
unit of execution the integrator supplies, either by calling the library or by supplying an executor
under `CFG-012`.

`CORE-061`. The library MUST NOT perform work that no call has asked for. Provider polling,
reconciliation, staleness evaluation, health timer evaluation, retry backoff, and migration steps
MUST each be driven by a call the integrator makes, or by an executor the integrator supplied.

`CORE-062`. Where no executor is supplied, an implementation MUST NOT poll a provider and MUST NOT
advance a timer of its own. `refresh`, `HealthView.advance`, and `MigrationPlan.step` are the calls
through which an integrator drives those effects.

`CORE-063`. An implementation MUST NOT hold a lock across a call into an integrator-supplied
extension point: a `TopologyProvider` under `CORE-080`, a `HealthView`, a `MovementHooks`, a
`PressureGauge`, a `ShardMetricsSource`, a `HintObserver`, an event sink, or a metrics registry.

`CORE-064`. An implementation MUST NOT block a routing call on input or output, on a topology
installation, on a provider call, or on any extension point other than the health view. A routing
call MUST NOT wait, sleep, or back off under `FAIL-027`.

`CORE-065`. An implementation MUST serialise the load pipeline under `TOPO-041` without serialising
routing calls against it. Installing a snapshot MUST NOT exclude a routing call, and a routing call
MUST NOT exclude an installation.

### Caller obligations

`CORE-070`. A caller MUST NOT modify the octets of a key while a call that reads them has not
returned. An implementation MUST NOT retain a reference to a caller-supplied key buffer after the
call returns, and MUST copy the octets where it retains them in an `ExplainRecord`.

`CORE-071`. A caller MUST construct a `Router` once and hold it. An implementation MUST NOT require
a router per call, per key, or per thread, and MUST NOT make construction part of the routing path.

`CORE-072`. An integrator-supplied extension point MUST be safe to call from any unit of
execution on which the integrator calls the library, because the library adds none of its own
under `CORE-060`.

`CORE-073`. A caller MUST supply the monotonic time source, the units of execution, and any
transport. The library computes, records, and reports; it moves no data, opens no connection, and
elects nothing.

`CORE-074`. A caller MUST NOT assume an ordering between a topology installation and a routing call
it did not order itself. Two calls made concurrently with an installation MAY observe different
snapshots, and each observes exactly one under `TOPO-121`.

### Topology provider

A topology document reaches the library through a provider the integrator supplies. A provider
retrieves a document from a source and delivers it; every decision about that document is the
library's. A provider carries a pull model, a push model, or both, and the library adapts to
whichever it declares.

#### Provider interface

`CORE-080`. An implementation MUST expose the provider extension point with this shape. The
requirements that constrain what the library does with a document a provider delivers are `TOPO-*`.

```
Document = the octets of a topology document, or a parsed form of them, CORE-083

interface TopologyProvider:
    capabilities()               -> Capabilities
    load()                       -> Result<Document, Error>   # present where pull is true
    watch(sink: TopologySink)    -> Subscription              # present where push is true
    close()

record Capabilities:
    pull: boolean                # the provider answers load on demand
    push: boolean                # the provider delivers through a sink

interface TopologySink:
    onDocument(document: Document)
    onError(error: Error)

interface Subscription:
    cancel()
```

`CORE-081`. A `Capabilities` value MUST carry at least one of `pull` and `push` as true. An
implementation MUST refuse construction with `invalidArgument` under `CFG-003` where the configured
provider declares neither, MUST NOT supply a default for either member, and MUST NOT infer a
capability from the members a provider happens to offer.

`CORE-082`. A provider that declares `pull` MUST supply `load`, and a provider that declares `push`
MUST supply `watch`. An implementation MUST NOT call `load` on a provider that does not declare
`pull` and MUST NOT call `watch` on a provider that does not declare `push`, so a member that is
present and undeclared is never called. A declared member a provider does not supply fails at the
call, and the failure MUST be reported as `providerError` under `ERR-033` and `ERR-063`, like any
other failure raised by a provider.

`CORE-083`. A provider MUST deliver a topology document, as the octets of its JSON encoding or in a
parsed form a binding defines, and MUST NOT deliver a `TopologySnapshot`. Decoding, schema
validation, semantic validation, the canonical form, the digest, the comparison of `TOPO-051`, and
placement preparation MUST be performed by the library under `TOPO-001`. An implementation MUST NOT
let a provider validate, filter, repair, or reject a document on its behalf, and where a binding
accepts a parsed form, the production of that form MUST apply `TOPO-002`.

#### Provider adaptation

`CORE-090`. A provider declaring `pull` and not `push` MUST be adapted by polling. An implementation
MUST call `load` at startup and then once every `pollIntervalMillis`, and MUST run every document it
receives through `TOPO-001`. Where no executor is supplied it MUST NOT poll under `CFG-012`, and
`refresh` under `CORE-033` is then the only path by which a document arrives.

`CORE-091`. A provider declaring `push` and not `pull` MUST be adapted by subscription. An
implementation MUST call `watch` at startup, and MUST have no snapshot in force until the first
document `TOPO-061` accepts is installed. A routing call made before then MUST answer `unready`
under `ERR-023` rather than with an empty preference list, and MUST NOT block waiting for a first
document under `CORE-064`. An implementation MUST report `providerError` under `ERR-033` where no
document arrives within `initialTimeoutMillis` of the subscription.

`CORE-092`. A provider declaring both `pull` and `push` MUST be subscribed and polled. An
implementation MUST call `watch` at startup, MUST call `load` at startup and then once every
`reconcileIntervalMillis` under `CFG-011`, and MUST treat a polled document and a pushed document
alike.

`CORE-093`. A document delivered through `onDocument` MUST be processed on the unit of execution
that called it, because the library creates none of its own under `CORE-060`. An implementation MUST
serialise the pipeline under `TOPO-041` however a document arrives, MUST report a failure delivered
through `onError` as `providerError` under `ERR-033`, and MUST NOT hold a lock across a call into a
provider or a subscription under `CORE-063`.

#### Provider failure behaviour

`CORE-100`. A failure to deliver a document MUST leave the snapshot in force, its freshness, and the
snapshots retained under `TOPO-161` unchanged. An implementation MUST report the failure as
`providerError` under `ERR-033`, MUST emit `topology.provider_error` under `OBS-020`, and MUST space
its next attempt by `min(providerRetryBaseMillis * 2^(attempt - 1), providerRetryCapMillis)`, with
any jitter drawn as an integer number of milliseconds no greater than the computed value and
subtracted from it where `providerRetryJitter` is true. An implementation MUST resubscribe on that
schedule after a subscription fails. A document that is delivered has the outcome `TOPO-061` gives
it; this requirement governs only a failure to deliver one.

`CORE-101`. `close` under `CORE-034` MUST cancel the subscription and then close the provider, MUST
call each at most once, and MUST make no further call into either afterwards.

## Routing keys and placement

A routing call derives a routing key from the key, matches the override table against it, computes a
candidate ordering over the eligible node set under the configured strategy, and names the shard the
key belongs to. Each of the five core strategies computes the candidate ordering by arithmetic of
its own, over a set of rules that holds for all of them.

### Routing key handling

A routing call receives a key and derives the routing key from it before any placement arithmetic
runs. The routing key is the input to override matching, to directory matching, to range bound
comparison, and to `keyHash`.

#### Key opacity

`KEY-001`. An implementation MUST treat a key as an opaque sequence of octets.

`KEY-002`. An implementation MUST NOT apply character encoding, Unicode normalisation, case folding,
whitespace trimming, or any other transformation to a key, other than the configured key transform.

`KEY-003`. A language binding that accepts a key as text MUST encode it as UTF-8 with no byte order
mark, and MUST offer a call that accepts octets directly.

`KEY-004`. An implementation MUST accept a key of zero octets and MUST route it by the same rules as
any other key.

`KEY-005`. An implementation MUST NOT truncate a key or a routing key. Where an implementation
imposes a configured maximum key length, it MUST refuse the routing call rather than route a
truncated key.

#### Key transform selection

`KEY-010`. The routing key MUST be the result of applying the transform named by `keyTransform.kind`
to the key. Where the document carries no `keyTransform` member, the transform MUST be `none`.

`KEY-011`. A key transform MUST be a pure function of the key octets and of the transform's
configured fields. It MUST NOT read the node set, the epoch, time, or randomness.

`KEY-012`. A key transform MUST operate on octets. It MUST NOT decode the key as text, MUST NOT
interpret multi-byte character sequences, and MUST NOT treat a delimiter octet occurring inside a
multi-byte sequence differently from any other occurrence.

`KEY-013`. A routing key MAY be an incomplete or invalid encoding of text, and an implementation
MUST NOT reject it on that ground.

`KEY-014`. A key transform MAY produce a routing key of zero octets, and an implementation MUST
route it by the same rules as any other routing key.

#### Key transform `none`

`KEY-020`. The `none` transform MUST return the key unchanged, octet for octet.

#### Key transform `braceTag`

The `braceTag` transform extracts the octets between the first `open` octet and the first `close`
octet after it. `open` and `close` are each exactly one octet, written as two lowercase hexadecimal
digits, defaulting to `7b` and `7d`.

```
braceTag(key, open, close):
    o = the least index i in [0, length(key)) with key[i] == open
    if no such index exists:
        return key
    c = the least index j in (o, length(key)) with key[j] == close
    if no such index exists:
        return key
    if c - o == 1:
        return key
    return key[o + 1 .. c - 1]
```

`KEY-030`. An implementation MUST search for `open` from index zero, and MUST search for `close`
from index `o + 1`, where `o` is the index of the first `open` octet.

`KEY-031`. Where the key contains no `open` octet, the routing key MUST be the whole key.

`KEY-032`. Where the key contains an `open` octet but no `close` octet strictly after it, the
routing key MUST be the whole key.

`KEY-033`. Where the first `close` octet after `o` is at index `o + 1`, so that no octet lies
between the two delimiters, the routing key MUST be the whole key.

`KEY-034`. Where an `open` octet occurs at index zero, an implementation MUST treat it as the
opening delimiter and MUST NOT treat position zero as a special case.

`KEY-035`. Where a `close` octet occurs before the first `open` octet, an implementation MUST ignore
it.

`KEY-036`. An implementation MUST NOT match nested delimiters. The extracted octets MUST be
`key[o + 1 .. c - 1]` even where they contain further `open` octets.

`KEY-037`. An implementation MUST accept a configuration in which `open` and `close` are the same
octet, and MUST apply the same algorithm to it.

| Key | Routing key | Rule |
|---|---|---|
| `a{b}c` | `b` | the tagged octets |
| `{b}` | `b` | delimiter at position zero |
| `{}` | `{}` | empty tag |
| `{abc` | `{abc` | no `close` after `open` |
| `abc}` | `abc}` | no `open` |
| `}a{b}` | `b` | `close` before `open` ignored |
| `{a{b}c}` | `a{b` | no nesting |
| the empty key | the empty key | no `open` |

#### Key transform `prefixFields`

The `prefixFields` transform returns the octets preceding the `count`-th occurrence of the
`separator` octet. `separator` is exactly one octet, written as two lowercase hexadecimal digits.
`count` is an integer between 1 and 16.

```
prefixFields(key, separator, count):
    seen = 0
    for i in 0 .. length(key) - 1:
        if key[i] == separator:
            seen = seen + 1
            if seen == count:
                return key[0 .. i - 1]
    return key
```

`KEY-040`. An implementation MUST scan the key from index zero towards the end and MUST count every
occurrence of the `separator` octet.

`KEY-041`. Where the key holds at least `count` occurrences of `separator`, the routing key MUST be
the octets preceding the `count`-th occurrence.

`KEY-042`. Where the key holds fewer than `count` occurrences of `separator`, the routing key MUST
be the whole key.

`KEY-043`. Where the `count`-th occurrence of `separator` is at index zero, the routing key MUST be
the empty octet sequence. An implementation MUST NOT return the whole key in that case.

`KEY-044`. Occurrences of `separator` MUST be counted as single octets, so consecutive separators
count separately.

| Key | `separator` | `count` | Routing key |
|---|---|---|---|
| `acme:orders:99` | `3a` | 1 | `acme` |
| `acme:orders:99` | `3a` | 2 | `acme:orders` |
| `acme` | `3a` | 1 | `acme` |
| `acme:` | `3a` | 1 | `acme` |
| `:x` | `3a` | 1 | the empty routing key |
| `::` | `3a` | 2 | `:` |
| `a:b` | `3a` | 2 | `a:b` |
| the empty key | `3a` | 1 | the empty key |

### Placement rules

The rules in this section hold for every placement strategy. A strategy adds to them and never
relaxes them.

#### Placement set and eligible set

`PLACE-001`. The placement set at an epoch MUST be exactly the nodes of the snapshot whose `state`
is `active` or `draining`.

`PLACE-002`. A node whose `state` is `joining` or `leaving` MUST NOT appear in a candidate ordering,
under any strategy, under any assignment mode, and under any override.

`PLACE-003`. The eligible node set for a routing key MUST be the placement set filtered by the
`constrain` member of the matched override entry, where one applies, and MUST be the placement set
otherwise. The filtering rules are `OVR-020` and following.

`PLACE-004`. An implementation MUST compute the eligible node set before the strategy runs, and MUST
present the strategy with no node outside it.

`PLACE-005`. Where the eligible node set is empty, the candidate ordering MUST be empty. An empty
eligible node set is not a validation failure and is not an error at snapshot publication; the
result of a routing call over an empty candidate ordering is specified by `FAIL-*`.

#### Candidate ordering contract

`PLACE-010`. A candidate ordering MUST be a pure function of the triple of snapshot, routing key,
and eligible node set.

`PLACE-011`. A candidate ordering MUST NOT read health state, wall clock time, monotonic time,
randomness, caller identity, thread identity, process identity, locale, a node's `address`, or the
document's `metadata`. A node's `tags` MUST be read only by an override constraint.

`PLACE-012`. `prepare` MUST be a pure function of the snapshot. Two prepared placements built from
snapshots with equal topology digests MUST produce identical candidate orderings for every routing
key and every eligible node set.

`PLACE-013`. A candidate ordering MUST contain each node identity at most once. Where a strategy
derives its ordering from an authored node list, an implementation MUST emit the first occurrence of
each identity and MUST discard every later occurrence.

`PLACE-014`. A candidate ordering MUST contain no identity outside the eligible node set. Where an
authored node list names an identity that is not eligible, an implementation MUST omit it and MUST
preserve the relative order of the identities that remain.

`PLACE-015`. `candidates` and `candidatesForShard` MAY compute their result lazily. Where they do,
the first `p` entries a caller consumes MUST equal the first `p` entries of the ordering computed
eagerly, for every `p`.

`PLACE-016`. An implementation MUST NOT depend on the stability of any sort it uses. Every
comparator this specification defines is a total order over distinct node identities.

`PLACE-017`. An implementation MUST produce the candidate ordering before any replication rule runs.
Replication factor, failure domain spread, and distinctness are applied to the candidate ordering by
`REPL-*` and never by a strategy.

#### Node identity comparison

`PLACE-020`. Node identities MUST be compared as the unsigned octet sequences of their UTF-8
encoding, exactly as the topology document spells them.

`PLACE-021`. The comparison MUST be lexicographic over octets, each octet compared as an unsigned
value between 0 and 255. Where one sequence is a proper prefix of the other, the shorter sequence
MUST compare less.

`PLACE-022`. An implementation MUST NOT compare node identities by code point, by collation order,
by locale-sensitive rules, or case insensitively.

`PLACE-023`. Every comparator this specification defines MUST end in ascending node identity
comparison. Node identities are unique within a topology, so every such comparator is a total order.

#### Shard identifiers

`PLACE-030`. `shardOf` MUST be a pure function of the snapshot and the routing key. It MUST be
computed over the placement set and MUST NOT read the eligible node set, so that an override
constraint never renames a shard.

`PLACE-031`. A strategy MUST name its shards as follows.

| Kind | Shard identifier | `shards` enumeration order |
|---|---|---|
| `ring` | the owning token as sixteen lowercase hexadecimal digits | ascending ring order |
| `rendezvous` | the routing key octets in lowercase hexadecimal | empty |
| `slot` | the slot index in decimal ASCII with no leading zeros | ascending slot index |
| `range` | the covering range's `shardId` | document order |
| `directory` | `<kind>:<base16 of the decoded matcher value>` | `entries` array order |

`PLACE-032`. Under `rendezvous`, `shards` MUST be empty and `candidatesForShard(s, eligible)` MUST
decode `s` from lowercase hexadecimal to the routing key octets and equal `candidates` over those
octets.

`PLACE-033`. For every routing key `rk` and every eligible node set `E`, `candidates(rk, E)` MUST
equal `candidatesForShard(shardOf(rk), E)`, except where `rk` is matched by an override carrying a
`pin` under `OVR-016`, and except where `shardOf(rk)` answers with no shard under `DIR-020`.

`PLACE-034`. The octets of a `ShardId` under `CORE-001` MUST be the UTF-8 encoding of the rendering
`PLACE-031` gives. Under `ring`, `slot`, and `directory` that rendering is ASCII; under `range` it
is the `shardId` string as the document spells it; under `rendezvous` it is the hexadecimal text
rather than the routing key octets. The same octets are framed into `rangeScore` under `HASH-031`
and compared under `CORE-003`.

#### Node weights

`PLACE-040`. A node's `weight` is an integer between 0 and 1000000, defaulting to 1. It expresses
the node's share of capacity relative to its peers and carries no dimension.

`PLACE-041`. An implementation MUST honour weight by replicating a node in the strategy's input
space in proportion to its weight. It MUST NOT scale, divide, exponentiate, or otherwise weight a
hash value or a score.

`PLACE-042`. A node of weight 0 in the placement set MUST receive a virtual node count of 0 under
every derived assignment, and MUST NOT appear in a candidate ordering produced by `ring` with
`derived` token assignment, by `rendezvous`, by `slot` with `derived` assignment, or by `range` with
`derived` assignment.

`PLACE-043`. A node of weight 0 in the placement set MUST remain in the placement set, MUST be
eligible for an override `pin`, and MUST appear in a candidate ordering produced from an authored
node list or from an authored token list.

`PLACE-044`. Weight MUST NOT affect placement under `ring` with `explicit` token assignment, under
`slot` with `explicit` assignment, under `range` with `explicit` assignment, or under `directory`.
Under those configurations weight is advisory, and an implementation reports observed balance
against it without changing any ordering.

#### Virtual node counts

`PLACE-050`. The **virtual node count** of a node under a derived assignment MUST be
`min(weight * perWeightUnit, cap)`, where `perWeightUnit` and `cap` are taken from the table below.

| Configuration | `perWeightUnit` | `cap` |
|---|---|---|
| `ring` with `derived` | `tokensPerWeightUnit`, default 4 | `maxTokensPerNode`, default 4096 |
| `rendezvous` | `virtualNodesPerWeightUnit`, default 1 | `maxVirtualNodesPerNode`, default 1024 |
| `slot` with `derived` | 1, not configurable | 1024, not configurable |
| `range` with `derived` | 1, not configurable | 1024, not configurable |

`PLACE-051`. An implementation MUST compute `weight * perWeightUnit` in an integer type of at least
64 bits, or by a saturating multiplication, before applying the cap. The product reaches 4096000000
at the configured maxima, which overflows a signed 32-bit integer.

`PLACE-052`. Where `weight * perWeightUnit` exceeds `cap`, an implementation MUST clamp the count to
`cap` and MUST emit a clamping event at snapshot publication naming the node identity, the requested
count, and the granted count. Clamping MUST NOT be a validation failure.

`PLACE-053`. Virtual node indices MUST be the integers from 0 to the virtual node count minus one,
and MUST be framed into a hash input as `u32be(i)`.

`PLACE-054`. The virtual node set of a node at count `v` MUST be a prefix of its virtual node set at
any count greater than `v`. An implementation MUST NOT derive an index from the count.

#### Matcher evaluation

A matcher is `{kind, value, encoding}`. It is shared by `directory` entries and by `overrides`
entries, and it is evaluated against the routing key.

`PLACE-060`. An implementation MUST decode a matcher's `value` to octets before comparing it. Under
`encoding` of `utf8`, the octets MUST be the UTF-8 encoding of the string as the document spells it.
Under `encoding` of `base16`, the octets MUST be the decoding of an even count of lowercase
hexadecimal digits. Where `encoding` is absent, it MUST be treated as `utf8`.

`PLACE-061`. A matcher of `kind` `exact` MUST match a routing key exactly when the decoded value and
the routing key have the same length and are equal octet for octet.

`PLACE-062`. A matcher of `kind` `prefix` MUST match a routing key exactly when the decoded value is
no longer than the routing key and equals its leading octets.

`PLACE-063`. A `prefix` matcher whose decoded value is the empty octet sequence MUST match every
routing key.

`PLACE-064`. Matching MUST be over octets. An implementation MUST NOT normalise, case fold, or
decode either side as text.

`PLACE-065`. Where several entries of one table match a routing key, an implementation MUST select
the **matched entry** by this precedence, applied in order until one entry remains.

1. An entry whose matcher is `exact` beats every entry whose matcher is `prefix`.
2. Among matching `prefix` entries, the entry whose decoded value is longest in octets wins.
3. Among entries still tied, the entry at the lower array index wins.

Clause 3 is unreachable in a valid document. Two entries that survive clauses 1 and 2 carry the same
`kind` and the same decoded octets, which `PLACE-067` makes a validation failure. The clause is
retained so that the precedence stays total over a table an implementation evaluates without having
validated it, as `RANGE-015` is retained for the same reason.

`PLACE-066`. An implementation MUST NOT evaluate more than one matched entry for a routing key.

`PLACE-067`. Two entries of one table with identical matchers, compared over the decoded octets and
the `kind`, make the document invalid. Identity of matchers is a load-time rule under `TOPO-*`, and
an implementation MUST NOT resolve such a collision at routing time.

### Ring strategy

#### Ring token derivation

`RING-001`. Under `tokenAssignment` of `derived`, a node's token count MUST be its virtual node
count under `PLACE-050`, and its tokens MUST be `ringToken(id, i)` for `i` from 0 to that count
minus one.

`RING-002`. Under `derived`, a node of weight 0 has a token count of 0, owns no token, and MUST NOT
appear in a candidate ordering.

`RING-003`. Under `tokenAssignment` of `explicit`, a node's tokens MUST be the values of its
`tokens` member, each decoded from sixteen lowercase hexadecimal digits to an unsigned 64-bit
integer, in the order the array spells them.

`RING-004`. Under `explicit`, `weight` MUST NOT affect the token set. A placement-set node of
weight 0 that carries tokens owns the token ranges those tokens terminate and appears in the
candidate ordering accordingly.

`RING-005`. An implementation MUST derive tokens from the eligible node set when building a
candidate ordering, and from the placement set when computing `shardOf` or enumerating `shards`.

#### Ring order

The **ring order** is the total order over ring entries that every ring operation walks. A ring
entry is the triple of a token value, an owner node identity, and a token index.

```
ringEntries(nodes):
    entries = []
    for node in nodes:
        if tokenAssignment == "derived":
            for i in 0 .. virtualNodeCount(node) - 1:
                entries.append(entry(ringToken(node.id, i), node.id, i))
        else:
            for i in 0 .. length(node.tokens) - 1:
                entries.append(entry(decodeU64(node.tokens[i]), node.id, i))
    return entries sorted by ringLess
```

`RING-010`. `ringLess` MUST compare two ring entries by token value ascending as unsigned 64-bit
integers, then by owner node identity ascending under `PLACE-020`, then by token index ascending.

`RING-011`. Where two eligible nodes derive the same token value, the ring order MUST place the
entry whose owner identity is lower first. This case arises only under `derived`; under `explicit`
duplicate token values across the document are a validation failure under `TOPO-*`.

`RING-012`. Where one node derives the same token value at two indices, both entries MUST appear in
the ring order, ordered by index, and the walk emits the owner once under `PLACE-013`.

`RING-013`. The ring order MUST NOT depend on the order of the `nodes` array of the document.

#### Ring ownership and walk

```
candidates(rk, eligible):
    R = ringEntries(eligible)
    if R is empty:
        return the empty ordering
    return walk(R, positionAtOrAbove(R, keyHash(rk)))

positionAtOrAbove(R, v):
    p = the least index in [0, length(R)) with R[p].token >= v, compared unsigned
    if no such index exists:
        p = 0
    return p

walk(R, s):
    result = the empty ordering
    for k in 0 .. length(R) - 1:
        owner = R[(s + k) mod length(R)].owner
        if owner is not already in result:
            append owner to result
    return result
```

`RING-020`. The owning ring entry for a routing key MUST be the entry at
`positionAtOrAbove(R, keyHash(rk))`, which is the first entry in ring order whose token is at or
above the key hash, wrapping to the first entry of the ring order where no token is at or above it.

`RING-021`. The candidate ordering MUST be the owners encountered by walking the ring order
ascending from the owning entry, wrapping once past the last entry to the first, visiting every
entry exactly once, and appending each owner the first time it is encountered.

`RING-022`. The walk MUST visit exactly `length(R)` entries and MUST terminate.

`RING-023`. The candidate ordering MUST contain every eligible node that owns at least one token,
exactly once, and MUST contain no other identity.

`RING-024`. Where no eligible node owns a token, the candidate ordering MUST be empty.

`RING-025`. The candidate ordering MUST NOT depend on whether an implementation searches the ring by
linear scan, by binary search, or by a precomputed lookup table.

#### Ring shards

`RING-030`. `shardOf(rk)` MUST be the token of the owning ring entry computed over the placement
set, rendered as sixteen lowercase hexadecimal digits, most significant first.

`RING-031`. `shards()` MUST enumerate the distinct token values of the placement set in ascending
ring order, each rendered by `RING-030`, and MUST yield each distinct value exactly once. Where two
ring entries carry one token value under `RING-011` or `RING-012`, they name one shard, and
`RING-032` answers identically for either entry.

`RING-032`. `candidatesForShard(s, eligible)` MUST decode `s` to an unsigned 64-bit token value `v`,
build the ring order over the eligible node set, and return `walk(R, positionAtOrAbove(R, v))`. A
shard whose token is absent from the eligible ring MUST therefore start the walk at the next entry
at or above `v`, wrapping, rather than yielding an empty ordering.

### Rendezvous strategy

#### Rendezvous scores

`RV-001`. A node's virtual node count MUST be `min(weight * virtualNodesPerWeightUnit,
maxVirtualNodesPerNode)` under `PLACE-050`.

`RV-002`. A node whose virtual node count is 0 has no score and MUST NOT appear in the candidate
ordering.

`RV-003`. A node's score for a routing key MUST be the largest of `rvScore(rk, id, i)` over `i` from
0 to its virtual node count minus one, compared as unsigned 64-bit integers.

`RV-004`. An implementation MUST NOT scale a score by weight, MUST NOT take a logarithm, and MUST
NOT perform any floating-point operation in computing or comparing a score.

#### Rendezvous ordering

```
candidates(rk, eligible):
    scored = [ (score(rk, node), node.id) for node in eligible with virtualNodeCount(node) > 0 ]
    sort scored by rvLess
    return the node identities in that order
```

`RV-010`. `rvLess` MUST order two scored nodes by score descending as unsigned 64-bit integers, then
by node identity ascending under `PLACE-020`. That comparator is a total order.

`RV-011`. The candidate ordering MUST contain every eligible node whose virtual node count is at
least 1, exactly once, and MUST contain no other identity.

`RV-012`. Where the eligible node set contains no node of non-zero virtual node count, the candidate
ordering MUST be empty.

`RV-013`. The candidate ordering MUST NOT depend on the order of the `nodes` array of the document.

#### Rendezvous shards

`RV-020`. `shardOf(rk)` MUST be the lowercase hexadecimal encoding of the routing key octets, two
digits per octet, under `PLACE-031`. A `rendezvous` shard identifier names one routing key, so its
extent is a single key rather than a range of them.

`RV-021`. `shards()` MUST be empty. A `rendezvous` topology names no shard extent, so it drives no
ownership delta and no handoff.

`RV-022`. `candidatesForShard(s, eligible)` MUST decode `s` from lowercase hexadecimal to the
routing key octets and equal `candidates` over those octets, under `PLACE-032`.

### Slot strategy

#### Slot index derivation

`SLOT-001`. The slot index for a routing key MUST be `keyHash(rk) mod slotCount`, computed as an
unsigned 64-bit remainder. `slotCount` is an integer between 1 and 1048576.

`SLOT-002`. The slot index MUST lie between 0 and `slotCount - 1`.

`SLOT-003`. An implementation MUST NOT require `slotCount` to be a power of two and MUST NOT replace
the remainder with a bitwise mask.

`SLOT-004`. The slot index MUST NOT depend on the node set, so adding or removing a node MUST NOT
change any key's slot index.

#### Slot explicit assignment

`SLOT-010`. A slot range string MUST be either a single slot index in decimal ASCII or an inclusive
pair `low-high` in decimal ASCII. Both forms denote slot indices inclusive of their endpoints.

`SLOT-011`. The **covering entry** for a slot index MUST be the entry of `assignments` one of whose
`slots` ranges contains that index. Validation under `TOPO-*` requires that every slot index from 0
to `slotCount - 1` is covered exactly once, so the covering entry is unique.

`SLOT-012`. The candidate ordering MUST be the covering entry's `nodes` array in array order,
filtered to the eligible node set under `PLACE-014` and deduplicated under `PLACE-013`.

`SLOT-013`. Where no entry covers the slot index, the candidate ordering MUST be empty. An
implementation MUST NOT fall back to derived ordering, MUST NOT choose a neighbouring entry, and
MUST NOT interpolate.

`SLOT-014`. `weight` MUST NOT affect the ordering under `explicit` assignment.

#### Slot derived assignment

`SLOT-020`. Under `assignment` of `derived`, a node's virtual node count MUST be `min(weight, 1024)`
under `PLACE-050`, and a node of count 0 MUST NOT appear in the candidate ordering.

`SLOT-021`. A node's score for a slot index MUST be the largest of `slotScore(slotIndex, id, i)`
over `i` from 0 to its virtual node count minus one, compared as unsigned 64-bit integers.

`SLOT-022`. The candidate ordering MUST order the eligible nodes of non-zero virtual node count by
score descending, then by node identity ascending under `PLACE-020`.

`SLOT-023`. The candidate ordering for a slot index MUST NOT depend on the routing key beyond the
slot index it reduces to. Two routing keys with the same slot index MUST produce the same ordering.

#### Slot shards

`SLOT-030`. `shardOf(rk)` MUST be the slot index rendered in decimal ASCII with no leading zeros.

`SLOT-031`. `shards()` MUST enumerate every slot index from 0 to `slotCount - 1` in ascending order.

`SLOT-032`. `candidatesForShard(s, eligible)` MUST parse `s` as a decimal slot index and
apply `SLOT-012` or `SLOT-022` according to the configured assignment mode.

### Range strategy

#### Bound comparison

`RANGE-001`. An implementation MUST compare a routing key against a range bound by unsigned bytewise
comparison.

```
compare(a, b):
    n = the lesser of length(a) and length(b)
    for i in 0 .. n - 1:
        if a[i] < b[i]: return LESS
        if a[i] > b[i]: return GREATER
    if length(a) < length(b): return LESS
    if length(a) > length(b): return GREATER
    return EQUAL
```

`RANGE-002`. Each octet MUST be compared as an unsigned value between 0 and 255. An implementation
MUST NOT compare octets as signed values and MUST NOT compare the operands as text.

`RANGE-003`. Where one sequence is a proper prefix of the other, the shorter sequence MUST compare
less. The bound `6d` therefore compares less than the key `6d00`.

`RANGE-004`. The empty octet sequence MUST compare less than every non-empty sequence and equal to
itself.

`RANGE-005`. A range bound MUST be decoded from lowercase hexadecimal before comparison, and MUST be
compared against the routing key rather than against the key.

#### Covering range

`RANGE-010`. A range MUST be the half-open interval from `start` inclusive to `end` exclusive. A
`start` of `null` MUST be treated as the beginning of the keyspace and an `end` of `null` as its
end.

`RANGE-011`. The **covering range** for a routing key MUST be the range for which `start` is `null`
or `compare(rk, start)` is not `LESS`, and `end` is `null` or `compare(rk, end)` is `LESS`.

`RANGE-012`. A routing key equal to a range's `start` MUST fall inside that range. A routing key
equal to a range's `end` MUST fall outside it, in the following range.

`RANGE-013`. Validation under `TOPO-*` requires the first `start` to be `null`, the last `end` to be
`null`, and each `end` to equal the following `start`, so no gap and no overlap exists and every
routing key including the empty one has exactly one covering range. A routing key can therefore
never fall below the lowest bound or above the highest.

`RANGE-014`. Where an implementation nonetheless evaluates a routing key that no range covers, the
candidate ordering MUST be empty. It MUST NOT select the nearest range, MUST NOT extend a bound, and
MUST NOT wrap from the last range to the first.

`RANGE-015`. Where an implementation nonetheless evaluates a routing key covered by more than one
range, it MUST select the range at the lower array index, and MUST NOT merge the two orderings.

#### Range explicit assignment

`RANGE-020`. Under `assignment` of `explicit`, the candidate ordering MUST be the covering range's
`nodes` array in array order, filtered to the eligible node set under `PLACE-014` and deduplicated
under `PLACE-013`.

`RANGE-021`. `weight` MUST NOT affect the ordering under `explicit` assignment.

#### Range derived assignment

`RANGE-030`. Under `assignment` of `derived`, a node's virtual node count MUST be
`min(weight, 1024)` under `PLACE-050`, and a node of count 0 MUST NOT appear in the candidate
ordering.

`RANGE-031`. A node's score for a range MUST be the largest of `rangeScore(shardId, id, i)` over `i`
from 0 to its virtual node count minus one, where `shardId` is framed as its UTF-8 octets.

`RANGE-032`. The candidate ordering MUST order the eligible nodes of non-zero virtual node count by
score descending, then by node identity ascending under `PLACE-020`.

`RANGE-033`. The candidate ordering MUST depend on the routing key only through the covering range's
`shardId`. Two routing keys in the same range MUST produce the same ordering.

#### Range shards

`RANGE-040`. `shardOf(rk)` MUST be the covering range's `shardId`.

`RANGE-041`. `shards()` MUST enumerate the `shardId` of every range in document order, which is
ascending order of `start`.

`RANGE-042`. `candidatesForShard(s, eligible)` MUST locate the range whose `shardId` is `s` and
apply `RANGE-020` or `RANGE-032` according to the configured assignment mode. Where no range carries
that identifier, the candidate ordering MUST be empty.

### Directory strategy

#### Directory evaluation

`DIR-001`. An implementation MUST evaluate the `entries` table against the routing key using the
matcher rules of `PLACE-060` and following.

`DIR-002`. The matched entry MUST be selected by the precedence of `PLACE-065`: an `exact` matcher
beats every `prefix` matcher, the longest matching `prefix` wins among prefixes, and the lower array
index wins among entries still tied.

`DIR-003`. The candidate ordering MUST be the matched entry's `nodes` array in array order, filtered
to the eligible node set under `PLACE-014` and deduplicated under `PLACE-013`.

`DIR-004`. `weight` MUST NOT affect the ordering.

`DIR-005`. An implementation MUST NOT reorder a matched entry's `nodes` array by any hash, score, or
failure domain rule. The authored order is the candidate ordering.

#### Directory no-match result

`DIR-010`. Where no entry matches the routing key, the candidate ordering MUST be empty. A directory
is exhaustive by construction.

`DIR-011`. An implementation MUST NOT supply a default entry, MUST NOT fall back to another
strategy, and MUST NOT place an unmatched routing key on the whole eligible node set.

`DIR-012`. The result of a routing call over an empty candidate ordering is specified by `FAIL-*`.

#### Directory shards

`DIR-020`. `shardOf(rk)` MUST be the matched entry's matcher rendered as `<kind>:<value>`, where
`<kind>` is `exact` or `prefix` and `<value>` is the decoded matcher octets in lowercase
hexadecimal. Where no entry matches, `shardOf` MUST answer with no shard.

`DIR-021`. `shards()` MUST enumerate the entries in `entries` array order, each rendered as
`DIR-020` renders it.

`DIR-022`. `candidatesForShard(s, eligible)` MUST locate the entry whose rendering equals `s` and
apply `DIR-003`. Where no entry renders to `s`, the candidate ordering MUST be empty.

### Overrides and pinning

An override is a layer above the placement strategy. It is declared in the topology document,
matched against the routing key, and composes with the strategy in one of two modes.

#### Override matching

`OVR-001`. An implementation MUST evaluate the `overrides` array against the routing key, after the
key transform and before the strategy runs.

`OVR-002`. Matching MUST follow `PLACE-060` and following, and the matched entry MUST be selected by
the precedence of `PLACE-065`.

`OVR-003`. At most one override entry MUST apply to a routing key. An implementation MUST NOT
compose two entries, MUST NOT union their node lists, and MUST NOT apply a second entry's constraint
to a first entry's pin.

`OVR-004`. Where no entry matches the routing key, or where the document carries no `overrides`
member, the eligible node set MUST be the placement set and the strategy MUST run over it.

`OVR-005`. The override layer MUST be evaluated under every strategy kind, including `directory`.

`OVR-006`. The override layer MUST NOT read the key, only the routing key.

#### Pinning

`OVR-010`. Where the matched entry carries a `pin` member, the candidate ordering MUST be the `pin`
array in array order, and the placement strategy MUST NOT run for that routing key.

`OVR-011`. The pinned ordering MUST be filtered to the placement set under `PLACE-002`, so an
identity whose node is `joining` or `leaving` is omitted and the identities that remain keep their
relative order.

`OVR-012`. The pinned ordering MUST be deduplicated under `PLACE-013`.

`OVR-013`. An implementation MUST NOT append the remainder of the strategy's ordering to a pinned
ordering, MUST NOT reorder a pinned ordering by any hash or score, and MUST NOT reorder it by
failure domain. Failure domain spread under `REPL-*` applies to a pinned ordering by filtering it,
never by reordering it.

`OVR-014`. Where every identity in `pin` is filtered out, the candidate ordering MUST be empty. An
implementation MUST NOT fall back to the strategy.

`OVR-015`. A `pin` naming an identity absent from `nodes` makes the document invalid under `TOPO-*`.

`OVR-016`. Under `ring`, `shardOf` MUST continue to answer for a pinned routing key, because the
shard is a property of the keyspace rather than of the pin. A pinned key's candidate ordering and
its shard's candidate ordering therefore differ, and `PLACE-033` does not hold for a pinned routing
key.

#### Constraints

`OVR-020`. Where the matched entry carries a `constrain` member and no `pin` member, the eligible
node set MUST be the placement set filtered by the constraint, and the strategy MUST run over the
filtered set exactly as it would run over the placement set.

`OVR-021`. The three members of a constraint MUST intersect. A node is eligible only where it
satisfies every member that is present.

`OVR-022`. Under `domains`, for each named level, a node MUST carry a domain identifier at that
level whose octets equal one of the listed values. A node whose identifier at a named level is
listed nowhere MUST be excluded.

`OVR-023`. Under `tags`, for each named key, a node MUST carry a tag at that key whose value octets
equal one of the listed values. A node carrying no tag at a named key MUST be excluded.

`OVR-024`. Under `nodes`, a node's identity MUST appear in the list. A node absent from the list
MUST be excluded.

`OVR-025`. A member absent from `constrain` MUST impose no restriction.

`OVR-026`. Domain identifiers and tag values MUST be compared as octet sequences under `PLACE-020`,
case sensitively, with no normalisation. A domain identifier is scoped to its level, so equal
identifiers at different levels are unrelated.

`OVR-027`. Where a constraint excludes every node of the placement set, the eligible node set MUST
be empty and the candidate ordering MUST be empty. An implementation MUST NOT widen the constraint,
MUST NOT fall back to the placement set, and MUST NOT emit a node the constraint excluded. The
result of the routing call is specified by `FAIL-*`.

`OVR-028`. A constraint naming a level absent from `domainLevels` makes the document invalid under
`TOPO-*`.

#### Override composition

`OVR-030`. Where the matched entry carries both `pin` and `constrain`, the pin MUST apply and the
constraint MUST filter the pinned ordering. The strategy MUST NOT run.

`OVR-031`. Under `OVR-030` the filters MUST be applied in either order with the same result, because
the placement set filter and the constraint filter both remove identities and neither reorders.

`OVR-032`. Under a constraint, an authored node list belonging to a `slot` assignment entry,
a `range` entry, or a `directory` entry MUST be filtered to the constrained eligible set
under `PLACE-014`. A constraint therefore composes with explicit assignment as well as with a
hash strategy.

`OVR-033`. An implementation MUST record the matched entry, the constraint applied, and every
identity the constraint excluded, in the explain record.

#### Override replication factor

`OVR-040`. Where the matched entry carries a `factor` member, that value MUST supersede
`replication.factor` for the routing keys the entry matches. The preference list rules that consume
it are `REPL-*`.

`OVR-041`. `factor` MUST NOT change the candidate ordering. It selects how far down an ordering the
preference list builder takes replicas, and nothing else.

### Placement properties

The properties below are the contract the conformance suite tests. Each is stated so that an
implementation either satisfies it or produces a counterexample.

Throughout, `E` is the eligible node set for a routing key, `v_i` is node `i`'s virtual node count,
and `V` is the sum of `v_j` over `E`. Under `ring`, `t_i` is node `i`'s token count and `T` is the
sum of `t_j` over `E`. A sample of `M` routing keys is drawn independently and uniformly from the
set of sixteen-octet sequences, and `c_i` is the count of sampled keys whose candidate ordering
begins with node `i`.

#### Determinism

`PROP-001`. For any valid topology document `D`, any key `k`, and any two conforming
implementations, the routing key, the matched override entry, the eligible node set, the shard
identifier, and the candidate ordering MUST be identical.

`PROP-002`. Equality of two candidate orderings MUST be tested over the sequence of node identities
serialised as a JSON array of strings. Two orderings are equal exactly when those serialisations are
byte identical.

`PROP-003`. Two invocations with the same snapshot, routing key, and eligible node set MUST produce
identical candidate orderings, within one process, across process restarts, across operating
systems, across processor architectures, and across language bindings.

`PROP-004`. A candidate ordering MUST be identical whether computed eagerly or consumed lazily, for
every prefix length, under `PLACE-015`.

`PROP-005`. A candidate ordering MUST be identical when the `nodes` array of the document is
permuted, when `metadata` changes, when any node's `address` changes, when `epoch` changes, and when
`replication` changes.

`PROP-006`. A bound of this section stated over a sample of `M` routing keys MUST be evaluated
against the deterministic sample that [`30-conformance.md`](30-conformance.md) fixes, rather than
against a sample a port draws for itself. Two ports evaluating one bound MUST draw the same keys in
the same order, so that a bound that fails in one port fails in the other.

#### Minimal movement

Let `D` and `D'` be two valid topology documents that are identical except for their `nodes` array,
where `D'` adds one node `x` to the node set of `D` or removes one node `x` from it, and where every
other node's `weight`, `state`, `domains`, and `tokens` are unchanged.

`PROP-010`. Under `ring`, `rendezvous`, `slot` with `derived` assignment, and `range` with `derived`
assignment, on addition of `x`, for every key the first candidate under `D'` MUST be either the
first candidate under `D` or `x`. No key may move between two nodes that both exist in `D`.

`PROP-011`. Under the same four configurations, on removal of `x`, for every key whose first
candidate under `D` is not `x`, the first candidate under `D'` MUST be the same node.

`PROP-012`. Under the same four configurations, the candidate ordering under `D` with `x` deleted
from it MUST equal the candidate ordering under `D'` on removal, and the candidate ordering under
`D'` with `x` deleted from it MUST equal the candidate ordering under `D` on addition. The whole
ordering is preserved as a subsequence, not only its first entry.

`PROP-013`. Under `rendezvous`, `slot` with `derived`, and `range` with `derived`, the expected
fraction of keys that change first candidate MUST be `v_x / (V + v_x)` on addition and `v_x / V` on
removal. With `N` nodes of equal weight this is `1 / (N + 1)` on addition and `1 / N` on removal.

`PROP-014`. Under `ring`, the expected fraction of keys that change first candidate MUST be
`t_x / (T + t_x)` on addition and `t_x / T` on removal.

`PROP-015`. The expected fractions of `PROP-013` and `PROP-014` MUST be checked as a bound. With `m`
the count of sampled keys whose first candidate differs between `D` and `D'`, and `p_num` and
`p_den` the numerator and denominator of the expected fraction, a conforming implementation MUST
satisfy `20 * |m * p_den - M * p_num| <= M * p_num` under `rendezvous`, `slot` with `derived`, and
`range` with `derived`, provided `M * p_num >= 10000 * p_den`. Under `ring` the multiplier MUST be 4
rather than 20, and the bound applies only where every node holds at least 256 tokens.

`PROP-016`. Under `slot`, `range`, `rendezvous`, and `directory`, adding or removing a node MUST NOT
change `shardOf(k)` for any key. Under `ring`, adding `x` MAY change `shardOf(k)`, and where it does
the new value MUST be one of `x`'s tokens; removing `x` MAY change `shardOf(k)`, and where it does
the old value MUST have been one of `x`'s tokens.

`PROP-017`. Under `slot` with `explicit` assignment, `range` with `explicit` assignment, and
`directory`, this specification states no movement bound. Movement between two epochs is exactly the
difference between the authored tables.

`PROP-018`. Under those three configurations, a key's shard MUST NOT change while the shard-defining
fields are unchanged: `slotCount` under `slot`, every range's `start` and `end` under `range`, and
the matcher of every entry under `directory`.

`PROP-019`. Let `D` and `D'` be identical except for node `x`'s `weight`, with the weight higher in
`D'`. Under `ring` with `derived`, `rendezvous`, `slot` with `derived`, and `range` with `derived`,
for every key the first candidate under `D'` MUST be either the first candidate under `D` or `x`,
and a key whose first candidate under `D` is `x` MUST keep `x`. Lowering a weight is the same
statement with `D` and `D'` exchanged. A weight change therefore moves keys only between `x` and the
rest, never between two other nodes.

#### Balance

`PROP-020`. Under `rendezvous`, for every eligible node `i`, a conforming implementation MUST
satisfy `20 * |c_i * V - M * v_i| <= M * v_i`, provided the sample is large enough that
`M * v_min >= 10000 * V`, where `v_min` is the least virtual node count in `E`. The bound is a band
of five per cent around the node's weighted expected share.

`PROP-021`. Under `ring`, for every eligible node `i`, a conforming implementation MUST satisfy
`4 * |c_i * T - M * t_i| <= M * t_i`, provided every eligible node holds at least 256 tokens and
`M * t_min >= 10000 * T`. The bound is a band of twenty five per cent. Below 256 tokens per node
this specification states no balance bound for `ring`.

`PROP-022`. Under `slot` with `derived` assignment, the bound of `PROP-020` MUST hold with the
sample replaced by the set of all `slotCount` slots and `c_i` replaced by the count of slots whose
candidate ordering begins with node `i`, provided `slotCount * v_min >= 10000 * V`.

`PROP-023`. Under `range` with `derived` assignment, the bound of `PROP-020` MUST hold with the
sample replaced by the set of all ranges and `c_i` replaced by the count of ranges whose candidate
ordering begins with node `i`, provided the range count times `v_min` is at least `10000 * V`. No
bound over keys is stated, because range extents are authored rather than derived.

`PROP-024`. The reduction `keyHash(rk) mod slotCount` distributes keys over slots with a bias per
slot of at most `slotCount / 2^64`. A balance bound over keys under `slot` MUST ignore that bias.

`PROP-025`. Under `slot` with `explicit` assignment, `range` with `explicit` assignment, and
`directory`, this specification states no balance bound. An implementation reports observed balance
against weight and changes no ordering.

`PROP-026`. The precondition of `PROP-023` is met only by a document authoring at least
`10000 * V / v_min` ranges, which is at least 20000 for the two-node topology on which the bound
first says anything. Range extents are authored rather than derived, so no topology of a workable
size reaches it. `PROP-023` therefore states a bound and carries no executable test, and a
conformance suite MUST report it as uncovered by a witness rather than as covered.

#### Proportionality

`PROP-030`. Under `rendezvous`, the probability that node `i` is the first candidate for a routing
key drawn uniformly at random MUST be `v_i / V`, treating the hash as a random function. The
proportionality is exact rather than asymptotic, and it holds for every distribution of weights.

`PROP-031`. The node identity tie-break perturbs `PROP-030` by at most `V^2 / 2^65`, which is the
bound on the probability that two eligible nodes produce equal maximum scores for one routing key.
A conformance test MUST NOT assert `PROP-030` more tightly than that bound allows.

`PROP-032`. `PROP-030` MUST hold without any floating-point operation in the library. The
proportionality is a property of the distribution of the maximum of `v_i` independent uniform
64-bit values, not a quantity the library computes.

`PROP-033`. Under `slot` with `derived` assignment and `range` with `derived` assignment, `PROP-030`
MUST hold with the routing key replaced by the slot index and by the `shardId` respectively, over
shards rather than over keys.

#### Purity

`PROP-040`. The candidate ordering MUST be a function of the snapshot, the routing key, and the
eligible node set alone.

`PROP-041`. Replacing the health view with any other health view MUST NOT change any candidate
ordering, any shard identifier, or any eligible node set.

`PROP-042`. Advancing the clock, changing the time zone, and changing the monotonic time source MUST
NOT change any candidate ordering.

`PROP-043`. Seeding or reseeding any random source available to the process MUST NOT change any
candidate ordering.

`PROP-044`. Changing caller identity, thread identity, process identity, locale, or default
character encoding MUST NOT change any candidate ordering.

`PROP-045`. Two callers holding the same snapshot MUST compute the same owner for the same key.
They may attempt different nodes, because the health filter under `FAIL-*` removes entries from a
preference list and never reorders it.

#### Exemptions

`PROP-050`. A routing key matched by an override entry carrying a `pin` is exempt from `PROP-010`
through `PROP-024` and from `PROP-030` through `PROP-033`. Its ordering is authored, so its balance
and its movement are properties of the document rather than of the strategy.

`PROP-051`. A routing key matched by an override entry carrying a `constrain` and no `pin` carries
every property of this section within its constrained eligible set. `E`, `V`, and `T` MUST be
computed over the constrained set, and a conformance test MUST partition its sample by matched
entry before evaluating any bound.

`PROP-052`. A conformance vector asserting a balance bound or a movement bound MUST exclude pinned
routing keys from its sample, and MUST state which override entry it excluded.

## Replication and failover

The preference list builder turns a candidate ordering into a replica prefix and a fallback tail,
degrading the failure domain spread where no placement satisfies it. A caller-local health view then
filters that list into an attempt sequence, which the caller walks under a limit and a retry
budget.

### Replication factor

The **effective replication factor** of a routing key, written `n`, is the number of distinct nodes
a preference list names as replicas. It is a u32 and it is fixed for the lifetime of a routing call.

`REPL-001`. Where the `replication` member is absent, or is present without `factor`, the effective
replication factor MUST be 1.

`REPL-002`. Where no override matches the routing key, the effective replication factor MUST be the
value of `replication.factor`.

`REPL-003`. Where the override matched under `PLACE-*` carries a `factor`, that value MUST supersede
`replication.factor` for the keys that entry matches. At most one override matches a routing key, so
at most one `factor` supersedes.

`REPL-004`. An effective replication factor of 0 is a validation failure (`TOPO-*`). An
implementation MUST NOT route a key at factor 0.

`REPL-005`. An implementation MUST NOT infer a replication factor from the length of a `slot`
assignment entry's `nodes`, a `range` entry's `nodes`, a `directory` entry's `nodes`, or an
override's `pin`. Those lists are candidate orderings; the effective replication factor alone
decides how many of their entries are replicas.

`REPL-006`. The topology document carries no per-shard replication factor. An implementation MUST
NOT accept one, and MUST NOT derive one from a shard identifier.

### Preference list construction

The preference list builder consumes the candidate ordering produced under `PLACE-*` and produces a
preference list: a replica prefix of at most `n` entries followed by the fallback tail. The length
of the replica prefix is written `r`, where `r` is at most `n`.

`REPL-010`. The builder MUST take exactly three inputs: the candidate ordering for the routing key,
the effective replication factor, and the spread requirement described under `SPREAD-*`. It MUST
read nothing else.

`REPL-011`. The builder MUST NOT admit a node identity that the replica prefix already holds. Every
candidate ordering reaching the builder is deduplicated under `PLACE-013`, and a pinned ordering
under `OVR-012`, so no ordering this specification defines offers an identity twice.

`REPL-012`. The replica prefix MUST be selected by walking the candidate ordering from its first
entry towards its last, admitting each entry that satisfies distinctness and the spread requirement
in force, and halting when the prefix holds `n` entries or the candidate ordering is exhausted.

`REPL-013`. The fallback tail MUST be the candidate ordering with every member of the replica prefix
removed, in candidate ordering order. Entries skipped for spread therefore appear in the tail at
their original relative position.

`REPL-014`. The preference list MUST be the replica prefix followed by the fallback tail. Both parts
preserve the relative order of the candidate ordering, so the preference list inherits the total
order and the node-identity tie-break specified under `PLACE-*`.

`REPL-015`. An implementation MAY produce the preference list lazily, consuming only the prefix of
the candidate ordering a caller observes. A lazy implementation MUST produce the same sequence as an
eager one.

`REPL-016`. The preference list MUST be a pure function of the topology snapshot and the routing
key. It MUST NOT read health state, wall-clock or monotonic time, randomness, caller identity, or
the outcome of any previous routing call.

`REPL-017`. Each preference list entry MUST carry a role of `replica` for positions below `r` and
`fallback` for positions at or above `r`, and MUST carry its zero-based position.

### Replication shortfall

A shortfall is a preference list whose replica prefix is shorter than the effective replication
factor.

`REPL-020`. Where the candidate ordering holds fewer than `n` identities, or the spread requirement
in force admits fewer than `n` of them, the builder MUST produce a replica prefix of `r` entries
where `r` is less than `n`. It MUST NOT repeat a node identity, MUST NOT synthesise a node, and MUST
NOT promote a fallback tail entry into the replica prefix. An eligible node set smaller than `n` is
one way the ordering falls short; `REPL-021` names the others.

`REPL-021`. A shortfall MUST be classified by its limiting cause, over the candidate ordering rather
than over the eligible node set: `nodes` where the candidate ordering holds fewer than `n`
identities, and `domains` where it holds at least `n` and the spread requirement in force admits
fewer than `n` of them. An eligible node set smaller than `n`, an authored node list or an override
`pin` naming fewer than `n` eligible identities, and a derived assignment in which some eligible
nodes carry a virtual node count of 0 all shorten the candidate ordering, and all classify as
`nodes`.

`REPL-022`. A shortfall MUST emit an event. The event MUST carry the effective replication factor,
the achieved replica count `r`, the limiting cause, the shard identifier where the strategy names
shards, and the fencing token. The event name is given by the observability contract.

`REPL-023`. The shortfall event MUST NOT carry the key, the routing key, or any prefix of either.

`REPL-024`. An implementation MUST emit at most one shortfall event per combination of epoch, shard
identifier, and limiting cause. Where the strategy names no shards, it MUST emit at most one
shortfall event per combination of epoch and limiting cause.

`REPL-025`. A shortfall MUST NOT fail the routing call. A preference list shorter than `n` is a
routing decision like any other, and the caller decides whether it is sufficient.

### Failure domain spread

Two nodes share a failure domain at a level when their domain identifiers agree at that level and at
every coarser level, as specified under `TOPO-*`.

`SPREAD-001`. A spread requirement at level `L` is satisfied by a replica prefix when no two of its
entries share a failure domain at `L`.

`SPREAD-002`. Node distinctness MUST be enforced unconditionally and MUST NOT be degraded. It is not
a member of `replication.spread` and no policy relaxes it.

`SPREAD-003`. Where `replication.spread` is absent or empty, or where `domainLevels` is empty,
distinctness MUST be the only constraint the builder applies.

`SPREAD-004`. The spread requirement MUST apply to a candidate ordering produced by an override
`pin` exactly as it applies to a strategy-produced ordering. A pinned key is exempt from the balance
bound and the minimal movement bound; it is not exempt from spread.

`SPREAD-005`. `replication.spread` MUST be read as an ordered list, coarsest level first, in the
same relative order as `domainLevels`.

### Spread degradation

Degradation is expressed as a ladder of relaxation stages over the spread level list. Let `spread`
hold `m` levels, coarsest first.

`SPREAD-010`. A **relaxation stage** `k`, for `k` in `0` to `m` inclusive, MUST enforce node
distinctness together with the finest `m-k` levels of `spread`, which are the levels at indices `k`
through `m-1`. Stage `0` enforces every named level; stage `m` enforces distinctness alone.

`SPREAD-011`. `select(k)` MUST be the replica prefix that `REPL-012` produces with the constraints
of stage `k` in force, which are `spread[k]` through `spread[m-1]`.

```
select(k)  =  select_with(candidates, n, spread[k .. m-1])

select_with(candidates, n, levels):
    prefix = []
    for x in candidates:                       # candidate ordering, in order
        if identity(x) in prefix:              continue
        if shares_domain(x, prefix, levels):   continue
        append x to prefix
        if length(prefix) == n: break
    return prefix

shares_domain(x, prefix, levels):
    for L in levels:
        for y in prefix:
            if domain_path(x, L) == domain_path(y, L): return true
    return false
```

`domain_path(x, L)` is the tuple of `x`'s domain identifiers from the coarsest level through `L`,
compared as a sequence of byte sequences.

`SPREAD-012`. Under `spreadPolicy` of `relaxed`, the builder MUST use `select(k)` for the smallest
`k` in `0` to `m` for which `length(select(k))` equals `n`.

`SPREAD-013`. Under `relaxed`, where no `k` in `0` to `m` yields `n` entries, the builder MUST use
`select(m)`. Stage `m` admits the greatest number of entries any stage admits, so this is the
longest replica prefix available.

`SPREAD-014`. Under `spreadPolicy` of `strict`, the builder MUST use `select(0)` and MUST NOT
evaluate any other stage. Where `length(select(0))` is less than `n`, the result is a shortfall
under `REPL-020` with cause `domains`.

`SPREAD-015`. The routing decision MUST report the relaxed levels: the `k` coarsest levels of
`spread` that the chosen stage does not enforce, which are the levels at indices `0` through `k-1`,
in `spread` order. Under `strict` and under `relaxed` at stage `0` this list MUST be empty.

`SPREAD-016`. Relaxation MUST emit an event carrying the relaxed levels, the chosen stage, the shard
identifier where the strategy names shards, and the fencing token. `REPL-023` and `REPL-024` apply
to it unchanged.

`SPREAD-017`. An implementation MUST evaluate at most `m + 1` stages for one routing key. Since
`domainLevels` holds at most eight levels, at most nine stages exist.

`SPREAD-018`. `domain_path` at a level extends `domain_path` at every coarser level, so a stage's
coarsest enforced level decides which entries it admits. Stage `k`, for `k` below `m`, MUST admit
exactly the entries that enforcing `spread[k]` alone admits.

`SPREAD-019`. The ladder MUST be ordered from the strongest constraint at stage `0` to node
distinctness at stage `m`. An entry that stage `k` admits against a set of already admitted entries,
stage `k + 1` MUST also admit against that set. An implementation MUST NOT reorder the stages and
MUST NOT omit one. The replica prefixes the stages produce need not be nested, because the walk
halts at `n`, and a weaker stage may fill the prefix before it reaches an entry a stronger stage
admitted.

`SPREAD-020`. The chosen stage MUST be a pure function of the topology snapshot and the routing key.
Two callers holding the same snapshot MUST choose the same stage, and therefore the same preference
list, for the same routing key.

`SPREAD-021`. The fallback tail MUST NOT be constrained by spread at any stage. The tail is the
remainder of the candidate ordering and carries no spread property.

### Node health state machine

Health state is runtime state held by a caller. It is derived from signals the caller observes and
is never agreed between callers.

`HEALTH-001`. The health state of a node MUST be exactly one of `unknown`, `available`, `suspect`,
`probation`, and `unavailable`.

`HEALTH-002`. Health state MUST NOT be serialised into a topology document, MUST NOT appear in a
fencing token, and MUST NOT be exchanged between callers by the library. A topology document member
naming a health state is a validation failure under the unknown-member rule.

`HEALTH-003`. The health state names MUST remain disjoint from the administrative state names
`active`, `joining`, `draining`, and `leaving`. An implementation MUST NOT map one vocabulary onto
the other.

`HEALTH-004`. A node for which no signal has been ingested MUST hold health state `unknown`. A
caller that ingests no signals therefore filters nothing, and its attempt sequence equals its
preference list.

`HEALTH-005`. Health state MUST affect a routing call only as this table gives.

| Health state | Effect on the attempt sequence |
|---|---|
| `unknown` | the entry is attemptable |
| `available` | the entry is attemptable |
| `suspect` | the entry is attemptable |
| `probation` | the entry is attemptable on an admitted probe, and skipped otherwise |
| `unavailable` | the entry is skipped |

`HEALTH-006`. Health entries MUST be keyed by node identity and MUST survive an epoch change. An
entry whose identity is absent from the snapshot in force MUST NOT affect any routing call, and MAY
be evicted once absent for longer than the ejection reset interval.

### Health signal ingestion

The library owns the state machine and ingests signals. It opens no connection, sends no probe, and
produces no signal of its own.

`HEALTH-010`. An implementation MUST expose this ingestion surface.

```
enum HealthState:    unknown | available | suspect | probation | unavailable
enum Outcome:        success | failure | timeout | refused | cancelled

record HealthSignal:
    node:      NodeId            # node identity as UTF-8 bytes
    outcome:   Outcome
    observed:  Instant           # monotonic reading, u64 milliseconds

interface HealthView:
    stateOf(node: NodeId) -> HealthState
    report(signal: HealthSignal)
    advance(now: Instant)                    # evaluates timers; ingestion also evaluates them
    onSnapshotInstalled(snapshot: TopologySnapshot)
    admitProbe(node: NodeId) -> boolean
```

`HEALTH-011`. `report` MUST accept a signal for any node identity, including one absent from the
snapshot in force. `stateOf` MUST answer `unknown` for an identity it holds no entry for.

`HEALTH-012`. `observed` MUST be an `Instant` read from the monotonic source of `CORE-004`. An
implementation MUST NOT read a wall clock for health, and MUST ignore a signal whose `observed`
value precedes the newest value already ingested for that node.

`HEALTH-013`. The `HealthView` is an extension point. An integrator MAY supply an
implementation of its own. Any implementation, supplied or built in, MUST confine its effect to
the table in `HEALTH-005`, and MUST NOT reorder a preference list.

`HEALTH-014`. An implementation MUST supply a built-in `HealthView` implementing the state machine
specified under `HEALTH-020` to `HEALTH-055`. The built-in implementation is what the conformance
suite exercises.

`HEALTH-015`. Where `advance` evaluates timers for several nodes at one instant, it MUST evaluate
them in node identity order, ascending as unsigned UTF-8 bytes, so that a cap or a guard applied
across nodes resolves identically in every implementation.

`HEALTH-016`. `onSnapshotInstalled` MUST be called with every snapshot the library installs, before
the snapshot is visible to a routing call, and MUST be the only way a `HealthView` learns the
placement set that `HEALTH-030` compares against and the set size that `HEALTH-034` bounds. An
implementation that supplies no placement set to a `HealthView` MUST NOT run outlier ejection in it.
A `HealthView` MAY ignore the call, in which case `HEALTH-030` does not run and `HEALTH-034` refuses
no transition.

`HEALTH-017`. `admitProbe` MUST be the call through which the probe counter of `HEALTH-051` is
incremented and admission is decided. `route` and `routeForRead` MUST call it once for each entry in
`probation` that they reach in preference list order. `explain` MUST NOT call it, under `OBS-045`,
and MUST report `attemptable` for such an entry as the value a probe-free evaluation gives. For a
node in any other health state, `admitProbe` MUST NOT be called and the entry's `attemptable` value
MUST be taken from `HEALTH-005`.

### Health aggregation

`HEALTH-020`. Observations MUST be aggregated over a sliding window of `windowMillis`, divided into
`bucketCount` buckets of equal length. Each bucket MUST hold a u32 success count and a u32 failure
count. A bucket whose end precedes `now - windowMillis` MUST be discarded rather than decayed.

`HEALTH-021`. Every health computation MUST use unsigned integer arithmetic. An implementation MUST
NOT use floating point anywhere in health aggregation, in ejection, in probation admission, or in
retry budgeting. Integer division MUST truncate towards zero.

`HEALTH-022`. `success` MUST count as a success. `failure`, `timeout`, and `refused` MUST count as
failures. `cancelled` MUST count as neither. An implementation MUST NOT classify a caller-supplied
outcome differently from this mapping; a caller that treats an application-level response as a
success reports `success`.

`HEALTH-023`. The failure percentage of a node, written `f(x)`, MUST be `failures * 100 / total`,
where `total` is the sum of the success and failure counts over the window and the division
truncates. Where `total` is 0, `f(x)` MUST be 0.

`HEALTH-024`. A node MUST hold a u32 consecutive failure counter, incremented by each failure and
reset to 0 by each success.

`HEALTH-025`. A node MUST hold a u32 ejection count, incremented on each entry into `unavailable`
and reset to 0 once the node has held `available` continuously for `ejectionResetMillis`.

### Outlier ejection

Outlier ejection compares a node against its peers, so that a node failing while its peers succeed
is ejected sooner than the absolute thresholds alone would eject it.

`HEALTH-030`. The comparison set MUST be the nodes of the placement set at the epoch in force whose
window total is at least `minimumSamples`. Where the comparison set holds fewer than
`outlierMinimumNodes` members, outlier ejection MUST NOT run.

`HEALTH-031`. The peer median MUST be the median of `f(x)` over the comparison set. Where the
comparison set holds an even number of members, the median MUST be the lower of the two central
values.

`HEALTH-032`. A node MUST be an outlier when `f(x)` is at least the peer median plus
`outlierMarginPercent`.

`HEALTH-033`. Where several nodes qualify for ejection at one evaluation, they MUST be considered in
descending `f(x)`, then in node identity order ascending as unsigned UTF-8 bytes.

`HEALTH-034`. A transition into `unavailable` MUST be refused where it would take the count of nodes
in `unavailable` above `maxEjectionPercent` of the placement set size. A node whose transition is
refused MUST hold `suspect`. The test is

```
(ejected + 1) * 100 > maxEjectionPercent * placementSetSize
```

The comparison is exact over both products under `CORE-005`.

### Health state transitions

`HEALTH-040`. `unknown` MUST become `available` on a success.

`HEALTH-041`. `unknown` or `available` MUST become `suspect` on a failure.

`HEALTH-042`. `suspect` MUST become `available` where the window holds no failure and the window
total is at least `minimumSamples`.

`HEALTH-043`. `suspect` MUST become `unavailable`, subject to `HEALTH-034`, where any of these
holds: the consecutive failure counter is at least `consecutiveFailureThreshold`; the window total
is at least `minimumSamples` and `f(x)` is at least `failureRatePercent`; or the node is an outlier
under `HEALTH-032`.

`HEALTH-044`. `unavailable` MUST become `probation` once `ejectionMillis` has elapsed since entry
into `unavailable`.

`HEALTH-045`. `probation` MUST become `available` once `probationMillis` has elapsed with no failure
ingested for the node.

`HEALTH-046`. `probation` MUST become `unavailable` on a single failure, incrementing the ejection
count. `HEALTH-034` MUST NOT refuse this transition, because the node was already ejected when
probation began.

`HEALTH-047`. A transition MUST be evaluated on each ingested signal for the node and on each
`advance`. An implementation MUST NOT require a caller to poll for a timer transition to take
effect; an ingested signal evaluates timers first.

`HEALTH-048`. An implementation MUST emit an event on every transition, carrying the node identity,
the prior state, the new state, and the trigger. The event name is given by the observability
contract.

### Probation and flap containment

`HEALTH-050`. The ejection interval MUST be evaluated at entry into `unavailable` as

```
ejectionMillis = min(baseEjectionMillis << min(ejectionCount - 1, 10), maxEjectionMillis)
```

Repeated ejection therefore lengthens the interval, and a node that flaps is attempted less often
rather than more.

`HEALTH-051`. A node in `probation` MUST hold a u32 probe counter, initialised to 0 at entry into
`probation`. Each call to `admitProbe` for that node MUST increment it, and `admitProbe` MUST answer
true exactly when the incremented value modulo `probationDivisor` equals 1. `HEALTH-017` states
which calls invoke it.

`HEALTH-052`. Probation admission MUST NOT alter the preference list, the position of any entry, or
the identity of the primary. It decides only whether one caller attempts one entry on one call.

`HEALTH-053`. Two callers, or one caller on two calls, MAY admit different probes. This is a
permitted variation under `FAIL-010`.

`HEALTH-054`. An implementation MUST NOT transition a node out of `unavailable` on a signal alone.
Only the elapse of `ejectionMillis` reaches `probation`, so a stale success cannot cancel an
ejection.

`HEALTH-055`. An implementation MUST expose the parameters of the built-in state machine, and MUST
use the defaults given.

| Parameter | Default | Meaning |
|---|---|---|
| `windowMillis` | 30000 | length of the sliding observation window |
| `bucketCount` | 10 | buckets the window is divided into |
| `minimumSamples` | 20 | window total below which rate tests do not apply |
| `failureRatePercent` | 50 | window failure percentage that ejects a node |
| `consecutiveFailureThreshold` | 5 | consecutive failures that eject a node |
| `baseEjectionMillis` | 30000 | ejection interval at the first ejection |
| `maxEjectionMillis` | 300000 | ceiling on the ejection interval |
| `probationMillis` | 30000 | failure-free interval that ends probation |
| `probationDivisor` | 16 | one attempt in this many is admitted during probation |
| `outlierMarginPercent` | 30 | margin above the peer median that marks an outlier |
| `outlierMinimumNodes` | 5 | comparison set size below which outlier ejection is off |
| `maxEjectionPercent` | 50 | ceiling on the share of the placement set held `unavailable` |
| `ejectionResetMillis` | 600000 | continuous `available` interval that resets the ejection count |

### Deterministic ownership and caller-local attempts

The preference list is agreed and the attempt sequence is local. The attempt sequence is the
subsequence of the preference list that a caller's health view leaves attemptable, in preference
list order.

`FAIL-001`. Two callers holding the same topology identifier and epoch MUST compute the same
preference list for the same key: the same node identities, in the same order, with the same replica
prefix length. An implementation MUST NOT let health state, attempt history, caller identity, or
elapsed time change it.

`FAIL-002`. The health filter MUST produce the attempt sequence by removing entries from the
preference list, reordered under `READ-*` where a caller requested read affinity, and MUST NOT
reorder the entries that remain. An implementation MUST NOT promote a healthier entry above a less
healthy one.

`FAIL-003`. The attempt sequence MUST be an order-preserving subsequence of the preference list. A
caller MUST attempt its entries in that order.

`FAIL-004`. The primary of a shard MUST be the head of the preference list, whatever its health
state. An implementation MUST NOT report a different node as primary because the head is
`unavailable`.

`FAIL-005`. Read affinity under `READ-*` is the only reordering the library performs. It MUST be
applied to the preference list before the health filter, and it MUST be applied only when a caller
requests it.

`FAIL-010`. A caller MAY vary: the contents of its health view; the attempt limit it sets; the retry
budget it configures; whether it requests read affinity and with what affinity path; which probes
probation admits; the instant at which it attempts; and whether it attempts at all.

`FAIL-011`. A caller MUST NOT vary: the preference list; the order of the preference list; the
replica prefix length; the identity of the primary; the effective replication factor; or the shard a
key belongs to. None of these is a caller-supplied input to a routing call.

`FAIL-012`. The health filter MUST NOT yield an empty attempt sequence from a non-empty preference
list. Where every entry is skipped, the attempt sequence MUST be the whole preference list, ordered
as the preference list orders it, and the routing decision MUST record that the filter failed open.

`FAIL-013`. The routing decision MUST expose the preference list, each entry's role, each entry's
position, and each entry's health state at the instant the decision was made. A caller that needs
the unfiltered list MUST be able to obtain it from the decision.

### Attempt depth and exhaustion

`FAIL-020`. The caller decides how deep to walk. The library MUST NOT open a connection, MUST NOT
attempt a node, and MUST NOT retry anything.

`FAIL-021`. An implementation MUST expose an attempt limit on a routing call, counted in attempts
rather than in preference list positions. The attempt sequence MUST be truncated to that limit.

`FAIL-022`. The default attempt limit MUST be `n + 2`, clamped to the length of the attempt
sequence. A topology at factor 1 therefore offers two fallback attempts by default, and a topology
at factor 3 offers two beyond the replica prefix.

`FAIL-023`. An implementation MUST expose the attempt walk as this surface.

```
interface AttemptSequence:
    next() -> NodeId | exhausted
    recordOutcome(node: NodeId, outcome: Outcome, at: Instant)
    remaining() -> u32
```

`next` answers the next attemptable entry in preference list order, or `exhausted`. `recordOutcome`
forwards a `HealthSignal` to the health view and accounts the attempt against the retry budget.

`FAIL-024`. `next` MUST answer `exhausted` when the attempt limit is reached, when the retry budget
refuses a further attempt, or when the preference list is spent, whichever comes first.

`FAIL-025`. An exhausted attempt sequence and an empty preference list MUST be distinct conditions.
An empty eligible node set, or a strategy that matches no entry, yields the no-candidate condition
(`ERR-*`). An attempt sequence that runs out after at least one attempt yields the exhaustion
condition (`ERR-*`), which carries the preference list, the attempted node identities in order, the
outcome recorded for each, and the fencing token.

`FAIL-026`. Exhaustion MUST emit an event carrying the attempted count, the preference list length,
the shard identifier where the strategy names shards, and the fencing token. `REPL-023` applies to
it unchanged.

`FAIL-027`. An implementation MUST NOT wait, sleep, or back off inside a routing call. Spacing
between attempts belongs to the caller.

### Retry budgets

A **retry budget** bounds the share of load that retries contribute, so that a partial failure
cannot multiply the request rate the cluster sees.

`FAIL-030`. An implementation MUST account, over a sliding window of `retryBudgetWindowMillis`, the
count of first attempts and the count of retries. The first `next` of an attempt sequence is a first
attempt; every later `next` is a retry.

`FAIL-031`. A retry MUST be permitted exactly when this holds over the window, evaluated in unsigned
integer arithmetic.

```
retries * 100 <= retryBudgetPercent * firstAttempts + 100 * retryBudgetMinimum
```

Both sides MUST be evaluated exactly under `CORE-005`, whatever the window totals reach.

`FAIL-032`. A first attempt MUST always be permitted. The budget MUST NOT be able to make a key
unroutable.

`FAIL-033`. The budget MUST be held per router instance and MUST span every key, shard, and node the
instance routes. A per-key budget would permit a storm assembled from many keys.

`FAIL-034`. Where the budget refuses a retry, `next` MUST answer `exhausted` and the exhaustion
condition MUST name the budget as its cause, distinguishing it from a preference list that ran out.

`FAIL-035`. The budget parameters MUST be these, with the defaults given.

| Parameter | Default | Meaning |
|---|---|---|
| `retryBudgetWindowMillis` | 10000 | length of the accounting window |
| `retryBudgetPercent` | 20 | retries permitted as a percentage of first attempts |
| `retryBudgetMinimum` | 3 | retries permitted in the window regardless of the percentage |

### Hinted handoff hook

`FAIL-040`. The library MUST NOT implement hinted handoff and MUST NOT implement sloppy quorum.
It stores no hint, replays no write, and holds no record of a substitution beyond the
notification in `FAIL-042`.

`FAIL-041`. The fallback tail is the substitution mechanism. A caller that writes to a fallback tail
entry in place of a skipped replica performs a sloppy quorum of its own construction, and `REPL-017`
gives it the role labelling it needs to tell a replica from a substitute.

`FAIL-042`. An implementation MUST expose this optional hook.

```
interface HintObserver:
    onSubstitution(shard: ShardId | none, intended: list<NodeId>,
                   substitute: NodeId, token: FencingToken, at: Instant)
    onReplicaAvailable(node: NodeId, at: Instant)
```

`FAIL-043`. `onSubstitution` MUST be called when `recordOutcome` records a success for an entry
whose role is `fallback` while at least one entry whose role is `replica` was skipped earlier in the
same attempt sequence. `intended` MUST hold the skipped replica identities in preference list order.

`FAIL-044`. `onReplicaAvailable` MUST be called on a node's transition from `probation` to
`available`, and MUST NOT be called on the transition from `unavailable` to `probation`. A caller
replaying hints therefore does not replay them onto a node that is still being probed.

`FAIL-045`. Where no `HintObserver` is supplied, an implementation MUST NOT compute the `intended`
list. The hook is the only consumer of that computation.

### Read routing and read affinity

Reads and writes share one preference list. Read affinity is a separate call that reorders within
the replica prefix and leaves ownership untouched.

`READ-001`. A routing call MUST return the same preference list for a read as for a write. An
implementation MUST NOT hold a second placement for reads.

`READ-010`. An implementation MUST expose read affinity as `routeForRead` under `CORE-030`, an
explicitly requested call distinct from the routing call that serves writes. Its affinity argument
has this shape.

```
record AffinityRequest:
    level:   string              # a declared domain level
    path:    list<bytes>         # domain identifiers, coarsest first, through level
    window:  u32 | none          # none takes the replica prefix length, CFG-020
```

`READ-011`. `level` MUST name a level declared in `domainLevels`. A request naming an undeclared
level MUST fail with the invalid-argument condition (`ERR-*`) and MUST NOT fall back to no affinity.

`READ-012`. `window` MUST be clamped to the replica prefix length `r`. Read affinity MUST NOT move
an entry across the boundary between the replica prefix and the fallback tail, and MUST NOT change
`r`.

`READ-013`. The reordering MUST be a stable partition of the first `window` entries of the
preference list into two groups: entries whose domain path agrees with `path` at `level` and at
every coarser level, then the remaining entries. Each group MUST preserve preference list order.

`READ-014`. Entries at positions at or above `window` MUST be unchanged, in both identity and
position.

`READ-015`. `routeForRead` MUST NOT read health state. The reordering is a pure function of the
preference list and the affinity request, so the health filter remains the only health-dependent
step of a routing call.

`READ-016`. The routing decision MUST carry the unreordered preference list alongside the reordered
one, and MUST identify the primary as the head of the unreordered list.

`READ-020`. A caller MUST NOT use a read-affinity result to select a write target. Write routing
uses the unreordered preference list.

`READ-021`. Read affinity MUST NOT be requested where the caller requires reading from the primary.
The library cannot detect that requirement and enforces nothing; the reordered list makes no
statement about which replica is most recently written.

`READ-022`. Read affinity MUST NOT change the shard a key belongs to, the effective replication
factor, the replica prefix membership, or the fencing token.

`READ-023`. Where a caller requests read affinity with its own node's domain path, and no replica
shares that path, the reordered list MUST equal the preference list. An absent local replica is not
an error.

## Topology change and rebalancing

A document becomes a snapshot through a fixed pipeline and a monotonicity check, and the ownership
delta between two snapshots names the shards that moved. A recipient compares a fencing token to
decide whether to serve, and a handoff carries one shard from a source node to a destination node at
a rate the integrator controls.

### Topology loading and snapshot lifecycle

A topology snapshot is the immutable, validated, prepared form of one topology document. The
library holds at most one snapshot in force at a time, and replaces it as a whole.

#### Load pipeline

`TOPO-001`. An implementation MUST process every document a provider delivers through the following
stages, in this order, and MUST abandon the document at the first stage that fails.

1. Decode the octets as JSON.
2. Validate against the published schema for the document's `formatVersion`.
3. Validate the semantic rules of [`20-topology-format.md`](20-topology-format.md).
4. Compute the canonical form and the topology digest.
5. Compare `topologyId` and `epoch` against the snapshot in force.
6. Prepare the placement strategy over the document.
7. Install the resulting snapshot.

`TOPO-002`. Stage 1 MUST reject a document in which one JSON object carries two members of the same
name, and MUST reject a document carrying a JSON string that holds an unpaired surrogate code point,
whether written literally or through a `\uD800` through `\uDFFF` escape. A duplicate member name
has no single value and an unpaired surrogate has no UTF-8 encoding, so neither document has one
canonical form under stage 4. An implementation MUST NOT resolve a duplicate member by taking the
first or the last occurrence, and MUST NOT substitute a replacement character for an unpaired
surrogate.

`TOPO-011`. A document that fails any stage MUST be rejected whole. An implementation MUST NOT
repair a document, MUST NOT drop an offending node, and MUST NOT merge any part of a rejected
document into the snapshot in force.

`TOPO-021`. An implementation MUST complete stage 6 before stage 7. A routing call MUST NOT observe
a snapshot whose placement preparation has not completed.

`TOPO-031`. Stages 1 through 6 MUST be free of observable effect on routing. A document rejected at
any stage leaves the snapshot in force unchanged, including its freshness.

`TOPO-041`. An implementation MUST serialise the pipeline so that at most one document is being
compared against the snapshot in force at a time. Two documents accepted concurrently would make the
accepted sequence non-monotonic.

#### Monotonicity and acceptance

`TOPO-051`. An implementation MUST compare a candidate document against the snapshot in force using
integer comparison of `epoch` and octet comparison of `topologyId`. The comparison MUST NOT read a
clock, a provider revision, a document timestamp, or any floating point value.

`TOPO-061`. The acceptance outcome MUST be exactly one of the following, and an implementation MUST
NOT define others.

| Condition | Outcome |
|---|---|
| No snapshot in force, `epoch` at or above `minEpoch` | accept and install |
| `topologyId` differs from the one in force or configured | reject, topology conflict |
| `epoch` below `minEpoch` | reject, stale topology |
| `epoch` below the epoch in force | reject, stale topology |
| `epoch` equal, digest equal | accept as a no-op, refresh freshness |
| `epoch` equal, digest differs | reject, topology conflict |
| `epoch` above the epoch in force | accept and install |

`TOPO-071`. An implementation MUST accept a `minEpoch` value from the integrator and MUST reject any
document whose `epoch` is below it, including the first document after a restart. Where `minEpoch`
is unset, monotonicity begins at the first accepted document.

`TOPO-081`. An implementation MUST NOT assign, increment, or infer an epoch, and MUST NOT accept a
document whose epoch is lower than the epoch in force under any condition, including an identical
digest to a previously accepted document.

`TOPO-091`. Where no `topologyId` is configured, an implementation MUST adopt the identifier of the
first accepted document and MUST check every later document against it.

#### Snapshot installation and visibility

`TOPO-101`. A snapshot MUST be immutable once installed. No field of a snapshot, and no product of
its placement preparation, may change after installation.

`TOPO-111`. Installation MUST be a single atomic replacement of the reference a routing call reads.
No intermediate state may be observable.

`TOPO-121`. A routing call MUST read the snapshot reference exactly once, at entry, and MUST compute
its whole result from that snapshot. This satisfies invariant 12: a routing call sees exactly one
snapshot for its whole lifetime.

`TOPO-131`. An implementation MUST keep a snapshot readable until every call that acquired it has
completed. Installing a newer snapshot MUST NOT invalidate a snapshot a call is still reading.

`TOPO-141`. Freshness and staleness MUST be held outside the snapshot. A snapshot marked stale is
the same snapshot with the same digest; staleness changes what the holder reports and never what the
snapshot contains.

`TOPO-151`. Every routing decision MUST carry the fencing token of the snapshot that produced it.

#### Snapshot retention

`TOPO-161`. An implementation MUST retain the snapshot in force and MAY retain a bounded number of
previously installed snapshots for the same `topologyId`, in descending epoch order. The retention
depth is a non-negative integer supplied by the integrator and defaults to 3 previous snapshots.

`TOPO-171`. A retained snapshot MUST be usable only for the recipient-side evaluation specified
in `FENCE-081` and for plan basis checks. An implementation MUST NOT route a call against a
retained snapshot.

`TOPO-181`. An implementation MUST discard retained snapshots whose `topologyId` differs from the
one in force.

#### Validation without installation

`TOPO-191`. An implementation MUST expose a validation entry point that runs stages 1 through 4 and
6 of `TOPO-001` over a document and returns a snapshot without installing it and without consulting
monotonicity.

`TOPO-201`. A snapshot produced by `TOPO-191` MUST be admissible as the target of a migration plan.
This is how a handoff is prepared against an epoch the authority has published for planning and has
not yet published for routing.

#### Ownership delta

`TOPO-211`. An implementation MUST expose the ownership delta between two snapshots of the same
`topologyId` as the set of shards whose ordered replica set differs, and for each such shard the
nodes gained and the nodes lost. The replica set of a shard is the first `factor` entries of its
preference list as specified in `REPL-*`.

`TOPO-221`. Computing an ownership delta MUST be a pure function of the two snapshots. It MUST NOT
read health, a clock, or any handoff state.

`TOPO-231`. An implementation MUST refuse to compute an ownership delta between two snapshots whose
shard identity is not comparable. Shard identity is not comparable when the two snapshots differ in
`strategy.kind`, in `keyTransform`, in `hash.seed`, in `hash.algorithm`, or, under `slot`, in
`slotCount`.

`TOPO-241`. The size of an ownership delta is bounded by the minimal movement statement in `PROP-*`.
An implementation MUST NOT enlarge the delta by reordering entries that are unchanged as a set;
a shard whose replica set is the same set in a different order is a delta entry with no node gained
and no node lost.

### Fencing

#### Fencing token

`FENCE-001`. The fencing token is the ordered pair of `topologyId` and `epoch`. An
implementation MUST NOT include any other value in the ordering component of a token.

`FENCE-011`. A token MAY carry the topology digest as a third, diagnostic component. An
implementation MUST NOT use the digest in any ordering or acceptance decision at a recipient.

`FENCE-021`. An implementation MUST expose a canonical octet encoding of a token for transports that
need one:

```
tokenBytes = u32be(len(topologyId)) || topologyId || u64be(epoch)
```

where `topologyId` is its UTF-8 octets. An implementation MUST NOT use a delimited textual form as
the canonical encoding, because `topologyId` may contain any character.

`FENCE-031`. For a transport carrying named text fields, an implementation SHOULD present the token
as two fields named `sharder-topology-id` and `sharder-epoch`, the first carrying the identifier
verbatim and the second the epoch in decimal ASCII with no leading zeros and no sign.

#### Token propagation

`FENCE-041`. A caller SHOULD attach the fencing token of a routing decision to every request that
decision routes. A recipient that receives no token MUST treat the request as unfenced and MUST
apply the policy specified in `FENCE-131`.

`FENCE-051`. A recipient MUST NOT use a received token to change its own snapshot. A token is
evidence about the sender and never a topology update.

#### Recipient comparison

`FENCE-061`. An implementation MUST expose a recipient-side check with this shape.

```
check(token: FencingToken, routingKey: bytes, selfId: NodeId) -> Verdict

Verdict = {
    relation:        one of { same, senderBehind, senderAhead, unknownEpoch, identityMismatch },
    ownership:       one of { owner, notOwner, unknown },
    ownershipStable: boolean,
    currentOwner:    NodeId | none,
    localToken:      FencingToken
}
```

`FENCE-071`. An implementation MUST compute `relation` as follows, comparing `topologyId` as
unsigned octets and `epoch` as an unsigned integer.

| Condition at the recipient | `relation` |
|---|---|
| `topologyId` differs | `identityMismatch` |
| no snapshot in force | `unknownEpoch` |
| token epoch equals the epoch in force | `same` |
| token epoch below the epoch in force | `senderBehind` |
| token epoch above the epoch in force | `senderAhead` |

`FENCE-081`. An implementation MUST compute `ownership` by evaluating the preference list for
`routingKey` against the snapshot in force and reporting whether `selfId` appears within the first
`factor` entries. It MUST set `currentOwner` to the first entry of that preference list.

`FENCE-091`. An implementation MUST set `ownershipStable` to true when `relation` is `senderBehind`,
the snapshot at the token's epoch is retained under `TOPO-161`, and `selfId` appears within the
first `factor` entries of the preference list for `routingKey` under both that snapshot and the
snapshot in force. In every other case `ownershipStable` MUST be false. Where `relation` is
`senderBehind` and the token's epoch is not retained, `ownership` MUST be reported as computed and
`ownershipStable` MUST be false.

`FENCE-101`. The check MUST be a pure function of the token, the routing key, the node identity, and
the retained snapshots. It MUST NOT read health, a clock, randomness, or any handoff state, and MUST
NOT use floating point.

#### Recipient policy

`FENCE-111`. A recipient MUST refuse a request whose verdict has `relation` of `identityMismatch`.
Epochs under two identifiers are incomparable, so no recovery is available at the recipient.

`FENCE-121`. A recipient MUST refuse a request whose verdict has `ownership` of `notOwner`, and the
refusal MUST name `currentOwner`. A recipient MUST NOT serve a shard it does not own on the strength
of a sender's token.

`FENCE-131`. A recipient whose verdict has `relation` of `senderBehind` and `ownership` of `owner`
MUST refuse the request unless `ownershipStable` is true, in which case it MAY serve it. An
implementation MUST expose this choice as a policy with two values, `strict` and `stable`, and
`strict` MUST be the default. The same policy governs an unfenced request, which `strict` refuses
and `stable` serves.

`FENCE-141`. A recipient whose verdict has `relation` of `senderAhead` MUST NOT serve the request on
the strength of its own snapshot. It MUST request a topology refresh, MAY wait for one up to an
integrator-supplied deadline, and MUST refuse the request if its epoch has not reached the token's
epoch by that deadline. A recipient MUST NOT synthesise the sender's topology.

`FENCE-151`. A recipient whose verdict has `relation` of `unknownEpoch` MUST refuse the request and
MUST report the unready condition in `ERR-*` rather than the stale condition.

`FENCE-161`. A recipient participating in a handoff MUST take its answer from the handoff rules
in `MOVE-121` and `MOVE-131` where those rules apply to the shard, and MUST apply this section
only where they do not. A destination that has not committed cutover MUST refuse with
`currentOwner` set to the source, whatever its own epoch says.

#### Caller behaviour when fenced

`FENCE-171`. A caller that receives a refusal naming a `currentOwner` MAY retry the request against
that node. It MUST resolve the node's address from the `nodes` list of its own snapshot, which
includes nodes outside the placement set, and MUST refuse to retry where the identity is absent.

`FENCE-181`. A caller MUST NOT follow more than a bounded number of such redirects for one request.
The bound is an integer supplied by the integrator and defaults to 2.

`FENCE-191`. A caller MUST NOT retry a request against a node it has already attempted for that
request. A redirect that names an already attempted node MUST terminate the walk.

`FENCE-201`. A caller that receives a refusal carrying a `localToken` whose epoch exceeds its own
SHOULD request a topology refresh before retrying, and MUST NOT install that epoch on the strength
of the refusal.

`FENCE-211`. A caller that exhausts its redirect bound MUST surface the condition in `ERR-*` and
MUST NOT fall back to an arbitrary node.

### Shard ownership handoff

A handoff moves ownership of one shard from a source node to a destination node. The library
sequences the handoff and calls the integrator's movement hooks. It moves no data and speaks no
storage protocol.

#### Handoff states

`MOVE-001`. An implementation MUST model a handoff with exactly these states.

| State | Meaning |
|---|---|
| `planned` | admitted to the plan, no hook called |
| `preparing` | the destination is being made ready to receive |
| `transferring` | the bulk contents are being copied |
| `catchingUp` | the residue accumulated during the copy is being closed |
| `cutover` | the source is quiescing and the cutover record is being committed |
| `verifying` | the destination copy is being checked against the source |
| `cleanup` | the source copy is being released |
| `complete` | terminal, ownership moved and the source released |
| `aborting` | compensation is running after an abort |
| `aborted` | terminal, ownership did not move and no residue remains |
| `failed` | terminal, operator action is required |

`MOVE-011`. A handoff in `failed` MUST carry exactly one failure kind from the set `unverified`,
`residue`, `undetermined`, and `rollbackFailed`.

#### Transitions

`MOVE-021`. An implementation MUST permit exactly these transitions and no others.

| From | To | Trigger |
|---|---|---|
| none | `planned` | plan construction admits the shard |
| `planned` | `preparing` | the rate policy admits the handoff |
| `planned` | `aborted` | abort requested, or the plan is superseded |
| `preparing` | `transferring` | `prepare` returns success |
| `preparing` | `aborting` | abort requested, plan superseded, or attempts exhausted |
| `transferring` | `catchingUp` | `transfer` reports no bulk remaining |
| `transferring` | `aborting` | abort requested, plan superseded, or attempts exhausted |
| `catchingUp` | `cutover` | `catchUp` reports a residue at or below `catchUpResidualThreshold` |
| `catchingUp` | `transferring` | `catchUp` reports a residue above `reTransferResidualThreshold` |
| `catchingUp` | `aborting` | abort requested, plan superseded, or attempts exhausted |
| `cutover` | `verifying` | `commitCutover` returns a cutover record |
| `cutover` | `aborting` | `quiesce` fails and no cutover record exists |
| `cutover` | `failed` | the outcome of `commitCutover` is undetermined at the deadline |
| `verifying` | `cleanup` | `verify` returns success |
| `verifying` | `failed` | `verify` reports a mismatch, or attempts are exhausted |
| `cleanup` | `complete` | `cleanup` returns success or waived |
| `cleanup` | `failed` | `cleanup` attempts are exhausted |
| `aborting` | `aborted` | `rollback` returns success |
| `aborting` | `failed` | `rollback` attempts are exhausted |

`MOVE-031`. `complete`, `aborted`, and `failed` are terminal. An implementation MUST NOT transition
out of a terminal state, and MUST require a new plan to retry the shard.

`MOVE-041`. The cutover is the `cutover` to `verifying` transition. Before it the source is the
authoritative owner of the shard; after it the destination is. An implementation MUST NOT treat any
other transition, and MUST NOT treat the installation of a snapshot, as a change of authority.

#### Coordinator drive model

`MOVE-051`. The handoff coordinator MUST be a passive state machine. It MUST NOT start a thread,
MUST NOT schedule work, and MUST NOT call a movement hook except from a call the integrator makes.

`MOVE-061`. An implementation MUST expose the coordinator with this shape.

```
interface HandoffCoordinator:
    plan(from: TopologySnapshot, to: TopologySnapshot,
         hooks: MovementHooks, policy: MigrationPolicy)
        -> Result<MigrationPlan, Error>

interface MigrationPlan:
    delta()                     -> OwnershipDelta
    handoffs()                  -> iterator<HandoffId>
    state(id: HandoffId)        -> HandoffState
    step(clock: MonotonicClock) -> StepOutcome
    recover(clock: MonotonicClock) -> Result<unit, Error>
    abort(id: HandoffId, reason: string)
    abortAll(reason: string)
    onSnapshotInstalled(snapshot: TopologySnapshot)
    summary()                   -> map<HandoffState, u32>

StepOutcome = one of {
    idle,                          # nothing admissible under the rate policy
    progressed(id, unitsMoved),
    advanced(id, fromState, toState),
    deferred(id, retryAfterMillis),
    settled(id, terminalState)
}
```

`MOVE-062`. The `MonotonicClock` a call to `step` or `recover` supplies MUST be the source the plan
reads for the duration of that call, superseding the source of `CORE-004` for that call alone. An
implementation MUST NOT mix readings from the two sources within one call and MUST NOT carry a
reading from one call into the next.

`MOVE-071`. `step` MUST advance at most one handoff by at most one hook call. An implementation MUST
permit concurrent calls to `step` and MUST NOT advance the same handoff from two calls at once.

`MOVE-081`. `plan` MUST refuse, reporting the appropriate condition in `ERR-*`, when the two
snapshots fail `TOPO-231`, when the target snapshot's epoch is not above the source snapshot's, when
the strategy does not support orchestrated migration under `MOVE-241`, or when a destination named
by the delta is outside the placement set of the target snapshot.

`MOVE-091`. `onSnapshotInstalled` MUST supersede the plan when the installed snapshot's `epoch` is
neither the plan's source epoch nor its target epoch, or when its `topologyId` differs. Superseding
MUST abort every handoff that has not yet reached `cutover` and MUST let every handoff at `cutover`
or beyond run to a terminal state.

`MOVE-101`. A plan MUST NOT be created, advanced, or superseded as a side effect of installing a
snapshot. The library publishes the ownership delta and an event; the integrator decides whether to
migrate.

#### Movement hook interface

`MOVE-111`. An implementation MUST define the movement hooks with this shape.

```
interface MovementHooks:
    declare()                        -> HookDeclaration
    prepare(ctx)                     -> HookResult
    transfer(ctx, budget: u32)       -> TransferResult
    catchUp(ctx, budget: u32)        -> CatchUpResult
    quiesce(ctx)                     -> QuiesceResult
    commitCutover(ctx)               -> CutoverResult
    verify(ctx)                      -> VerifyResult
    cleanup(ctx)                     -> HookResult
    rollback(ctx)                    -> HookResult
    observe(ctx)                     -> Observation

HandoffContext ctx = {
    shardId, topologyId, fromEpoch, toEpoch,
    source: NodeId, destination: NodeId,
    attempt: u32, deadlineMillis: u32
}

HookDeclaration = {
    cutoverGuarantee: one of { linearisable, advisory },
    budgetUnit:       string,          # opaque to the library
    supportsRollback: boolean,
    supportsVerify:   boolean
}

HookResult      = one of { success, deferred(retryAfterMillis), retryable(reason),
                           permanent(reason) }
TransferResult  = HookResult plus { unitsMoved: u32, bulkRemaining: u64 }
CatchUpResult   = HookResult plus { unitsMoved: u32, residue: u64 }
QuiesceResult   = HookResult plus { leaseMillis: u32 }
CutoverResult   = one of { committed(CutoverRecord), alreadyCommitted(CutoverRecord),
                           lost(CutoverRecord), undetermined, retryable(reason),
                           permanent(reason) }
VerifyResult    = one of { matched, mismatched(detail), retryable(reason) }
Observation     = { cutoverRecord: CutoverRecord | none, destinationPrepared: boolean,
                    sourceQuiesced: boolean, sourceResidue: boolean }
CutoverRecord   = { shardId, topologyId, epoch, owner: NodeId, opaque: bytes }
```

`MOVE-121`. Until the cutover, the source MUST answer reads and writes for the shard and the
destination MUST refuse them with `currentOwner` set to the source. Whether the source also
replicates each write to the destination is the integrator's choice, declared outside the library;
the library requires only that the destination does not answer.

`MOVE-131`. After the cutover, the destination MUST answer reads and writes for the shard and the
source MUST refuse them with `currentOwner` set to the destination, whatever epoch either node
holds.

`MOVE-141`. The library MUST NOT interpret `budgetUnit`, `unitsMoved`, `bulkRemaining`, `residue`,
or the `opaque` member of a cutover record. It compares residue against integer thresholds and sums
units for reporting, and does nothing else with them.

#### Idempotence and retry

`MOVE-151`. `prepare`, `transfer`, `catchUp`, `quiesce`, `verify`, `cleanup`, `rollback`, and
`observe` MUST be idempotent. Calling any of them twice with the same context MUST leave the same
result as calling it once, and the coordinator MAY call any of them any number of times.

`MOVE-161`. `commitCutover` MUST be idempotent in outcome. A second call with the same context MUST
return `alreadyCommitted` with the record the first call committed, or `lost` with the record
another destination committed. It MUST NOT commit a second, different record.

`MOVE-171`. A hook result of `retryable` MUST be retried up to `maxAttemptsPerStep`, with the
backoff in `RATE-051`. A result of `permanent` MUST NOT be retried. A result of `deferred` MUST NOT
count against the attempt budget and MUST NOT be retried before `retryAfterMillis` has elapsed on
the supplied clock.

`MOVE-181`. `cleanup` MUST NOT be called before `verify` has returned `matched` for the same
context, or, where `supportsVerify` is false, before `commitCutover` has returned a record. This
ordering is what prevents the handoff from destroying the only surviving copy.

`MOVE-191`. `rollback` MUST NOT be called after a cutover record exists for the shard at the plan's
target epoch. Compensation after cutover is a new plan under a higher epoch.

#### Coordinator failure and recovery

`MOVE-201`. The coordinator's state MUST NOT be the authority on what has happened. An
implementation MUST treat the integrator's durable state, read through `observe`, as authoritative
whenever the two disagree.

`MOVE-211`. `recover` MUST call `observe` for every handoff whose state is not terminal, and MUST
assign a state from the observation using this mapping.

| Observation | Resumed state |
|---|---|
| a record whose `owner` is the destination and `epoch` is the target epoch | `verifying` |
| a record naming another owner, or another epoch | `aborting` |
| no record, `sourceQuiesced` true | `cutover` |
| no record, `destinationPrepared` true | `transferring` |
| no record, `destinationPrepared` false | `preparing` |

`MOVE-221`. A coordinator that restarts MUST rebuild its plan from the same two snapshots and MUST
call `recover` before its first `step`. Rebuilding is safe because plan construction is a pure
function of the two snapshots and the policy, and because every hook is idempotent.

`MOVE-231`. Where `observe` is unavailable or returns `undetermined` for a handoff in `cutover`, the
coordinator MUST move that handoff to `failed` with the kind `undetermined` and MUST NOT call
`cleanup`, `rollback`, or `commitCutover` for it again.

#### Strategy applicability

`MOVE-241`. A strategy supports orchestrated migration exactly when its prepared placement
enumerates shards, that is when `shards()` is non-empty for a non-empty placement set and `shardOf`
returns a shard identifier rather than the routing key. `ring`, `slot`, `range`, and `directory`
support orchestrated migration. `rendezvous` does not.

`MOVE-251`. `plan` MUST refuse a pair of snapshots whose strategy does not support orchestrated
migration, and MUST report the refusal under `ERR-*` naming the strategy kind. It MUST NOT return an
empty plan, because an empty plan is indistinguishable from a topology with no movement.

`MOVE-261`. A registered strategy outside the core set MUST declare its support by the same rule.
An implementation MUST NOT infer support from the strategy's name.

`MOVE-271`. Under a strategy that does not support orchestrated migration, an epoch change moves
keys with no handoff and no hook. The ownership delta is still computable for reporting where
`shards()` is non-empty, and is empty otherwise.

### Concurrent ownership

#### Ownership during a handoff

`MOVE-281`. Two nodes MAY hold the contents of one shard at the same time. At most one of them is
the authoritative owner at any instant, and the cutover record is the only thing that decides which.
A node's topology epoch MUST NOT be used to decide authority for a shard under handoff.

`MOVE-291`. The window of concurrent holding opens when `prepare` succeeds and closes when `cleanup`
succeeds. Within that window the rules of `MOVE-121` and `MOVE-131` apply without exception.

`MOVE-301`. A caller whose request reaches the non-authoritative node observes a refusal naming
the authoritative node, and follows it under `FENCE-171`. A caller observes no window in which
both nodes answer, and no window in which neither answers, except for the quiesce interval
specified in `MOVE-311`.

`MOVE-311`. Between the success of `quiesce` and the return of `commitCutover`, both nodes MUST
refuse writes for the shard, and the refusal MUST be reported as retryable under `ERR-*`. The source
MAY continue to answer reads during this interval. An implementation MUST bound this interval by
`cutoverGraceMillis` plus the `commitCutover` deadline, and MUST count it in its migration events.

#### Cutover commitment

`MOVE-321`. `commitCutover` MUST be a single-winner operation over a store that both the source and
the destination read. An implementation MUST NOT commit a cutover through a hook that writes only to
the destination.

`MOVE-331`. The coordinator MUST NOT call `commitCutover` before `quiesce` has returned success for
the same context, and MUST NOT call it after the quiesce lease has expired on the supplied clock.
Where the lease has expired, the coordinator MUST call `quiesce` again.

`MOVE-341`. Where `cutoverGuarantee` is `advisory`, the coordinator MUST wait `cutoverGraceMillis`
after `quiesce` returns success before calling `commitCutover`, and `cutoverGraceMillis` MUST be
greater than zero. Where it is `linearisable`, the coordinator MAY call `commitCutover` immediately.

`MOVE-351`. A `CutoverResult` of `lost` MUST move the handoff to `aborting`. Another destination has
taken ownership, and this handoff's copy is residue.

#### Guarantee levels

`MOVE-361`. An implementation MUST report the guarantee level of every plan, taken from
`cutoverGuarantee`, in the plan summary and in its migration events.

`MOVE-371`. A `MigrationPolicy` MUST carry `requireLinearisableCutover`, which defaults to true.
Where it is true and the hooks declare `advisory`, `plan` MUST refuse.

`MOVE-381`. Under a `linearisable` declaration the library provides these guarantees.

- At most one node is the authoritative owner of a shard at any instant.
- No acknowledged write is discarded by the handoff, because `cleanup` never runs before `verify`
  succeeds under `MOVE-181`.
- A handoff that reaches `complete` has a verified destination copy.

`MOVE-391`. These properties are best-effort under every declaration, and an implementation MUST NOT
present them as guarantees.

- That a caller's first attempt reaches the authoritative owner.
- That a partitioned source stops answering promptly. The bound is the quiesce lease, which the
  integrator enforces and the library only observes.
- That the destination is complete without `catchUp`, where the integrator replicates writes during
  the transfer.

`MOVE-401`. Under an `advisory` declaration, mutual exclusion is not provided. An implementation
MUST emit an event at plan construction naming the shortfall and MUST repeat the level in every
handoff event for that plan.

### Migration abort and rollback

`MOVE-411`. An abort requested in `planned` MUST move the handoff directly to `aborted` without
calling a hook. No hook has run, so nothing is to compensate.

`MOVE-421`. An abort requested in `preparing`, `transferring`, or `catchingUp` MUST move the handoff
to `aborting` and MUST call `rollback`, whose duty is to release the destination's partial copy and
any scratch state `prepare` created. The source is untouched throughout, so the abort loses nothing.

`MOVE-431`. An abort requested in `cutover` MUST be admitted only while no cutover record exists.
The coordinator MUST call `observe` before admitting it. Where a record exists, the abort MUST be
refused and the handoff MUST roll forward.

`MOVE-441`. An abort MUST NOT be admitted in `verifying`, `cleanup`, `complete`, `aborted`, or
`failed`. Ownership has already moved, and moving it back is a topology change rather than an abort.

`MOVE-451`. A handoff that reaches `failed` with the kind `unverified` MUST leave the source copy in
place. An implementation MUST NOT call `cleanup` for it under any later plan for the same epoch.

`MOVE-461`. A handoff that reaches `failed` with the kind `residue` has moved ownership correctly
and has left a copy at the source. An implementation MUST report the source node and the shard so an
operator can release it, and MUST NOT treat the residue as a correctness failure.

`MOVE-471`. Returning ownership to a former source MUST be expressed as a new topology epoch above
the target epoch, carrying the former assignment. An implementation MUST NOT decrement an epoch,
MUST NOT reinstall a retained snapshot, and MUST NOT reverse a committed cutover record.

`MOVE-481`. Where the topology has already moved above the plan's target epoch, an abort of a
pre-cutover handoff MUST still run its compensation. The higher epoch changes where traffic goes and
does not release the destination's partial copy.

`MOVE-491`. An abort MUST be idempotent. Requesting an abort for a handoff already in `aborting`,
`aborted`, or `failed` MUST have no effect beyond recording the request.

### Migration rate control

`RATE-001`. A rebalance MUST NOT begin as a side effect of accepting a topology. The coordinator
performs work only from a call the integrator makes, under `MOVE-051`.

`RATE-011`. An implementation MUST accept a migration policy with at least these members, all
non-negative integers except where stated.

| Member | Meaning |
|---|---|
| `maxConcurrentHandoffs` | handoffs in a non-terminal, post-`planned` state across the plan |
| `maxConcurrentPerSourceNode` | the same bound per source node |
| `maxConcurrentPerDestinationNode` | the same bound per destination node |
| `initialStepBudget` | the budget passed to the first `transfer` or `catchUp` of a handoff |
| `minStepBudget` | the floor for a reduced budget, at least 1 |
| `maxStepBudget` | the ceiling for an increased budget |
| `budgetIncrement` | the additive increase applied after a successful step |
| `stepDeadlineMillis` | the deadline placed in a hook context |
| `maxAttemptsPerStep` | retryable attempts before a step fails |
| `retryBackoffBaseMillis`, `retryBackoffCapMillis` | the backoff bounds |
| `cutoverGraceMillis` | the wait between `quiesce` and `commitCutover` under `advisory` |
| `catchUpResidualThreshold` | the residue at or below which `catchingUp` reaches `cutover` |
| `reTransferResidualThreshold` | the residue above which `catchingUp` returns to `transferring` |
| `requireLinearisableCutover` | boolean, default true |

`RATE-021`. An implementation MUST refuse a policy in which `minStepBudget` is zero,
`maxStepBudget` is below `minStepBudget`, or `reTransferResidualThreshold` is at or below
`catchUpResidualThreshold`.

`RATE-031`. The budget an implementation passes to `transfer` and `catchUp` is a count in the
integrator's own `budgetUnit`. The library MUST treat it as opaque, MUST pass it unmodified, and
MUST NOT convert it to any other unit.

`RATE-041`. An implementation MUST adjust the per-handoff budget by integer arithmetic only. After a
step that returns success, `budget = min(budget + budgetIncrement, maxStepBudget)`. After a step
that returns `deferred`, or that runs under a pressure level above `none`, `budget = max(budget / 2,
minStepBudget)` using unsigned integer division. No other adjustment is permitted.

`RATE-051`. Retry backoff MUST be computed by integer arithmetic as
`min(retryBackoffBaseMillis * 2^(attempt - 1), retryBackoffCapMillis)`, with any jitter drawn as an
integer number of milliseconds no greater than the computed value.

#### Backpressure signals

`RATE-061`. An implementation MUST accept a pressure gauge with this shape, and MUST treat an absent
gauge as one that always answers `none`.

```
interface PressureGauge:
    level(scope: PressureScope) -> one of { none, soft, hard }

PressureScope = one of { cluster, node(NodeId) }
```

`RATE-071`. An implementation MUST consult the gauge at `cluster` scope, at `node` scope for the
source, and at `node` scope for the destination before each `step`, and MUST take the highest of the
three levels, ordered `none` below `soft` below `hard`.

`RATE-081`. At `soft`, an implementation MUST NOT move a handoff out of `planned`, MUST reduce the
budget under `RATE-041`, and MAY continue every other step.

`RATE-091`. At `hard`, an implementation MUST NOT move a handoff out of `planned` and MUST NOT call
`transfer` or `catchUp`. It MUST still call `quiesce`, `commitCutover`, `verify`, `cleanup`,
`rollback`, and `observe`, because those complete work already begun and withholding them would
leave shards in the concurrent-holding window indefinitely.

`RATE-101`. A hook result of `deferred` MUST be treated as a backpressure signal for that handoff
alone. An implementation MUST NOT let it reduce the budget of another handoff.

`RATE-111`. An implementation MUST return `idle` from `step` when the pressure level or the
concurrency bounds leave nothing admissible, and MUST NOT spin, wait, or sleep inside `step`.

#### Measurement

`RATE-121`. An implementation MUST measure only what the hooks and the supplied clock report:
`unitsMoved` per step, `bulkRemaining` and `residue` per handoff, elapsed milliseconds per hook
call, attempts per step, consecutive deferrals per handoff, and the count of handoffs in each state.

`RATE-131`. An implementation MUST NOT infer a rate from a wall clock, MUST NOT probe a node, and
MUST NOT derive a pressure level from its own measurements. Pressure comes from the gauge and
nowhere else.

`RATE-141`. Measurements MAY use floating point where they are reported and MUST NOT enter any
admission, budget, threshold, or ordering decision.

### Range splits and merges

A split divides one range shard into two or more; a merge is its inverse. Both are available under
the `range` strategy only.

`SPLIT-001`. An implementation MUST support orchestrated split and merge under `range` and MUST NOT
offer them under `ring`, `rendezvous`, `slot`, or `directory`. A token addition under `ring` divides
a token range as a consequence of placement and is an ordinary ownership delta, not a split.

`SPLIT-011`. The topology authority decides a split or a merge and expresses it by publishing a
document in which one range is replaced by two or more contiguous ranges, or the reverse. The
library MUST NOT edit a topology document, MUST NOT choose a split point, and MUST NOT publish an
epoch.

`SPLIT-021`. An implementation MAY accept shard measurements from the integrator with this shape,
and MUST treat every member as an integer count.

```
interface ShardMetricsSource:
    report(shard: ShardId) -> ShardReport | none

ShardReport = { bytes: u64, keys: u64, requests: u64, hottestKeyRequests: u64,
                intervalMillis: u32 }
```

`SPLIT-031`. Where a split advice policy is configured, an implementation MUST emit a split advice
event for a shard whose `bytes` or `keys` exceeds its configured threshold, or whose `requests`
exceeds its configured threshold over `intervalMillis`. The event names the shard, the crossed
threshold, and the observed value.

`SPLIT-041`. An implementation MUST report key skew by the integer comparison
`hottestKeyRequests * 100 >= requests * skewPercent`, evaluated exactly over both products under
`CORE-005`, and where it holds MUST mark the advice as not addressable by a split. A split cannot
divide a single key.

`SPLIT-051`. Split advice is advisory. An implementation MUST NOT let advice change a snapshot, a
plan, or a routing decision.

#### Split lineage

`SPLIT-061`. An implementation MUST derive the relationship between the shards of two `range`
snapshots from their bounds and MUST NOT derive it from `shardId`. A null `start` compares below
every routing key and a null `end` compares above every routing key; every other comparison is the
unsigned bytewise ordering that `PLACE-*` fixes for `range`.

`SPLIT-071`. A shard `b` of the target snapshot is a child of shard `a` of the source snapshot when
`a.start <= b.start` and `b.end <= a.end`. A shard `a` is a parent of `b` under the same condition.

`SPLIT-081`. An implementation MUST classify each source shard against the target snapshot as
exactly one of the following.

| Classification | Condition |
|---|---|
| `unchanged` | one child with identical bounds |
| `split` | two or more children, together covering the source shard exactly |
| `merged` | the source shard is one of two or more shards contained in one target shard |
| `unaligned` | neither of the above |

`SPLIT-091`. An implementation MUST refuse to plan a migration across an `unaligned` classification
and MUST name the offending bounds. An authority that moves a boundary publishes the change as a
split epoch followed by a merge epoch, each of which is plannable.

`SPLIT-101`. The contiguity and coverage rules of `20-topology-format.md` apply to both snapshots
independently. An implementation MUST NOT accept a partially applied split, because a document
whose ranges do not cover the keyspace is invalid at load.

#### Split and merge execution

`SPLIT-111`. A split is represented in flight by two epochs, not by a third state within one epoch.
The source epoch names the parent and the target epoch names the children. There is no epoch at
which both the parent and its children are addressable.

`SPLIT-121`. An implementation MUST decompose a `split` classification into one local split step
followed by one handoff per child whose replica set differs from the parent's. The local split step
MUST complete before any of those handoffs leaves `planned`.

`SPLIT-131`. An implementation MUST decompose a `merged` classification into one handoff per parent
whose replica set differs from the target shard's, followed by one local merge step. Every such
handoff MUST reach `complete` before the local merge step begins.

`SPLIT-141`. An implementation MUST define the local steps as two further movement hooks.

```
interface MovementHooks (continued):
    splitLocal(ctx, children: list<ShardBounds>) -> HookResult
    mergeLocal(ctx, parents: list<ShardId>)      -> HookResult
```

`SPLIT-151`. `splitLocal` and `mergeLocal` MUST be idempotent, and MUST be retryable under the
rule of `MOVE-171`. Each runs on the node that holds every shard it names, and the library MUST
refuse to sequence one where that node is not a replica of every shard named.

`SPLIT-161`. A split whose children keep the parent's replica set is a local step alone, with no
handoff. The ownership delta for that shard names no node gained and no node lost.

`SPLIT-171`. A split MUST NOT run under a plan that a snapshot install has superseded. Where the
local split step has already succeeded, the shard identity of the source snapshot no longer matches
the data, so the plan MUST move its remaining handoffs to `failed` with the kind `undetermined`
rather than roll back.

#### In-flight requests across a split

`SPLIT-181`. A routing call resolves a key to exactly one shard under exactly one snapshot, so no
call ever addresses a parent and a child at once. This follows from `TOPO-121`.

`SPLIT-191`. A recipient MUST evaluate `FENCE-061` over the routing key rather than over the shard
identifier. A key whose parent shard at the sender's epoch and whose child shard at the recipient's
epoch have the same owner yields `ownership` of `owner`, and yields `ownershipStable` of true where
the sender's epoch is retained.

`SPLIT-201`. A request routed against a parent that arrives at a node owning none of the children
covering its key MUST be refused with `currentOwner` naming the owner of the child that covers the
key at the recipient's epoch.

`SPLIT-211`. A merge is the same case in reverse, and the same rules apply without variation.

## Error taxonomy

Every way the library declines to answer is one of a closed set of named, numbered conditions. A
binding maps each to the idiom of its language and changes neither the name nor the number.

### Taxonomy rules

`ERR-001`. The failure conditions of the library MUST be exactly the sixteen given in `ERR-010`. An
implementation MUST NOT add a condition, MUST NOT withdraw one, and MUST NOT report one condition
under another's code.

`ERR-002`. Each condition MUST carry the numeric code and the name `ERR-010` gives it, unchanged in
every binding and in every serialisation. A binding renders a condition as an exception, an error
value, a result variant, or any other idiom; the code and the name are what a conformance vector and
a log reader join on.

`ERR-003`. A numeric code MUST be permanent. A condition that is withdrawn in a later version keeps
its code, and the code MUST NOT be reassigned.

`ERR-004`. Every condition MUST carry these members.

```
record Error:
    code       : u32                    # from ERR-010
    name       : string                 # from ERR-010
    retryable  : boolean                # from ERR-010
    detail     : string                 # human readable, not parsed
    token      : FencingToken | none    # the snapshot in force, where one is
    shard      : ShardId | none         # where the strategy names shards
    cause      : string | none          # the closed sub-reason, where the condition defines one
    currentOwner : NodeId | none        # the node a caller retries at, ERR-040
```

`cause` MUST carry a member of the closed set the condition's own requirement gives it, and MUST NOT
carry a value drawn from outside such a set. A node identity, an epoch, and any other open value
belongs in a member of its own.

`ERR-005`. A condition MUST NOT carry the key, the routing key, or any prefix of either, unless
`includeKeysInDiagnostics` is enabled under `CFG-061`. `detail` MUST NOT be constructed from key
octets while that setting is off.

`ERR-006`. `retryable` MUST be a property of the condition rather than of the call. A condition
marked not retryable MUST NOT be retried against the same snapshot; the same call MAY succeed after
a later snapshot is installed.

`ERR-007`. An implementation MUST NOT merge two conditions that `ERR-010` separates, even where a
binding's idiom invites it. `noCandidate` and `exhausted` in particular describe different cluster
states and carry different operator responses.

`ERR-008`. Where more than one condition holds for one call, an implementation MUST report the first
that holds in this order: `invalidArgument`, `unready`, `staleSnapshot`, `noCandidate`, `exhausted`.

`ERR-009`. A replication shortfall MUST NOT be a condition. A preference list shorter than the
effective replication factor is a successful routing decision under `REPL-025`, reported through
`shortfall` on the decision and through the event of `OBS-020`.

### Condition table

`ERR-010`. The closed set is exactly this.

| Code | Name | Retryable | Condition |
|---|---|---|---|
| 101 | `noCandidate` | no | the candidate ordering is empty |
| 102 | `exhausted` | yes | the attempt sequence ran out after at least one attempt |
| 103 | `unready` | yes | no snapshot is in force |
| 104 | `staleSnapshot` | yes | the snapshot in force is stale and the policy is `refuse` |
| 105 | `invalidArgument` | no | a caller-supplied argument is outside the contract |
| 201 | `invalidTopology` | no | a document failed schema or semantic validation |
| 202 | `topologyConflict` | no | a differing identifier, or an equal epoch with a new digest |
| 203 | `staleDocument` | no | an arriving epoch is below the epoch in force or below `minEpoch` |
| 204 | `providerError` | yes | the provider failed to deliver a document |
| 301 | `notOwner` | at another node | the recipient does not hold the shard for the key |
| 302 | `epochMismatch` | after a refresh | the sender is behind or ahead of the recipient |
| 303 | `identityMismatch` | no | the two `topologyId` values differ |
| 304 | `redirectExhausted` | no | a redirect walk reached its bound or revisited a node |
| 401 | `planRefused` | no | a plan cannot be built from the two snapshots and the policy |
| 402 | `quiesced` | yes | the shard is inside the cutover window |
| 403 | `handoffFailed` | no | a handoff reached `failed` |

`ERR-011`. An implementation MUST document the response each condition expects of a caller, and that
response MUST be this one.

| Name | Caller response |
|---|---|
| `noCandidate` | inspect the topology; the same key answers the same way |
| `exhausted` | back off, then retry; a `retryBudget` cause means the cluster is shedding |
| `unready` | wait for a first document, bounded by `initialTimeoutMillis` |
| `staleSnapshot` | wait for the provider, or serve the request from another region |
| `invalidArgument` | correct the call |
| `invalidTopology` | fix the document at the authority; the snapshot in force is unchanged |
| `topologyConflict` | an authority defect; two writers are publishing one identifier |
| `staleDocument` | none at the caller; the provider is serving a lagging replica |
| `providerError` | none; the snapshot in force stays in force and the backoff applies |
| `notOwner` | retry at `currentOwner`, bounded by `maxRedirects` |
| `epochMismatch` | refresh the topology, then retry |
| `identityMismatch` | operator action; epochs under two identifiers are incomparable |
| `redirectExhausted` | surface the failure; do not fall back to an arbitrary node |
| `planRefused` | correct the snapshots or the policy member named in `cause` |
| `quiesced` | retry after the window, which `cutoverGraceMillis` bounds |
| `handoffFailed` | operator action, directed by the failure kind in `cause` |

### Routing conditions

`ERR-020`. `noCandidate` MUST be raised where the candidate ordering is empty: an empty eligible
node set under `PLACE-005`, a constraint that excludes every node under `OVR-027`, a `directory`
with no matching entry under `DIR-010`, a pin whose every identity is filtered out under `OVR-014`,
a placement set in which no node has a non-zero virtual node count under `RV-012`, an authored node
list none of whose identities is eligible under `PLACE-014`, or an eligible node set none of whose
members owns a ring token under `RING-024`.

`ERR-021`. `noCandidate` MUST carry a `cause` from the closed set `emptyPlacementSet`,
`constraintExcludedAll`, `noDirectoryEntry`, `pinExcludedAll`, `noSlotEntry`, `noRangeEntry`,
`zeroVirtualNodes`, `authoredListExcludedAll`, and `noEligibleTokenOwner`.
`authoredListExcludedAll` names a `slot`, `range`, or `directory` entry that matched the routing key
and whose every named node lies outside the eligible node set. `noEligibleTokenOwner` names a `ring`
topology under `explicit` token assignment in which no eligible node carries a token. The set is
total over the cases of `ERR-020`, so an implementation MUST NOT report `noCandidate` with no cause.

`ERR-022`. `exhausted` MUST be raised where the attempt sequence answers `exhausted` after at least
one attempt, under `FAIL-025`. It MUST carry the preference list, the attempted node identities in
order, the outcome recorded for each, and a `cause` from the closed set `preferenceList`,
`attemptLimit`, and `retryBudget`, under `FAIL-034`.

`ERR-023`. `unready` MUST be raised where a routing call, an explain call, or a recipient check runs
with no snapshot in force. A recipient whose verdict has `relation` of `unknownEpoch` MUST report
`unready` rather than `staleSnapshot`, under `FENCE-151`.

`ERR-024`. `staleSnapshot` MUST be raised only where `stalePolicy` is `refuse`. Under the default
`serve`, a stale snapshot MUST produce an ordinary routing decision, and staleness MUST be reported
through `OBS-010` and `OBS-020` alone.

`ERR-025`. `invalidArgument` MUST be raised where an affinity request names a level absent
from `domainLevels` under `READ-011`, where a key exceeds `maxKeyBytes` under `KEY-005`, where
an attempt limit of zero is supplied, and where a setting supplied at construction is outside
its range under `CFG-003`. It MUST NOT be raised for a key whose octets are not valid text,
under `KEY-013`.

### Topology conditions

`ERR-030`. `invalidTopology` MUST be raised for a document that fails any stage of `TOPO-001`
through stage 4, including an unsupported `formatVersion`, an unknown member, and any rule of
[`20-topology-format.md`](20-topology-format.md). It MUST carry every validation error the document
produced rather than the first, so that an operator fixes the document once.

`ERR-031`. `topologyConflict` MUST be raised for a `topologyId` that differs from the configured or
adopted one, and for an epoch equal to the epoch in force whose digest differs, under `TOPO-061`.

`ERR-032`. `staleDocument` MUST be raised for an epoch below the epoch in force and for an epoch
below `minEpoch`, under `TOPO-061`. It is distinct from `staleSnapshot`: `staleDocument` describes
an arriving document and `staleSnapshot` describes the snapshot in force.

`ERR-033`. `providerError` MUST be raised where a provider reports a failure or fails to deliver
within `initialTimeoutMillis`. It MUST NOT change the snapshot in force, MUST NOT change its
freshness, and MUST be followed by the backoff of `CORE-100`.

`ERR-034`. A document accepted as a no-op under `TOPO-061` MUST NOT produce a condition. It
refreshes freshness and emits the event of `OBS-020`.

### Recipient conditions

`ERR-040`. `notOwner` MUST be raised where a verdict has `ownership` of `notOwner` under
`FENCE-121`, and where a destination that has not committed cutover receives a request, which is
the case `FENCE-161` covers. It MUST carry the verdict's `currentOwner` in the `currentOwner` member
of `ERR-004`, and MUST leave `cause` absent.

`ERR-041`. `epochMismatch` MUST be raised where a verdict has `relation` of `senderBehind` and
`ownershipStable` is false under `FENCE-131`, and where `relation` is `senderAhead` and the
recipient's epoch has not reached the token's epoch by the deadline of `FENCE-141`. It MUST carry
the recipient's `localToken` and a `cause` of `senderBehind` or `senderAhead`.

`ERR-042`. `identityMismatch` MUST be raised where a verdict has `relation` of `identityMismatch`
under `FENCE-111`. It MUST NOT be reported as `epochMismatch`, because no refresh resolves it.

`ERR-043`. `redirectExhausted` MUST be raised where a caller reaches `maxRedirects` under
`FENCE-181` or is redirected to a node it has already attempted under `FENCE-191`.

`ERR-044`. An unfenced request that `strict` refuses MUST be reported as `epochMismatch` with a
`cause` of `unfenced`, under `FENCE-041`.

`ERR-045`. Where more than one recipient condition holds for one request, an implementation MUST
report the first that holds in this order: `identityMismatch`, `unready`, `notOwner`,
`epochMismatch`. A verdict carrying `relation` of `senderAhead` together with `ownership` of
`notOwner` is therefore reported as `notOwner` and carries `currentOwner`. `ERR-008` governs the
routing conditions and does not govern these.

### Migration conditions

`ERR-050`. `planRefused` MUST be raised by `plan` under `MOVE-081`, `MOVE-251`, `MOVE-371`,
`RATE-021`, and `SPLIT-091`. It MUST carry a `cause` from the closed set `incomparableShards`,
`epochNotAdvancing`, `strategyUnsupported`, `destinationOutsidePlacementSet`, `unalignedRanges`,
`policyInvalid`, and `guaranteeTooWeak`.

`ERR-051`. `quiesced` MUST be raised for a write to a shard between the success of `quiesce` and the
return of `commitCutover`, under `MOVE-311`. It MUST be reported as retryable at both the source and
the destination, and MUST NOT be raised for a read the source still answers.

`ERR-052`. `handoffFailed` MUST be raised where a handoff reaches `failed`, and MUST carry a `cause`
of exactly one of `unverified`, `residue`, `undetermined`, and `rollbackFailed`, under `MOVE-011`.

`ERR-053`. A handoff that reaches `aborted` MUST NOT produce a condition. An abort is an outcome the
integrator requested or a plan supersession, and it is reported through the event of `OBS-020`.

### Binding mapping

`ERR-060`. A binding MUST map every condition of `ERR-010` to one idiom consistently. A binding that
raises exceptions MUST NOT raise one condition and return another as a value.

`ERR-061`. A binding MUST expose the numeric code and the name on the value it raises or returns,
and MUST NOT require a caller to parse `detail` to tell two conditions apart.

`ERR-062`. A binding MAY group conditions into a hierarchy whose leaves are the conditions that
`ERR-010` lists. It MUST NOT introduce a leaf of its own.

`ERR-063`. A binding MUST NOT translate a condition raised by an integrator-supplied extension
point into a condition of `ERR-010`. A provider failure surfaces as `providerError` with the
original failure carried in `detail`; a movement hook failure surfaces through the hook result
types of `MOVE-111`.

## Observability

The library reports what it decided, what it refused, and how skewed the result is. It gathers no
signal of its own: every number below is derived from a call a caller made or from a report an
integrator supplied.

### Metric rules

`OBS-001`. An implementation MUST expose every metric of `OBS-010`, MUST name it exactly as given,
and MUST carry exactly the labels given. It MAY expose further metrics of its own, under a name that
does not begin with `sharder.`.

`OBS-002`. A metric value MAY be a floating-point number. This is the single exception to the rule
that the library uses no floating point. A metric value MUST NOT be read by placement, by
validation, by any ordering, by fencing, by handoff admission, by a step budget, or by any threshold
comparison. Every such comparison MUST use the integer arithmetic its own requirement specifies.

`OBS-003`. Every label value MUST come from a bounded set. A key, a routing key, and a shard
identifier under `ring` or `rendezvous` MUST NOT be a label value, because each is unbounded in
cardinality. A shard identifier MAY be a label under `slot`, `range`, and `directory` while the
shard count is at or below `shardLabelLimit`, and MUST be dropped from the labels above it.

`OBS-004`. Metrics MUST be reported through a registry the integrator supplies under `CFG-060`.
Where none is supplied, an implementation MUST maintain the values internally, MUST expose them
through a call that returns them as a whole, and MUST NOT start a unit of execution to export them,
under `CORE-060`.

`OBS-005`. Recording a metric MUST NOT change a routing decision, a snapshot, a health state, a
retry budget, or a handoff state.

`OBS-006`. `topology_id` MUST be a label on every metric that describes work done against a
snapshot, carrying the `topologyId` of the snapshot in force. An implementation MUST NOT add `epoch`
as a label, because an epoch sequence is unbounded; the epoch is reported by
`sharder.topology.epoch`.

`OBS-007`. `sharder.health.ejections` and `sharder.health.failure_percent` MUST carry `node` alone
and MUST NOT carry `topology_id`. Health state is keyed by node identity and survives an epoch
change under `HEALTH-006`, so neither value describes work against one snapshot.

### Required metrics

`OBS-010`. An implementation MUST expose these metrics. Every name below carries the prefix
`sharder.`, which the tables omit for width.

Counters.

| Name | Labels | Meaning |
|---|---|---|
| `routing.decisions` | `topology_id`, `strategy`, `outcome` | routing calls completed |
| `routing.shortfall` | `topology_id`, `cause` | shortfalls by limiting cause |
| `routing.spread_relaxation` | `topology_id`, `stage` | relaxations by chosen stage |
| `routing.filter_failed_open` | `topology_id` | decisions in which the filter failed open |
| `routing.override_matched` | `topology_id`, `mode` | override matches by mode |
| `routing.errors` | `topology_id`, `code` | conditions raised, by numeric code |
| `attempts.total` | `topology_id`, `role`, `outcome` | attempts recorded |
| `attempts.exhausted` | `topology_id`, `cause` | exhaustions by cause |
| `attempts.retries_refused` | `topology_id` | retries the budget refused |
| `health.transitions` | `topology_id`, `from`, `to` | health state transitions |
| `health.ejections` | `node` | entries into `unavailable` |
| `health.ejections_refused` | `topology_id` | transitions the ceiling refused |
| `topology.documents` | `topology_id`, `outcome` | documents processed |
| `topology.weight_clamped` | `topology_id` | virtual node counts clamped |
| `fencing.verdicts` | `relation`, `ownership`, `served` | recipient checks |
| `fencing.redirects` | `topology_id` | redirects a caller followed |
| `migration.units_moved` | `topology_id` | `unitsMoved` summed over steps |
| `shard.requests` | `topology_id`, `shard` | requests per shard |

Gauges.

| Name | Labels | Meaning |
|---|---|---|
| `health.nodes` | `topology_id`, `state` | nodes in each health state |
| `health.failure_percent` | `node` | `f(x)` over the window |
| `topology.epoch` | `topology_id` | the epoch in force |
| `topology.snapshot_age_millis` | `topology_id` | since the snapshot was last confirmed |
| `topology.stale` | `topology_id` | 1 where the snapshot in force is stale |
| `topology.nodes` | `topology_id`, `state` | nodes in each administrative state |
| `migration.handoffs` | `topology_id`, `state` | handoffs in each state |
| `migration.step_budget` | `topology_id` | the current per-handoff budget |
| `migration.pressure` | `scope`, `level` | the level the gauge last reported |
| `balance.observed_share` | `topology_id`, `node` | observed share over expected share |

Histograms.

| Name | Labels | Meaning |
|---|---|---|
| `routing.duration_millis` | `topology_id` | time spent in a routing call |
| `routing.preference_list_length` | `topology_id` | length of the preference list |
| `routing.replica_count` | `topology_id` | the achieved replica count `r` |
| `topology.prepare_duration_millis` | `topology_id` | time in stage 6 of `TOPO-001` |
| `migration.hook_duration_millis` | `hook`, `outcome` | time in each movement hook |
| `migration.cutover_window_millis` | `topology_id` | the interval of `MOVE-311` |

`OBS-011`. `routing.decisions` MUST carry an `outcome` of `decided` or `failed`.
`topology.documents` MUST carry an `outcome` of `installed`, `noop`, or `rejected`.
`routing.override_matched` MUST carry a `mode` of `pin`, `constrain`, or `both`.
`routing.shortfall` MUST carry a `cause` of `nodes` or `domains`. `attempts.exhausted` MUST carry a
`cause` of `preferenceList`, `attemptLimit`, or `retryBudget`.

`OBS-012`. `balance.observed_share` MUST be the node's share of first-candidate decisions divided by
its weighted expected share. It is the one metric whose value is a ratio, and `OBS-002` is what
permits it.

### Required events

`OBS-020`. An implementation MUST emit these events, MUST name them exactly as given, and MUST carry
at least the payload given. Every name carries the prefix `sharder.`, which the table omits.

| Name | When | Payload beyond `OBS-021` |
|---|---|---|
| `topology.installed` | a snapshot is installed | digest, node counts, prepare duration |
| `topology.rejected` | a document is rejected | the condition, the epoch, the digest |
| `topology.unchanged` | an equal epoch and digest arrives | digest |
| `topology.stale` | the snapshot passes `staleAfterMillis` | the age, the policy in force |
| `topology.fresh` | a stale snapshot is confirmed | the age it reached |
| `topology.provider_error` | a provider reports a failure | the condition, the backoff |
| `topology.weight_clamped` | a count is clamped | node, requested count, granted count |
| `topology.directory_large` | a directory exceeds its threshold | entry count, threshold |
| `topology.default_seed` | a zero seed meets multi-tenancy | the evidence, under `SEC-011` |
| `topology.delta` | an ownership delta is computed | shards changed, gained, lost |
| `routing.shortfall` | a replica prefix is short | factor, achieved, cause, shard |
| `routing.spread_relaxed` | a stage above 0 is chosen | relaxed levels, stage, shard |
| `routing.filter_failed_open` | every entry is skipped | preference list length |
| `routing.exhausted` | an attempt sequence is exhausted | attempted, length, cause, shard |
| `health.transition` | a health state changes | node, prior state, new state, trigger |
| `health.ejection_refused` | the ceiling refuses a transition | node, ejected, set size |
| `fencing.refused` | a recipient refuses a request | relation, ownership, owner, token |
| `migration.planned` | `plan` returns a plan | handoff count, guarantee, policy |
| `migration.advisory` | a plan is built on `advisory` hooks | the shortfall of `MOVE-401` |
| `migration.state_changed` | a handoff changes state | handoff, shard, from, to, trigger |
| `migration.cutover_committed` | a record is committed | shard, source, destination, window |
| `migration.failed` | a handoff reaches `failed` | shard, kind, source, destination |
| `migration.superseded` | a plan is superseded | epoch, aborted count, finishing count |
| `shard.split_advice` | a split threshold is crossed | shard, threshold, value, addressable |
| `shard.hot` | a shard is hot under `OBS-031` | shard, observed share, expected share |
| `shard.key_skew` | key skew is detected | shard, hottest key requests, requests |

`OBS-021`. Every event MUST carry its name, the `Instant` at which it was emitted, the `topologyId`
and `epoch` in force, and a severity from the closed set `info`, `warning`, and `error`.

`OBS-022`. No event MUST carry the key, the routing key, or any prefix of either. `REPL-023` states
this for the shortfall event; it holds for every event without exception, and
`includeKeysInDiagnostics` MUST NOT relax it.

`OBS-023`. Events MUST be delivered to the sink the integrator supplies under `CFG-060`,
synchronously, on the unit of execution that produced them. A sink that fails MUST NOT fail the call
that produced the event, and its failure MUST be counted and otherwise ignored.

`OBS-024`. An implementation MUST deduplicate an event that one condition would otherwise emit per
call. `sharder.routing.shortfall` and `sharder.routing.spread_relaxed` are emitted at most once per
combination of epoch, shard identifier, and cause under `REPL-024`; `sharder.routing.exhausted` and
`sharder.fencing.refused` MUST be emitted per occurrence, because each names a distinct attempt.

`OBS-025`. Where no sink is supplied, an implementation MUST count events by name and MUST NOT
buffer their payloads.

`OBS-026`. The structure that holds the deduplication state of `OBS-024` MUST be bounded by
`shardLabelLimit`, counted in distinct shard identifiers per epoch. Where the shard identifier is
not a permitted label value under `OBS-003`, or where the bound is reached, an implementation MUST
deduplicate per combination of epoch and cause alone, MUST NOT record a further shard identifier for
that epoch, and MUST emit one event naming the suppression and the bound. The structure MUST be
discarded when the epoch it was built for is no longer in force.

### Skew detection

`OBS-030`. Hot-shard and key-skew detection MUST consume only the routing counters the library
holds and the `ShardReport` values an integrator supplies under `SPLIT-021`. An implementation MUST
NOT probe a node, MUST NOT sample traffic, and MUST NOT infer load from a wall clock.

`OBS-031`. A shard MUST be reported as hot when this holds, in unsigned integer arithmetic, over one
`intervalMillis`.

```
shardRequests * shardCount * 100 >= totalRequests * hotShardFactorPercent
```

Both sides MUST be evaluated exactly under `CORE-005`. `shardRequests` and `totalRequests` are
u64 under `SPLIT-021` and `shardCount` reaches 1048576 under `slot`, so the left-hand side exceeds
64 bits within the declared range and a 64-bit multiplication is not conforming.

`shardCount` is the number of shards `shards()` enumerates. Where a strategy enumerates no shards,
no shard is hot and the library MUST NOT report one.

`OBS-032`. Key skew MUST be reported by the comparison of `SPLIT-041`,
`hottestKeyRequests * 100 >= requests * keySkewPercent`. A hot shard that also shows key skew MUST
be marked as not addressable by a split.

`OBS-033`. Detection MUST use unsigned integer arithmetic throughout, whatever the metric surface
reports. `OBS-002` permits a floating-point metric value and forbids one reaching a comparison.

`OBS-034`. Detection MUST emit an event and MUST NOT change a routing decision, a snapshot, a
preference list, or a plan, under `SPLIT-051`.

`OBS-035`. Where a `ShardMetricsSource` is not supplied and the shard label is dropped under
`OBS-003`, an implementation MUST NOT report a hot shard. It reports the balance gauge of `OBS-010`,
which is per node and always available.

### Explain API

`OBS-040`. An implementation MUST expose `explain(key)` under `CORE-030`, answering with the account
of how the snapshot in force routes that key.

`OBS-041`. The record MUST have this shape.

```
record Exclusion:
    node   : NodeId
    stage  : one of { administrativeState, constraint, virtualNodes, authoredList,
                      duplicate, spread, factorReached, health }
    reason : string

record ExplainRecord:
    key             : Key
    routingKey      : RoutingKey
    keyTransform    : { kind: string, fields: map<string, string> }
    token           : FencingToken
    digest          : bytes
    strategy        : string
    matchedOverride : { index: u32, matcherKind: string, matcherValue: bytes,
                        mode: one of { pin, constrain, both } } | none
    shard           : ShardId | none
    strategyInputs  : list<{ name: string, value: string }>
    eligible        : list<NodeId>
    candidates      : list<NodeId>
    preferenceList  : list<PreferenceEntry>
    exclusions      : list<Exclusion>
    relaxedLevels   : list<string>
    shortfall       : { cause: string, factor: u32, achieved: u32 } | none
```

`OBS-042`. `strategyInputs` MUST name the arithmetic the configured strategy performed: `keyHash`
and the owning token under `ring`, `keyHash` and `slotIndex` under `slot`, the winning score and the
virtual node count under `rendezvous`, the covering range's bounds under `range`, and the matched
entry's index under `directory`. Every value MUST be rendered as text, a u64 as sixteen lowercase
hexadecimal digits.

`OBS-043`. Every member of the eligible node set MUST appear exactly once, either in `candidates` or
in `exclusions`. Every member of the placement set that the eligible set omits MUST appear in
`exclusions` with a `stage` of `constraint` or `administrativeState`.

`OBS-044`. `explain` MUST report the same routing key, the same matched override, the same shard,
the same candidate ordering, and the same preference list that `route` reports for the same key
against the same snapshot.

`OBS-045`. `explain` MUST NOT change any state. It MUST NOT record a health signal, MUST NOT count
against a retry budget, MUST NOT count in `sharder.routing.decisions`, and MUST NOT emit an event
that `route` would emit.

`OBS-046`. `route` MUST NOT compute an explain record unless the caller asked for one. The record
names every eligible node and every exclusion, so its cost grows with the node set and it does not
belong on the routing path.

`OBS-047`. A binding MUST offer a serialisation of an `ExplainRecord` as a JSON object whose member
names are the field names given in `OBS-041`. Octet-valued fields MUST be rendered as lowercase
hexadecimal.

`OBS-048`. `explain` MUST answer `unready` where no snapshot is in force, and MUST answer with a
record rather than a condition where the candidate ordering is empty. A record whose `candidates`
list is empty and whose `exclusions` list accounts for every node is the answer an operator needs.

## Configuration surface

Every setting the library reads is supplied by the integrator at construction. The topology document
carries what is agreed between callers; configuration carries what is local to one of them.

### Configuration rules

`CFG-001`. An implementation MUST accept every setting named in this section, MUST apply the default
given where the integrator supplies no value, and MUST behave as the default's row describes.

`CFG-002`. A setting MUST NOT appear in a topology document. A document member whose name matches a
setting is an unknown member and a validation failure under `TOPO-*`.

`CFG-003`. A setting MUST be validated at construction. A value outside its stated range MUST refuse
construction with `invalidArgument` under `ERR-025`. An implementation MUST NOT clamp a setting to
its range and MUST NOT ignore one it does not recognise.

`CFG-004`. No setting may change a candidate ordering, a shard identifier, a preference list, an
effective replication factor, or an ownership delta. Two routers configured differently that hold
the same snapshot MUST compute the same preference list for the same key. A setting decides whether
a call is served, how far a caller walks, and what is reported.

`CFG-005`. Every duration MUST be an integer count of milliseconds, every size an integer count of
octets, every share an integer percentage, and every count a non-negative integer. No setting is a
floating-point value.

`CFG-006`. A setting's name MUST carry its unit where the unit is not implied by the name:
`Millis` for a duration, `Bytes` for a size, `Percent` for a share.

`CFG-007`. An implementation MUST expose the values in force, including defaults the integrator did
not set, so that an operator reads the configuration from the running process rather than from a
deployment manifest.

### Provider and snapshot settings

`CFG-010`. An implementation MUST accept these settings.

| Setting | Default | Meaning |
|---|---|---|
| `provider` | required | the `TopologyProvider` of `CORE-080` documents are loaded from |
| `expectedTopologyId` | unset | the `topologyId` every document is checked against; `TOPO-091` |
| `minEpoch` | unset | the monotonicity floor across a restart, under `TOPO-071` |
| `pollIntervalMillis` | 30000 | period at which a pull-only provider is polled; `CORE-090` |
| `reconcileIntervalMillis` | 300000 | poll period for a provider that also pushes, `CORE-092` |
| `initialTimeoutMillis` | 10000 | wait for a first document before `unready`, `CORE-091` |
| `providerRetryBaseMillis` | 1000 | first backoff interval after a provider failure |
| `providerRetryCapMillis` | 60000 | ceiling on the provider backoff interval |
| `providerRetryJitter` | true | whether an integer jitter below the interval is subtracted |
| `staleAfterMillis` | 0 | age past which the snapshot is marked stale; 0 disables it |
| `stalePolicy` | `serve` | `serve` routes against a stale snapshot, `refuse` refuses |
| `retentionDepth` | 3 | previous snapshots retained for `FENCE-091` and plan checks |
| `maxKeyBytes` | 65536 | ceiling on a key, above which a routing call answers `invalidArgument` |
| `directoryWarnEntries` | 10000 | entry count above which the directory event is emitted |

`CFG-011`. `reconcileIntervalMillis` MUST be at least `pollIntervalMillis`. A provider that both
pushes and pulls is reconciled rarely, because the push path carries the change.

`CFG-012`. An implementation MUST accept an optional `executor`, defaulting to unset, on which
it runs polling and reconciliation. Where it is unset the library MUST NOT poll, and `refresh`
under `CORE-033` is the only path by which a document arrives on a pull-only provider.

`CFG-013`. `maxKeyBytes` bounds the hash cost of one routing call. An implementation MUST compare
the key length against it before applying the key transform, and MUST NOT truncate under `KEY-005`.

### Routing and failover settings

`CFG-020`. An implementation MUST accept these settings.

| Setting | Default | Meaning |
|---|---|---|
| `attemptLimit` | the factor plus 2 | attempts a sequence yields, under `FAIL-022` |
| `retryBudgetWindowMillis` | 10000 | accounting window for the retry budget |
| `retryBudgetPercent` | 20 | retries permitted as a percentage of first attempts |
| `retryBudgetMinimum` | 3 | retries permitted in the window regardless of the percentage |
| `readAffinityWindow` | the replica count `r` | entries read affinity may reorder, `READ-012` |

`CFG-021`. `attemptLimit` MUST be at least 1. A limit of 0 MUST refuse construction, because a
routing call that permits no attempt is a configuration defect rather than a load shedding policy.

`CFG-022`. The retry budget defaults permit a fifth of the first-attempt rate as retries, plus three
retries in the window regardless. A cluster losing one replica of three therefore retries freely,
and a cluster losing a third of its nodes stops multiplying its own load.

### Health settings

`CFG-030`. An implementation MUST accept the parameters of the built-in health state machine
as `HEALTH-055` gives them, with the defaults `HEALTH-055` gives, and MUST accept an optional
`healthView` that replaces the built-in implementation under `HEALTH-013`.

`CFG-031`. An implementation MUST refuse a configuration in which `bucketCount` is 0, `windowMillis`
is below `bucketCount`, `maxEjectionPercent` is above 100, `probationDivisor` is 0, or
`maxEjectionMillis` is below `baseEjectionMillis`.

`CFG-032`. Where `healthView` is supplied, the parameters of `HEALTH-055` MUST be ignored and the
supplied implementation MUST still satisfy `HEALTH-005` and `HEALTH-013`.

### Fencing settings

`CFG-040`. An implementation MUST accept these settings.

| Setting | Default | Meaning |
|---|---|---|
| `recipientPolicy` | `strict` | refusal policy for a stale or unfenced request, `FENCE-131` |
| `maxRedirects` | 2 | redirects one request may follow under `FENCE-181` |
| `refreshWaitMillis` | 0 | wait for the epoch to reach a `senderAhead` token, `FENCE-141` |
| `tokenDigest` | false | whether an emitted token carries the digest, under `FENCE-011` |

`CFG-041`. `recipientPolicy` defaults to `strict` because the deployment that fences is the
deployment whose worst failure is two nodes believing they own one shard.

`CFG-042`. `refreshWaitMillis` defaults to 0 so that a recipient adds no hidden latency to a request
it will probably refuse. An integrator whose provider pushes promptly raises it.

### Migration policy settings

`CFG-050`. An implementation MUST accept a `MigrationPolicy` with these members, under `RATE-011`.

| Setting | Default | Meaning |
|---|---|---|
| `maxConcurrentHandoffs` | 4 | handoffs past `planned` and short of terminal, across the plan |
| `maxConcurrentPerSourceNode` | 1 | the same bound per source node |
| `maxConcurrentPerDestinationNode` | 1 | the same bound per destination node |
| `initialStepBudget` | 1 | budget passed to the first `transfer` or `catchUp` of a handoff |
| `minStepBudget` | 1 | floor for a reduced budget |
| `maxStepBudget` | 64 | ceiling for an increased budget |
| `budgetIncrement` | 1 | additive increase after a step that returns success |
| `stepDeadlineMillis` | 30000 | deadline placed in a hook context |
| `maxAttemptsPerStep` | 5 | retryable attempts before a step fails |
| `retryBackoffBaseMillis` | 1000 | first retry backoff for a step |
| `retryBackoffCapMillis` | 60000 | ceiling on the step retry backoff |
| `cutoverGraceMillis` | 5000 | wait between `quiesce` and `commitCutover` under `advisory` |
| `commitDeadlineMillis` | 30000 | deadline past which a `commitCutover` outcome is undetermined |
| `catchUpResidualThreshold` | 0 | residue at or below which `catchingUp` reaches `cutover` |
| `reTransferResidualThreshold` | the largest u64 | residue returning a handoff to `transferring` |
| `requireLinearisableCutover` | true | whether `plan` refuses hooks that declare `advisory` |

`CFG-051`. The budget defaults move one unit per step and grow by one after each success, so an
integrator who has not measured their own `budgetUnit` gets a migration that starts slowly and finds
its rate, rather than one that saturates a cluster on its first step.

`CFG-052`. `catchUpResidualThreshold` defaults to 0, so a cutover happens only once the residue
is empty. `reTransferResidualThreshold` defaults to the largest u64, so `catchingUp` never
returns to `transferring` until an integrator chooses a threshold in their own unit. Both
defaults satisfy `RATE-021`.

`CFG-053`. `requireLinearisableCutover` defaults to true, so a plan built on hooks that cannot
exclude two owners refuses rather than proceeds. An integrator who accepts the weaker guarantee sets
it to false and reads the shortfall in every event under `MOVE-401`.

`CFG-054`. An implementation MUST accept an optional `pressureGauge` under `RATE-061` and an
optional `shardMetricsSource` under `SPLIT-021`, both defaulting to unset.

### Observability settings

`CFG-060`. An implementation MUST accept these settings.

| Setting | Default | Meaning |
|---|---|---|
| `metricsRegistry` | unset | the registry metrics are reported through under `OBS-004` |
| `eventSink` | unset | the sink events are delivered to under `OBS-023` |
| `shardLabelLimit` | 1024 | shard count at or below which a shard may be a label |
| `hotShardFactorPercent` | 400 | share of the expected share at which a shard is hot |
| `keySkewPercent` | 50 | share of a shard's requests one key must draw to be reported as skew |
| `splitAdviceBytes` | unset | shard size above which split advice is emitted |
| `splitAdviceKeys` | unset | shard key count above which split advice is emitted |
| `splitAdviceRequests` | unset | request count per interval above which advice is emitted |
| `includeKeysInDiagnostics` | false | whether `detail` may carry key octets, `ERR-005` |

`CFG-061`. `includeKeysInDiagnostics` defaults to false, so a key that is a tenant identifier, an
account number, or a user identifier does not reach a log by default. It MUST NOT relax `OBS-022`,
which forbids keys in events under every setting.

`CFG-062`. Where `eventSink` is unset an implementation MUST count events by name under `OBS-025`.
Where `metricsRegistry` is unset it MUST hold the values internally under `OBS-004`. Neither absence
may change behaviour beyond reporting.

`CFG-063`. The split advice thresholds default to unset, so no advice is emitted until an operator
states a threshold in the units their storage uses. An implementation MUST NOT choose one.

## Security and multi-tenancy

The library places keys deterministically, which is the property every caller relies on and the
property an adversary who can choose keys exploits. This section states what the library defends,
what it declines to defend, and what it discloses.

### Threat model

`SEC-001`. The library MUST NOT be relied upon to bound the load that keys of a caller's choosing
place on one node. Placement is a pure function of the snapshot and the routing key, so a party who
knows the node set and the seed computes a key's shard offline and produces as many keys for one
shard as it likes.

`SEC-002`. An implementation MUST NOT refuse, rewrite, rate limit, or reorder a key it judges
adversarial. Admission control belongs to the caller, which sees the principal behind a request; the
library sees octets.

`SEC-003`. The library's defence against a crafted key is a seed the adversary cannot read,
under `SEC-010`, and detection after the fact, under `OBS-031`.

`SEC-004`. An implementation MUST NOT treat health state, a routing decision, or a fencing token as
an authentication or authorisation artefact. None of them says who a caller is.

### Hash seed handling

`SEC-010`. `hash.seed` MUST be treated as a secret wherever crafted keys are part of the threat
model. An implementation MUST NOT log it, MUST NOT place it in a condition's `detail`, MUST NOT
place it in an event payload, MUST NOT place it in an `ExplainRecord`, and MUST NOT expose it
through any accessor on a snapshot or a router.

`SEC-011`. The default seed of sixteen zero octets provides no resistance to a crafted key, because
every implementation and every conformance vector uses it. An implementation MUST expose
`seedIsDefault` on a snapshot under `CORE-020`, and MUST emit `sharder.topology.default_seed` once
per accepted snapshot whose seed is sixteen zero octets and whose document carries at least one
`overrides` entry or a `directory` strategy. Either is evidence that the topology separates
tenants.

`SEC-012`. An implementation MUST NOT derive a seed of its own. It MUST NOT synthesise one from
`topologyId`, from a host name, from a process identifier, or from a clock, because two callers that
derived different seeds would compute different owners for one key at one epoch, which invariant 4
forbids.

`SEC-013`. A seed change moves every key. An implementation MUST refuse to compute an ownership
delta across a seed change under `TOPO-231`, and MUST NOT present a seed rotation as a rebalance.

`SEC-014`. A comparison that reads the seed MUST NOT short-circuit on its octets. An implementation
MUST NOT expose a timing oracle that distinguishes seed prefixes.

`SEC-015`. An implementation MUST NOT weaken the hash. It MUST use SipHash-2-4 as `hash.algorithm`
names it and MUST NOT substitute an unkeyed function, whatever the seed's value.

### Topology disclosure

`SEC-020`. A `RoutingDecision` names node identities, which disclose the cluster's membership and
size. An implementation MUST NOT be assumed to redact them, and a caller that returns a decision
across a trust boundary redacts it itself.

`SEC-021`. An `ExplainRecord` discloses the whole eligible node set, the failure domain layout, the
matched override entry, and the strategy's arithmetic. An implementation MUST treat `explain` as an
operator surface. A caller MUST NOT expose it on a path a tenant reaches.

`SEC-022`. An implementation MUST NOT copy an override's `note`, a node's `tags`, a node's
`address`, or the document's `metadata` into a `RoutingDecision`, under `CORE-043`. Each is an
operator annotation, and each is reachable from the snapshot by code that holds the router.

`SEC-023`. A fencing token discloses the `topologyId` and the epoch, and therefore the rate at which
the cluster changes. `tokenDigest` defaults to false under `CFG-040`, so a token that crosses a
trust boundary carries no content digest unless an integrator asks for one.

`SEC-024`. A pin discloses which nodes serve a tenant to anyone who reads a decision for that
tenant's key. An implementation MUST NOT reveal an override entry that did not match: a decision
carries the matched entry's index alone, under `CORE-040`.

### Tenancy and resource bounds

`SEC-030`. The library MUST NOT provide tenant isolation. An override `constrain` restricts where a
tenant's keys are placed; it does not prevent a caller from routing another tenant's key, and it is
not an access control rule.

`SEC-031`. Health state MUST remain caller-local under `HEALTH-002`. An implementation MUST NOT
exchange health state between callers, so one tenant's traffic cannot eject a node from another
caller's view.

`SEC-032`. A retry budget MUST be held per router instance across every key under `FAIL-033`. One
tenant's retries therefore consume a budget every tenant shares, and an integrator who needs
per-tenant budgets holds a router per tenant.

`SEC-033`. An implementation MUST bound the cost of one routing call by `maxKeyBytes` under
`CFG-013`, and SHOULD index `exact` matchers of a `directory` table and an `overrides` table so that
matching cost does not grow linearly with the table for every key.

`SEC-034`. An implementation MUST NOT allocate memory proportional to a caller-supplied value other
than the key itself during a routing call. An `ExplainRecord` is proportional to the node set and is
computed only where it was asked for, under `OBS-046`.
