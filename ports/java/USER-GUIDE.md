# Java port user guide

A task-oriented walk through the Java port of the sharder library: how a control plane and a caller
divide the work, a first routed key, the node set it routes over, replication, failover, read
affinity, fencing, resharding, and orchestrated migration. Each section assumes the ones above it,
and each ends at the reference that carries the full detail.

## Scope of the library

The sharder library answers one question. Given a routing key and a topology, it says which nodes
handle that key, in what order, and what a caller does next when those nodes are unavailable. It
also sequences an ownership change from one topology to the next.

The library computes and sequences. It does not act.

| The library | The integrator |
|---|---|
| computes a preference list from the topology and the key | opens the connection and sends the request |
| filters that list by the health an integrator reported | observes each attempt and reports its outcome |
| issues a fencing token and checks one at the recipient | attaches the token to the request and honours a refusal |
| orders the steps of a shard handoff | moves the data those steps name |
| answers which shards moved between two topologies | publishes the topology that moved them |

It moves no data, opens no connection, sends no probe, resolves no address, elects no leader, and
starts no unit of execution. Health comes from signals a caller reports, documents come from a
provider, and time comes from the configured clock, which is what makes a routing decision a
function of the snapshot in force, the routing key, and the signals reported.

A caller is the application embedding the library. It is not the client that talks to a node; that
one belongs to the integrator's world.

## The control plane and the caller

### Division of labour

A topology authority decides what the cluster looks like and publishes a topology document for each
epoch. The authority sits outside the library: a control plane, a key-value store, a file under
configuration management, or an operator with an editor. The library assigns no epoch, elects no
authority, and writes no document.

Each process that routes embeds the library and holds a `Router` over the document the authority
published. A router is long-lived. It is constructed once, it holds the snapshot in force, and it is
closed at shutdown; one constructed per request would repeat the preparation a snapshot pays for
once.

| Lifetime | What is held |
|---|---|
| the process | one `Router`, and the `TopologyProvider` it reads documents from |
| an epoch | the immutable `TopologySnapshot` the router installed |
| one request | the `RoutingDecision`, the `AttemptSequence` over it, and its `FencingToken` |

The authority publishes and the caller routes, and at run time neither asks anything of the other.
Two callers holding the same topology identifier and epoch compute the same preference list for the
same key, so no caller is told which node to use and none asks.
[`00-overview.md`](../../docs/design/00-overview.md#component-model) draws the components and the
three extension points an integrator implements.

### A document reaching a running router

A topology document is JSON, and where it comes from is the integrator's: a file on disk, a
key-value store, or octets a control plane already holds. It reaches a router through a
`TopologyProvider`, which delivers those octets and never constructs a snapshot. Validation, the
canonical form, the digest, the epoch comparison, and the preparation are the library's.
[Topology providers](#topology-providers) gives the interface, and the two providers the library
carries are the sections below it.

An epoch is published and never inferred. The library assigns none and increments none. It compares
a candidate document against the snapshot in force by integer comparison of `epoch` and octet
comparison of `topologyId`, reading no clock, no provider revision, and no document timestamp. A
document below the epoch in force is refused as stale, one at that epoch carrying the same digest is
a no-op that refreshes freshness, one at that epoch carrying a different digest is refused as a
conflict, and one above it is installed.
Two settings are checked before any of that, and both hold when nothing is in force, which is the
state a process is in after a restart. `expectedTopologyId` names the cluster this process routes
for, so a document under any other identifier is refused as a conflict rather than adopted, which is
what an unconfigured router does with the first document it accepts. `minEpoch` is a floor, and a
document below it is refused as stale even where it is the first to arrive, which is how an operator
stops a process coming back up on a document older than the one it was serving.

```java
RouterConfig config = RouterConfig.builder()
        .provider(provider)
        .expectedTopologyId("orders")
        .minEpoch(41)
        .build();
```

[`10-specification.md`](../../docs/design/10-specification.md#monotonicity-and-acceptance) carries
the whole acceptance table, including the order the rows are evaluated in: the identifier is checked
before the floor, so a foreign document below the floor is a conflict and not stale.

Installation replaces the snapshot in force whole, as a single atomic replacement of the reference a
routing call reads. A routing call already under way read that reference at entry and computes its
whole result from the snapshot it read, so an installation changes no call in flight and invalidates
no decision already answered. The router retains a bounded number of earlier snapshots for
[the recipient check](#the-recipient-check), and routes against none of them.

### The nodes for one key

The everyday call takes a key and answers the nodes that hold it, in the order to ask them.

```java
RoutingDecision decision = router.route("tenant-42", RouteOptions.DEFAULTS);
NodeId owner = decision.primary().node();
List<PreferenceEntry> candidates = decision.entries();
FencingToken token = decision.token();
```

`primary()` is the head of the list and the node to ask first. `entries()` holds that head and the
nodes behind it, each entry carrying its position, its `REPLICA` or `FALLBACK` role, its health
state, and whether the health filter left it attemptable. `shard()` names the shard the key belongs
to under the strategies that enumerate shards, and `token()` is what a request carries so the
recipient can check it. [The routing decision](#the-routing-decision) gives every member.

Trying the nodes in turn is `router.attempts(decision)` rather than a loop over `entries()`, because
the walk applies the health filter, the attempt limit, and the retry budget.
[Walking an attempt sequence](#walking-an-attempt-sequence) gives it.

The cost is bounded, and where it is paid matters more than what it is.

- A routing call is a pure function of the snapshot in force, the routing key, and the health
  signals the caller reported. It reads the snapshot reference once at entry and takes no lock.
- The work that grows with the node set is preparation, and preparation is performed once per
  snapshot at installation rather than once per call.
  [`10-specification.md`](../../docs/design/10-specification.md#placement-cost-model) bounds
  preparation, one routing call, and the resident size of a prepared placement.
- A call consumes a bounded prefix of the candidate ordering rather than the whole ordering: the
  greater of the achieved replica count and the resolved attempt limit, raised by the entries the
  health filter skips. `CORE-046` fixes the prefix and `PLACE-071` bounds it, and
  [`adr/0034`](../../docs/design/adr/0034-lazy-candidate-traversal-surface.md) records the cursor
  that leaves the ordering beyond the prefix uncomputed.
- `rendezvous` is the exception. It determines its first candidate from the scores of the whole
  eligible node set, so no prefix of its ordering costs less than the whole; `ring`, `slot`, and
  `directory` pay for the prefix alone.
- `preferenceList()` and an explain record each consume the whole ordering, so both belong off the
  routing path.

The Java build holds an allocation gate over one routing call, in
`src/test/java/com/codeheadsystems/sharder/api/AllocationGateTest.java`. A ring of a hundred nodes
and a ring of a thousand take one ceiling between them, and a rendezvous topology takes a ceiling of
its own.

## A first router

### Dependency coordinates

| Coordinate | Value |
|---|---|
| Group | `com.codeheadsystems` |
| Artifact | `sharder` |
| Java module | `com.codeheadsystems.sharder` |
| Minimum JDK | 21 |
| Version the build carries | `0.1.0-SNAPSHOT` |

Nothing is published. [`adr/0083`](../../docs/design/adr/0083-publication-as-the-last-stage.md)
puts publication after every port passes the conformance suite, so a build resolving those
coordinates from a remote repository finds nothing today, and the library is consumed from a local
build of `ports/java/`. [`README.md`](README.md) gives that build.

A modular consumer declares the module:

```java
module com.example.orders {
    requires com.codeheadsystems.sharder;
}
```

The artifact requires `java.base` alone at runtime. Everything under
`com.codeheadsystems.sharder.core.internal` and `com.codeheadsystems.sharder.migrate.internal` is
unexported and changes between any two versions.

### A topology document

A topology is a JSON document. It names the topology, its epoch, the placement strategy, and the
nodes, and every other member takes a default.

```json
{
  "formatVersion": "1.0",
  "topologyId": "objects",
  "epoch": 1,
  "domainLevels": ["zone"],
  "replication": { "factor": 3, "spread": ["zone"], "spreadPolicy": "relaxed" },
  "strategy": {
    "kind": "ring",
    "tokenAssignment": "derived",
    "tokensPerWeightUnit": 64,
    "maxTokensPerNode": 4096
  },
  "nodes": [
    { "id": "store-a1", "weight": 1, "domains": { "zone": "eu-west-1a" } },
    { "id": "store-a2", "weight": 1, "domains": { "zone": "eu-west-1a" } },
    { "id": "store-b1", "weight": 1, "domains": { "zone": "eu-west-1b" } },
    { "id": "store-b2", "weight": 1, "domains": { "zone": "eu-west-1b" } },
    { "id": "store-c1", "weight": 1, "domains": { "zone": "eu-west-1c" } },
    { "id": "store-c2", "weight": 1, "domains": { "zone": "eu-west-1c" } }
  ]
}
```

`topologyId` names a sequence of topologies over one cluster. `epoch` orders that sequence, and two
epochs under two identifiers are incomparable. The four strategy kinds are `ring`, `rendezvous`,
`slot`, and `directory`, and the members each one carries are in
[`20-topology-format.md`](../../docs/design/20-topology-format.md#strategy-configuration).

### The first routing call

A router is built from a `RouterConfig`, which carries the provider and every local setting. It
begins loading as it is constructed, and it is closed when the application shuts down.

```java
byte[] document = Files.readAllBytes(Path.of("objects.topology.json"));
try (Router router = Sharder.router(RouterConfig.builder()
        .provider(new InMemoryTopologyProvider(document))
        .build())) {
    RoutingDecision decision = router.route("tenant-42", RouteOptions.DEFAULTS);
    NodeId primary = decision.primary().node();
    FencingToken token = decision.token();
}
```

`route(byte[])` takes the key's octets, and `route(String, RouteOptions)` takes the UTF-8 encoding
of the text under `KEY-003`. A key above `maxKeyBytes` is refused with `InvalidArgumentException`.

A router that has installed no document yet raises `UnreadyException` from a routing call, and
`snapshot()` answers empty until the first installation under `CORE-032`. An integrator that must
not serve before a topology is in force checks `snapshot()` rather than catching the condition.

A `RouterConfig` is validated whole at build time under `CFG-003`. No setting is clamped and no
unrecognised setting is ignored, so a value outside its range refuses construction rather than
taking effect quietly. `router.configuration()` reports every value in force, defaults included.

### The routing decision

A `RoutingDecision` is immutable, and it reads one snapshot from first candidate to last under
`CORE-041`, so a decision held across an installation answers what was decided rather than what
would be decided now.

| Member | What it holds |
|---|---|
| `routingKey()` | the key after the document's key transform |
| `shard()` | the shard the key belongs to, absent where the strategy names no shards |
| `token()` | the fencing token of the snapshot the decision was taken against |
| `factor()` | the effective replication factor |
| `replicaCount()` | the replica prefix length achieved |
| `attemptLimit()` | the resolved attempt limit for this call |
| `primary()` | the head of the preference list, whatever its health |
| `entries()` | the materialised prefix of `CORE-046` |
| `ordered()` | the same entries after read affinity, where a read asked for it |
| `preferenceList()` | the whole preference list, recomputed on each call under `CORE-047` |
| `relaxedLevels()` | the spread levels the chosen relaxation stage did not enforce |
| `shortfall()` | why the replica prefix is shorter than the factor |
| `filterFailedOpen()` | whether the health filter left every entry attemptable |

`entries()` is a prefix rather than the whole list: its length is the lesser of the preference list
length and the greater of the replica count and the attempt limit. `preferenceList()` recomputes
the whole ordering from the snapshot and belongs off the routing path.

Each entry is a `PreferenceEntry` carrying the node, its zero-based position, its `Role` of
`REPLICA` or `FALLBACK`, its health state, and whether the health filter left it attemptable.

The decision carries no key, no address, no tags, and no metadata under `CORE-043`. An address is
resolved by the integrator from the `nodes` list of the snapshot.

[`40-java-binding.md`](../../docs/design/40-java-binding.md#routing-decision) is the reference for
the type.

## Node management

### Nodes and weights

A node carries an identity, an administrative state, a placement weight, its failure domains, its
tags, and an optional address. The identity is the only attribute placement consumes; the address
and the tags are opaque to it, and a tag is readable by an override constraint.

Weight is a share of capacity in weight units. Under `ring` a node of weight `w` owns
`min(w * tokensPerWeightUnit, maxTokensPerNode)` tokens, and under `rendezvous` it contributes
`min(w * virtualNodesPerWeightUnit, maxVirtualNodesPerNode)` scoring slots. A node of weight 0 is in
the placement set and receives no keys from the hash strategies. Weight is advisory under `slot`,
where the assignment itself is authoritative.

### Administrative states

`AdministrativeState` holds four values, and two of them are the placement set of `PLACE-001`.

| State | Placeable | What it means |
|---|---|---|
| `active` | yes | placeable and serving |
| `draining` | yes | placeable and serving, with its shards moving away at a later epoch |
| `joining` | no | announced and not placeable |
| `leaving` | no | placed off and not placeable |

A `joining` node and a `leaving` node appear in no candidate ordering. They are in the document so
that movement is prepared before ownership arrives and cleaned up after ownership departs, which is
why a node enters or leaves the cluster over two documents rather than one.

The health states of `HealthState` are a separate vocabulary and never map onto these. One says
what an authority published; the other says what a caller observed.

### Adding a node

1. Publish a document at the next epoch carrying the new node with `state` of `joining`. Its
   ownership is unchanged, and the ownership delta for that epoch is empty.
2. Prepare the movement against that document, by an orchestrated migration or by whatever the
   integrator's store does.
3. Publish a document at the epoch after it with the node's `state` of `active`. The node is now in
   the placement set and the delta names the shards it gained.

### Removing a node

1. Publish a document at the next epoch with the node's `state` of `draining`. It is still
   placeable and still serving, and an operator reads the delta at the epoch that moves its shards.
2. Publish a document at the epoch after it with the node's `state` of `leaving`. It is out of the
   placement set, it owns nothing, and it is still present for cleanup.
3. Publish a document at the epoch after that with the node absent.

### Topology providers

A `TopologyProvider` is where documents come from. It answers a pull, delivers a push, or both, and
at least one of the two capabilities is true under `CORE-081`.

```java
public interface TopologyProvider extends AutoCloseable {
    Capabilities capabilities();
    Loaded load(Optional<SourceVersion> known);
    Subscription watch(TopologySink sink);
    void close();

    record Capabilities(boolean pull, boolean push) { }
}
```

A provider delivers octets and never constructs a snapshot. Validation, the canonical form, the
digest, the monotonicity check, and the preparation are the library's. `load` answers
`Loaded.Document` with the version the source holds it at, or `Loaded.Unchanged` where the `known`
version is still the one the source holds, under `CORE-086`. A `SourceVersion` is opaque to the
library: a file provider builds one from the modification time and the size, and an adapter over a
key-value store builds one from its revision.

A provider that throws has the failure caught, counted, reported as a provider error with the
original attached as the Java cause, and followed by a backoff under `ERR-063`.

### The in-memory provider

`InMemoryTopologyProvider` holds one document, pulls, and pushes. `publish` replaces the document
and delivers it to every subscriber synchronously, so a control plane with a document already in
hand needs no file and no adapter.

```java
InMemoryTopologyProvider provider = new InMemoryTopologyProvider(document);
try (Router router = Sharder.router(RouterConfig.builder().provider(provider).build())) {
    provider.publish(nextDocument);
    long epoch = router.snapshot().orElseThrow().epoch();
}
```

### The file provider

`FileTopologyProvider` reads one file. It pulls and does not push, because a file changes without
telling anyone, so the document reaches a running router by a poll or by an explicit refresh.

```java
try (Router router = Sharder.router(RouterConfig.builder()
        .provider(new FileTopologyProvider(Path.of("/etc/sharder/objects.topology.json")))
        .executor(Executors.newSingleThreadScheduledExecutor())
        .pollInterval(Duration.ofSeconds(15))
        .build())) {
    router.route(key);
}
```

Polling and reconciliation run on the executor the integrator supplies, under `CFG-012`. Where no
executor is supplied, `refresh()` is the only path a document arrives by:

```java
router.refresh();
```

`refresh()` is a call an integrator made, so a topology condition is raised out of it rather than
recorded. A deployment that watches the file itself calls it as the file changes.

### Validation before publication

`router.loader().validate(document)` answers the snapshot a document would become and installs
nothing under `TOPO-191`. The snapshot it answers carries no installation instant. An invalid
document raises `InvalidTopologyException` carrying every validation error.

```java
TopologySnapshot candidate = router.loader().validate(document);
long epoch = candidate.epoch();
```

`Sharder.loader(config)` answers the same surface without a router in hand, so an operator checks a
document before publishing it.

## Replication and failure domains

### The preference list

A routing call produces one ordered list of nodes for a key: the preference list. Its head is the
primary, its first `replicaCount()` entries are the replica prefix, and the entries after those are
the fallback tail. The replication factor comes from `replication.factor`, and an override entry
carries a factor of its own for the keys it matches.

Node distinctness is enforced unconditionally under `SPREAD-002`: no node appears twice.

### Spread over failure domains

`domainLevels` declares the failure domain hierarchy, coarsest first, and each node gives its path
through it. `replication.spread` names the levels across which the replica prefix is placed in
distinct domains.

Two nodes share a failure domain at a level when their domain paths agree at that level and at
every coarser level of `domainLevels`. The path spans `domainLevels` and never `replication.spread`,
so a topology declaring `["region", "zone", "rack"]` and spreading over `["rack"]` alone still
compares the whole triple, and a rack named `r01` in two zones is two racks.

### The relaxation ladder

A cluster cannot always satisfy every spread level. Over `m` spread levels the builder holds `m + 1`
relaxation stages: stage 0 enforces every named level, and each later stage drops the coarsest level
still enforced, down to stage `m`, which enforces node distinctness alone.

`spreadPolicy` decides which stage is used.

| Policy | Behaviour |
|---|---|
| `relaxed` | the smallest stage that fills the replication factor, and stage `m` where none does |
| `strict` | stage 0 alone, and a shorter replica prefix where it does not fill the factor |

The chosen stage is a pure function of the snapshot and the routing key under `SPREAD-020`, so two
callers holding the same snapshot choose the same stage and compute the same preference list.
`decision.relaxedLevels()` names the levels the chosen stage did not enforce, in spread order, and
it is empty under `strict` and at stage 0.

A replica prefix shorter than the factor is an answer rather than a failure. `decision.shortfall()`
says why: `NODES` where the candidate ordering ran out, `DOMAINS` where a strict spread level
admitted no further placement, and `NONE` where the list reached the factor.

[`10-specification.md`](../../docs/design/10-specification.md#spread-degradation) states the ladder.

## Health and failover

### Reporting health signals

The library observes nothing. Every health state it holds is derived from a `HealthSignal` a caller
reported.

```java
router.health().report(new HealthSignal(node, Outcome.TIMEOUT, clock.millis()));
router.health().advance(clock.millis());
```

`Outcome` is one of `SUCCESS`, `FAILURE`, `TIMEOUT`, `REFUSED`, and `CANCELLED`. The middle three
count as failures in the sliding window; `CANCELLED` counts as neither, because a caller that gave
up on its own has observed nothing about the node. `advance` moves time forward, which is what
expires a window and ends an ejection.

An integrator whose signals live elsewhere supplies its own `HealthView` through
`RouterConfig.Builder.healthView`, and the parameters of the built-in machine are then unread. The
built-in parameters are a `HealthSettings` record, and every one of them is caller-local: health
state is never serialised into a document, never carried in a fencing token, and never exchanged
between callers.

### The five health states

| State | Effect on the attempt sequence |
|---|---|
| `UNKNOWN` | attemptable; no signal has been ingested |
| `AVAILABLE` | attemptable |
| `SUSPECT` | attemptable; the node has failed short of the ejection threshold |
| `PROBATION` | attemptable on an admitted probe |
| `UNAVAILABLE` | skipped by the health filter |

A node for which nothing has been reported holds `UNKNOWN`, so a caller that ingests no signals
filters nothing and its attempt sequence equals its preference list.

### Agreed ownership under a local filter

The preference list is agreed and the attempt sequence is local. The health filter produces the
attempt sequence by removing entries from the preference list, and it never reorders the entries
that remain, under `FAIL-002`. The primary is the head of the preference list whatever its health
state, under `FAIL-004`.

Two callers holding the same topology identifier and epoch therefore compute the same preference
list for the same key: the same identities, in the same order, with the same replica prefix length.
Health state, attempt history, caller identity, and elapsed time change none of it, under
`FAIL-001`. What a caller may vary is its own health view, its attempt limit, its retry budget,
whether it asks for read affinity, and whether it attempts at all.

Where the filter would leave nothing attemptable, the attempt sequence is the whole preference list
in preference list order and the decision records `filterFailedOpen()`, under `FAIL-012`. A filtered
list is never empty while the preference list is not.

### Walking an attempt sequence

`Router.attempts(decision)` answers the walk. It belongs to one request and one unit of execution,
it is not synchronised, and it never waits, sleeps, or backs off: spacing between attempts belongs
to the caller.

```java
RoutingDecision decision = router.route(key);
AttemptSequence attempts = router.attempts(decision);
for (Optional<NodeId> next = attempts.next(); next.isPresent(); next = attempts.next()) {
    NodeId node = next.get();
    try {
        send(node, key, decision.token());
        attempts.recordOutcome(node, Outcome.SUCCESS);
        return;
    } catch (TimeoutException failure) {
        attempts.recordOutcome(node, Outcome.TIMEOUT);
    }
}
throw new IllegalStateException("every attempt was spent");
```

`next()` answers the next attemptable entry in preference list order, or empty where the sequence is
exhausted. `recordOutcome` forwards the signal to the health view and accounts the attempt against
the retry budget; the two-argument form reads the configured clock and the three-argument form takes
the instant. `remaining()` says how many attempts the sequence may still offer.

The walk is drawn from the whole preference list rather than from `entries()`, under `FAIL-014`, so
skipping an unavailable entry of the materialised prefix continues along the list.

### The attempt limit and the retry budget

Two bounds end a walk, and they bound different things.

The attempt limit bounds one request. A call inherits the configured `attemptLimit`, which defaults
to the factor plus 2, and `RouteOptions.withAttemptLimit` sets it per call. It is counted in
attempts rather than in preference list positions, and it is clamped to the length of the attempt
sequence under `FAIL-022`.

The retry budget bounds the whole router. It accounts first attempts and retries over a sliding
window and refuses a retry once retries exceed `retryBudgetPercent` of first attempts plus
`retryBudgetMinimum`. The first `next()` of a sequence is a first attempt, every later one is a
retry, and a followed redirect is a retry. A first attempt is always permitted, so the budget
cannot make a key unroutable. One budget is held by the router and spans every key, shard, and
node it routes, because a per-key budget would permit a storm assembled from many keys.

`next()` answers empty when the attempt limit is reached, when the budget refuses a further attempt,
or when the preference list is spent, whichever comes first. An exhausted walk and an empty
preference list are distinct conditions: the first raises `ExhaustedException` and the second
`NoCandidateException`.

[`10-specification.md`](../../docs/design/10-specification.md#retry-budgets) states the budget
arithmetic.

## Read affinity

`routeForRead` reorders within the replica prefix and leaves ownership untouched. Reads and writes
share one preference list, and the library holds no second placement for reads.

```java
RoutingDecision read = router.routeForRead(key,
        AffinityRequest.of("zone", List.of("eu-west-1a")), RouteOptions.DEFAULTS);
List<PreferenceEntry> local = read.ordered();
```

`level` names a declared level of `domainLevels`. `path` carries one identifier for every level from
the coarsest through `level`, so a request at `zone` under `domainLevels` of `["region", "zone"]`
carries two identifiers. An undeclared level and a path of any other length are both refused with
`InvalidArgumentException` rather than answered without affinity.

The reordering is a stable partition of the first `window` entries into the entries whose domain
path agrees with `path` and the entries that do not, each group keeping preference list order.
`window` defaults to the replica count and is clamped to it, so no entry crosses the boundary
between the replica prefix and the fallback tail. Entries at or beyond the window are untouched.

`routeForRead` reads no health state, and `ordered()` holds the reordered list while `entries()`
and `primary()` still hold the unreordered one. A write target is selected from the unreordered
list. Where no replica shares the caller's domain path, the reordered list equals the preference
list, which is an answer rather than an error.

[`10-specification.md`](../../docs/design/10-specification.md#read-routing-and-read-affinity) states
the reordering.

## Fencing

### The fencing token

Every routing decision carries a `FencingToken`: the topology identifier and the epoch the decision
was taken against. A caller attaches it to every request that decision routes, and the recipient
compares it against its own snapshot. The token may carry the topology digest as a third component,
under the `tokenDigest` setting, and a recipient reads that component in no ordering or acceptance
decision.

`token.toByteArray()` is the canonical encoding a wire format carries:
`u32be(len(topologyId)) || topologyId || u64be(epoch)`. Two tokens are equal on the identifier and
the epoch alone.

### The recipient check

Each node process builds a router over the same topology and asks it for the receiving side of its
own identity.

```java
Recipient recipient = router.recipient(NodeId.of("store-a1"));
Verdict verdict = recipient.check(decision.token(), decision.routingKey().toBytes());
recipient.admit(decision.token(), decision.routingKey().toBytes());
```

`check` raises nothing and answers a `Verdict`.

| Member | What it holds |
|---|---|
| `relation()` | `SAME`, `SENDER_BEHIND`, `SENDER_AHEAD`, `UNKNOWN_EPOCH`, or `IDENTITY_MISMATCH` |
| `ownership()` | `OWNER`, `NOT_OWNER`, or `UNKNOWN`, against the recipient's own snapshot |
| `ownershipStable()` | whether the recipient owned the key at the token's epoch and owns it now |
| `currentOwner()` | the node the recipient would route the key to |
| `localToken()` | the recipient's own token |

Ownership is the replica prefix rather than the whole preference list: a node the key falls back to
is attemptable and is not an owner.

`admit` applies the configured `recipientPolicy` to the verdict and raises where the policy refuses,
with `NotOwnerException`, `EpochMismatchException`, or `IdentityMismatchException`. The two policies
differ in one case, a sender behind whose ownership is stable: `STRICT`, the default, refuses it and
`STABLE` serves it. A recipient never uses a received token to change its own snapshot.

### The redirect walk

A refusal names a `currentOwner`. A caller retries against that node through the attempt sequence,
which holds the identities already attempted and applies the bound and the budget.

```java
try {
    NodeId retryAt = attempts.followRedirect(verdict.currentOwner().orElseThrow());
    send(retryAt, key, decision.token());
} catch (RedirectExhaustedException exhausted) {
    // the walk is over: no arbitrary node is a fallback
}
```

`followRedirect` refuses in a fixed order: the `maxRedirects` bound, which defaults to 2; an
identity already attempted for this request; an identity absent from the snapshot's `nodes` list;
and last the retry budget. A walk refused for one of the first three is accounted against no budget.
A followed redirect counts against the retry budget as a retry and against the attempt limit not at
all.

A refusal carrying a `localToken` whose epoch is above the caller's is a reason to refresh the
topology before retrying, and never a reason to install that epoch.

[`10-specification.md`](../../docs/design/10-specification.md#caller-behaviour-when-fenced) states
the walk.

## Resharding and shard lineage

An authority changes a topology by publishing a whole document at a higher epoch. What that change
does to the shards is the thing worth being deliberate about, and there are two shapes.

A **reshard** keeps the shards and moves their ownership. The node set or the replication factor
changes, every shard keeps its identifier and the keys it holds, and the ownership delta names the
shards whose replica set differs.

A **split** or a **merge** changes which shards exist. Adding a ring token divides the extent of one
shard into two; removing one folds two extents into one. The shard identifiers are not the same on
both sides, so the ownership delta, which joins on the identifier, has no answer for the ones that
appeared or vanished. The lineage is the second join, over the keys a shard holds, and it is what
tells a plan where a new shard's contents come from.

The library derives the lineage from the topology document. Nothing in the document records it, and
no member has to be authored to get a split.

### Which shape to reach for

Prefer a split or a merge where the change is about capacity for part of the keyspace.

| | Split or merge | Reshard |
|---|---|---|
| What changes | which shards exist, and the keys each holds | who owns the existing shards |
| What moves | the contents of the extents that divided or folded | the contents of every shard whose replica set changed |
| Typical cause | one shard outgrew a node, or two are small enough to combine | a node joined or left, or the replication factor changed |
| Cost | proportional to the extent that moved | proportional to what the strategy reassigns |
| Reversible | yes, by folding back or dividing again | yes, by publishing the earlier shape |

A split moves less because it disturbs less: the keys outside the divided extent do not change
shard, so nothing about them moves. A reshard that redistributes the whole keyspace to add capacity
moves data that was already where it belonged. Where both would serve, the split is the cheaper
change, and it is the one to publish.

A reshard is the right shape when what changed is the cluster rather than the keyspace. Adding a
node to a `ring` topology under derived tokens is a reshard and a split at once, because the node's
tokens divide the extents they land in, and the library plans it as one change.

Under `slot` the shard count is fixed by `slotCount`, which `TOPO-231` holds equal across a
comparable pair, so every change is a reshard. Choose `slotCount` generously at the outset: it is
the one decision here that a later epoch cannot revisit.

The library does not enforce the preference. It refuses a change whose boundaries neither divide nor
fold an extent whole, because that has no lineage to name, and it reports the classification through
the `migration.lineage` event so an operator can see which shape an epoch took. It refuses nothing
else, and it never declines a reshard on the ground that a split would have been cheaper, because it
cannot tell a deliberate rebalance from a lazy one.
[`adr/0091`](../../docs/design/adr/0091-lineage-classification-as-a-signal.md) argues that.

### The unaligned change

A change that moves a boundary without either dividing or folding an extent whole is refused with
`PlanRefusedException` and the cause `unalignedLineage`. Removing a ring token and adding a
different one inside the same extent in one epoch is the common way to reach it.

Publish it as two epochs instead: the first divides every extent the change crosses, the second
folds the pieces into their destinations. Each is plannable on its own, and each leaves a topology
that routes correctly if the second is delayed.

Under `directory` an extent is a matcher narrowed by the entries that outrank it, so refining
`prefix:ab` into `ab0` and `ab1` is a division and dropping the two back to `ab` is a fold. An entry
that wins keys the earlier table matched to nothing names a shard with no parent, which moves no
contents and needs no handoff.

### A new epoch

A change is a whole document at the next epoch, published the way every other document reaches a
router.

```java
provider.publish(documentAtEpochTwo);
```

The epoch belongs to the authority, and what the library does with an arriving document is under
[A document reaching a running router](#a-document-reaching-a-running-router). A document below the
epoch in force is refused, so an epoch that carried a split is not undone by republishing the epoch
before it; the way back is a further epoch that folds the pieces again.

### The ownership delta

`OwnershipDelta.between(from, to)` answers the shards whose replica set differs between two
snapshots.

```java
TopologySnapshot before = router.snapshot().orElseThrow();
provider.publish(documentAtEpochTwo);
TopologySnapshot after = router.snapshot().orElseThrow();

OwnershipDelta delta = OwnershipDelta.between(before, after);
for (ShardChange change : delta.changes()) {
    ShardId shard = change.shard();
    List<NodeId> gained = change.gained();
    List<NodeId> lost = change.lost();
}
```

A `ShardChange` carries the shard, the replica set before, the replica set after, and the two
differences. The sets are the entries whose role is `REPLICA`, which is the achieved replica prefix
rather than the whole preference list, so a node a shard merely falls back to has gained nothing.

The delta is computed on demand rather than at installation. Its entries come in two groups: first
the shards the later snapshot enumerates, in that snapshot's order, then the shards only the earlier
one enumerates, so a shard that vanished reports the nodes that lost it rather than going
unreported. Two snapshots whose shard identity is not comparable refuse the computation with
`InvalidArgumentException` rather than reporting every shard as wholly changed. The same pair given
to `plan` is refused with `PlanRefusedException` and the cause `incomparableShards`, so one
condition reaches a caller as whichever condition the surface it called raises.
Under `rendezvous` the delta is empty, because the strategy enumerates no shards.

Reading the delta is enough where the integrator's store moves data by itself. Where each move has
to be sequenced so that no request is served by both the old owner and the new one,
[Orchestrated migration](#orchestrated-migration) gives the coordinator.

[`10-specification.md`](../../docs/design/10-specification.md#ownership-delta) states the delta.

### The lineage operation

`coordinator.lineage(from, to)` answers where each shard's contents come from across two snapshots.
It installs nothing and plans nothing, so an integrator sizes a change before deciding to run it,
which is the reason [the ownership delta](#the-ownership-delta) is a call rather than a product of
installation and the reason this is one too.

The two answer different questions, and an epoch that changes which shards exist needs both.

| | Ownership delta | Lineage |
|---|---|---|
| Joins on | the shard identifier | the keys a shard holds |
| Answers | which shards changed owner | where a shard's contents are |
| A shard that appeared | reports it gaining every replica | names the parents its extent draws from |
| A shard whose extent grew and kept its replicas | no entry | `MERGED`, with its parents |
| Under `rendezvous` | empty | refused |

```java
ShardLineage lineage = coordinator.lineage(before, after);
for (ShardLineage.Entry entry : lineage.entries()) {
    ShardId shard = entry.shard();
    LineageClass became = entry.lineage();
    List<ShardId> parents = entry.parents();
}
```

`LineageClass` holds eight values. `UNCHANGED` and `MOVED` are the two whose extent is the one the
parent held, which `extentUnchanged()` reports, and which every shard falls into where an epoch
moved ownership alone.

| Class | What became of the shard |
|---|---|
| `UNCHANGED` | one parent of equal extent, and the replica set is equal |
| `MOVED` | one parent of equal extent, and the replica set differs |
| `DIVIDED` | one parent whose extent strictly contains this one |
| `MERGED` | two or more parents whose extents this one contains |
| `FRESH` | no parent, this extent meeting no extent of the earlier snapshot |
| `SPLIT` | only the earlier snapshot enumerates it, and its extent divides into two or more |
| `FOLDED` | only the earlier snapshot enumerates it, and one child's extent contains it |
| `VACATED` | only the earlier snapshot enumerates it, and its extent meets no later extent |

`parents()` names the shards of the earlier snapshot for an entry the later snapshot enumerates, and
the shards of the later one for an entry only the earlier snapshot enumerates. It is empty under
`FRESH` and `VACATED`. The entries come in the two groups the ownership delta comes in: first the
shards the later snapshot enumerates, in that snapshot's order, then the shards only the earlier one
enumerates.

`lineage.entry(shard)` answers one entry. `lineage.counts()` answers how many shards fall in each
class, which is what the `migration.lineage` event reports. `lineage.identity()` answers whether
every shard kept the extent it held, which is every pair under `slot` and every pair under any kind
whose shard set did not move.

The call refuses with `PlanRefusedException`, whose `reason()` carries one of three causes. Two
snapshots carrying different topology identifiers, and two whose shard identity is not comparable,
both give `incomparableShards`, because a pair under two identifiers joins on nothing and `plan`
answers the same cause for the same input. A strategy that enumerates no shard, which is
`rendezvous`, gives `strategyUnsupported`. A boundary that moved without either dividing or folding
an extent whole gives `unalignedLineage`, which is [the unaligned change](#the-unaligned-change).

A snapshot this library did not produce is an `InvalidArgumentException` rather than a refusal, as
it is for `plan`.

[`10-specification.md`](../../docs/design/10-specification.md#lineage-classification) states the
classes.

## Orchestrated migration

### Store and strategy preconditions

`MOVE-321` requires `commitCutover` to be a single-winner write over a store that both the source
and the destination read. A single-winner cutover is the only mode the coordinator supports. An
integrator whose store offers no such write cannot use the coordinator, and moves a shard outside
the library.

The strategy matters too. `ring`, `slot`, and `directory` support orchestrated migration, because
each enumerates shards. `rendezvous` does not, and `plan` refuses a pair of `rendezvous` snapshots
with `PlanRefusedException` rather than answering an empty plan.

### The coordinator and the plan

```java
HandoffCoordinator coordinator = Sharder.coordinator();
MigrationPlan plan = coordinator.plan(before, after, hooks, MigrationPolicy.defaults());
```

The coordinator holds nothing between calls and installs nothing. A plan is a pure function of the
two snapshots and the policy, which is what lets a restarted coordinator rebuild the same plan. A
plan is never created as a side effect of installing a snapshot.

`plan` refuses with `PlanRefusedException` where the two snapshots do not join on shard identity,
where the target epoch does not advance, where the strategy supports no orchestrated migration,
where a destination sits outside the target placement set, where the change is unaligned, or where
the change needs a local step the hooks declare no support for. `PlanRefusedException.Cause` names
each of them.

The handoffs come from the lineage rather than from the ownership delta. One handoff is admitted for
each destination of each shard of the later snapshot that does not already hold the contents of a
parent that shard's extent draws from, so a shard whose extent grew carries a handoff even where its
replica set is unchanged and the delta reports nothing for it. A shard with no parent has no
contents to move and carries none, and no handoff names one node as both its source and its
destination.
[`10-specification.md`](../../docs/design/10-specification.md#plan-construction-over-a-lineage)
states the derivation.

A split or a merge also needs work on a node that holds the parent under the earlier snapshot and
the child under the later one, and that work moves nothing between nodes: the node divides or folds
its own copy so that what it holds matches the extent it owns. A plan carries it as a local step,
whose source and destination are the same node, sequenced before every handoff that draws from a
divided parent and after every handoff that draws into a folded shard. A local step counts against
the policy's concurrency as a handoff does.

A plan is passive. It advances when `step` is called and never on a timer, a thread, or an
installation. One `step` advances at most one handoff by at most one hook call. Concurrent calls to
`step` are permitted, and no two of them advance the same handoff.

```java
MonotonicClock clock = MonotonicClock.systemNanoTime();
for (StepOutcome outcome = plan.step(clock); !(outcome instanceof StepOutcome.Idle);
        outcome = plan.step(clock)) {
    if (outcome instanceof StepOutcome.Settled settled) {
        record(settled.id(), settled.terminalState());
    }
}
```

`StepOutcome` is sealed over five records: `Idle` where nothing was admissible, `Progressed` where a
handoff moved data and stayed in its state, `Advanced` where a handoff changed state, `Deferred`
where a handoff asked to be called again after an interval, and `Settled` where a handoff reached a
terminal state. A step never spins, waits, or sleeps to avoid answering `Idle`.

`Idle` says that nothing was admissible at that call rather than that the plan is finished, so a
driver running under a pressure gauge waits and calls again, and reads `plan.summary()` to see
whether every handoff is terminal.

`plan.handoffs()` iterates the handoff identifiers in a stable order, `plan.state(id)` answers the
state one is in, `plan.summary()` counts the handoffs in each state, and `plan.delta()` answers the
ownership delta the plan moves.

One monotonic source is supplied across the calls of one plan. The clock passed to a call is the
source the plan reads for that call alone, and a comparison between readings of two sources measures
nothing.

### Movement hooks

`MovementHooks` is what the integrator implements to move data. The library sequences the calls,
records what they answer, and enforces the ordering; it reads no durable state of its own.

```java
public interface MovementHooks {
    HookDeclaration declare();
    HookResult prepare(HandoffContext context);
    TransferResult transfer(HandoffContext context, int budget);
    CatchUpResult catchUp(HandoffContext context, int budget);
    QuiesceResult quiesce(HandoffContext context);
    CutoverResult commitCutover(HandoffContext context);
    VerifyResult verify(HandoffContext context);
    HookResult cleanup(HandoffContext context);
    HookResult rollback(HandoffContext context);
    HookResult divide(HandoffContext context);
    HookResult combine(HandoffContext context);
    ObserveResult observe(HandoffContext context);
}
```

`divide` and `combine` are the hooks a local step calls, and each carries a default that refuses
permanently. A storage that has not implemented them declares `supportsLineage` of false, and the
plan that would call one is refused before any call is made.

Every hook is called from whichever unit of execution called `step`, at most one per call. A hook
that raises rather than answering has its condition surfaced unchanged.

`HandoffContext` tells a hook the shard, the shard its contents come from, the topology identifier,
the source epoch and the target epoch, the source node, the destination node, the attempt number,
and the deadline in milliseconds. `sourceShardId` differs from `shardId` only where a lineage
divided or folded an extent, and a hook that read `shardId` alone would search the source for a
shard the source does not hold; the convenience constructor defaults it to `shardId`. The target
epoch is the handoff's own rather than the plan's, so the two differ after a rebase.

`declare()` is read once per plan. A `HookDeclaration` carries four components: the budget unit, and
whether the hooks support rollback, verification, and lineage.

```java
HookDeclaration declaration = HookDeclaration.of("rows");
HookDeclaration dividing = HookDeclaration.withLineage("rows");
HookDeclaration withoutVerification = new HookDeclaration("rows", true, false, false);
```

`budgetUnit` is opaque to the library: it is reported and never interpreted, and the unit counts a
transfer and a catch-up answer are summed and compared and nothing else. `supportsVerify` of false
is what lets `cleanup` follow a committed cutover directly, with no `verify` call.

`supportsLineage` states whether the integrator's storage can divide a copy in place and fold two
adjacent copies back together, which is a property of that storage rather than of the strategy. `of`
answers false for it and `withLineage` answers true. A plan that needs a local step over hooks that
declare false is refused with the cause `lineageUnsupported`, rather than reaching `DIVIDING` with
no hook to call and no way back.

`HookResult` is sealed over `Success`, `Deferred`, `Retryable`, and `Permanent`. `Retryable` is
retried up to `maxAttemptsPerStep` with the policy's backoff, `Permanent` is never retried, and
`Deferred` counts against no attempt budget and is not retried before its interval has elapsed on
the supplied clock. `TransferResult`, `CatchUpResult`, and `QuiesceResult` each compose a
`HookResult` with what the call measured.

`commitCutover` has six answers, and they are distinct. `Committed` wrote the record.
`AlreadyCommitted` found it already there naming this destination, which is what makes a retried
cutover idempotent. `Lost` found a record naming another owner, which aborts the handoff.
`Undetermined` does not know whether a record exists, which no later step establishes. `Retryable`
and `Permanent` are the two failures.

### The handoff states

`HandoffState` holds twelve values. `COMPLETE`, `ABORTED`, and `FAILED` are terminal, and nothing
leaves a terminal state except a re-observation the integrator calls for one named handoff.

| State | What is happening |
|---|---|
| `PLANNED` | admitted to the plan, no hook called |
| `DIVIDING` | a node holding both the parent and the child is dividing or folding its own copy |
| `PREPARING` | the destination is being made ready to receive |
| `TRANSFERRING` | the bulk contents are being copied |
| `CATCHING_UP` | the residue accumulated during the copy is being closed |
| `CUTOVER` | the source is quiescing and the cutover record is being committed |
| `VERIFYING` | the destination copy is being checked against the source |
| `CLEANUP` | the source copy is being released |
| `COMPLETE` | terminal: ownership moved and the source was released |
| `ABORTING` | compensation is running after an abort |
| `ABORTED` | terminal: ownership did not move and no residue remains |
| `FAILED` | terminal: operator action is required |

A catch-up leaves a residue, and the policy's two thresholds read it: at or below
`catchUpResidualThreshold` the handoff reaches `CUTOVER`, and above `reTransferResidualThreshold` it
returns to `TRANSFERRING`.

Between the success of `quiesce` and the return of `commitCutover`, both nodes refuse writes for the
shard and the refusal is retryable. The coordinator computes a commit horizon from the instant it
took before calling `quiesce`, the lease that call granted, and `quiesceLeaseMarginMillis`, and it
does not call `commitCutover` where the reading at the call plus `commitDeadlineMillis` is above
that horizon. Mutual exclusion across a cutover therefore rests on the single-winner write and on an
assumption about the integrator's two clocks that the library states and cannot verify.

`cleanup` never runs before the copy is established, and `rollback` never runs after a cutover
record belonging to the handoff exists. A local step aborted in `DIVIDING` is undone by the inverse
hook, a division by `combine` and a fold by `divide`. Where the inverse fails and its attempts are
spent, the handoff reaches `FAILED` with the failure kind `undivided`, which names a copy on one
node matching neither the parent's extent nor the child's; each of the other four kinds names a
condition of a copy that moved between nodes.

### Rate control

A `MigrationPolicy` bounds one migration rather than a caller's routing, which is why it is supplied
to `plan` and not to the router. Every value is validated at construction: a step budget of zero
admits nothing, and a re-transfer threshold at or below the catch-up threshold describes a handoff
that never settles.

```java
MigrationPolicy policy = MigrationPolicy.defaults()
        .withMaxConcurrentHandoffs(2)
        .withInitialStepBudget(64);
```

The policy carries the concurrency bounds across the plan and per source and destination node, the
step budget and deadline, the attempt count and the backoff base and cap, the commit deadline and
the quiesce margin, the two residual thresholds, and the gauge. `retryBackoffMillis(attempt)`
answers the backoff for one attempt, doubling per attempt and stopping at the cap.

Backpressure comes from a `PressureGauge` and nowhere else. The library infers no rate from a wall
clock, probes no node, and derives no level from its own measurements. An absent gauge always
answers `NONE`.

```java
PressureGauge gauge = scope -> scope instanceof PressureScope.Cluster
        ? clusterPressure() : PressureLevel.NONE;
MigrationPolicy throttled = MigrationPolicy.defaults().withPressureGauge(gauge);
```

A step takes the highest of the cluster reading, the source reading, and the destination reading.
`SOFT` admits no handoff out of `PLANNED` and lets every other step continue. `HARD` admits none out
of `PLANNED` and withholds `transfer` and `catchUp` as well. A withheld step answers `Idle`.

A `Deferred` hook result is backpressure for its own handoff alone, and the gauge is backpressure
across the plan.

[`10-specification.md`](../../docs/design/10-specification.md#migration-rate-control) states the
rate rules.

### Recovery

A coordinator that restarted rebuilds the plan from the two snapshots and then reads the
integrator's durable state, which is the authority for what actually happened.

```java
RecoveryReport report = plan.recover(clock);
List<HandoffId> unresolved = report.unresolved();
int retryAfterMillis = report.retryAfterMillis();
```

`recover` calls `observe` for every non-terminal handoff and resumes each one in the state the
observation maps to. It answers a value rather than raising, because an unresolved handoff is an
outcome the integrator acts on. `ObserveResult.Unavailable` means the durable state could not be
read and `ObserveResult.Undetermined` means it was read and establishes nothing; the two are never
treated as one answer, and neither moves a handoff.

`plan.reobserve(id, clock)` reads the durable state for one terminal handoff, and it is the one call
that moves a handoff out of a terminal state. It answers `Resumed`, `Unresolved`, or `Refused`.

### Rebase

A snapshot installed above the plan's target epoch does not abort the plan. `onSnapshotInstalled`
marks the plan rebase pending against that snapshot, which records the snapshot and nothing else.
While the mark stands, no handoff leaves `PLANNED` and none reaches `CUTOVER`, every other
transition stays available, and work already begun finishes.

```java
plan.onSnapshotInstalled(newer);
RebaseReport report = plan.rebase(newer);
```

The report names the handoffs rebased, the handoffs aborted, and the handoffs unchanged. A handoff
at `CUTOVER` or beyond is unchanged: it keeps the target epoch it held at that transition and runs
to a terminal state under it. A rebase onto an epoch the plan already targets is refused with
`PlanRefusedException`.

An installed snapshot under a different topology identifier, or one not comparable with the plan's
source, supersedes the plan instead: every handoff short of `CUTOVER` aborts and every handoff at
`CUTOVER` or beyond runs to a terminal state.

### Abort

```java
plan.abort(id, "the operator stopped the migration");
plan.abortAll("shutting down");
```

An abort runs compensation through `rollback` where the handoff has begun. It is admitted while no
cutover record belonging to the handoff exists. At `CUTOVER` the coordinator observes the durable
state first, and an abort is refused with `InvalidArgumentException` where a record exists or where
the observation establishes nothing. Past the cutover an abort is refused outright, because
ownership has moved and compensating would destroy the only surviving copy. `abortAll` leaves those
handoffs untouched.

Attempts exhausted short of the cutover abort the handoff rather than failing it, and compensation
is `rollback`'s duty before the handoff settles.

## Guarantees and best-effort properties

- At most one node is the authoritative owner of a shard at any instant.
- No acknowledged write is discarded by a handoff, because `cleanup` never runs before `verify`
  succeeds, or, where the hooks declare `supportsVerify` false, before `commitCutover` has returned
  a record.
- A handoff that reaches `COMPLETE` has a verified destination copy, where the hooks declare
  `supportsVerify`.

The first two rest on two things the library does not supply and cannot check: the single-winner
cutover the integrator's hooks implement, and the clock assumption above. Where either fails, the
source can answer a write inside the window both nodes are meant to refuse in, and restoring the
guarantee is the integrator's.

Three further properties are best-effort rather than guarantees: that a caller's first attempt
reaches the authoritative owner; that a partitioned source stops answering promptly, the bound being
a quiesce lease the integrator enforces and the library only observes; and that the destination is
complete without `catchUp` where the integrator replicates writes during the transfer.

[`10-specification.md`](../../docs/design/10-specification.md#provided-guarantees) states them.

## Reference material

[`40-java-binding.md`](../../docs/design/40-java-binding.md) is the reference manual for every type
above: the package layout, the public type set, the error model, the thread-safety contracts, and
the build gates. [`10-specification.md`](../../docs/design/10-specification.md) is normative and
language-neutral. [`20-topology-format.md`](../../docs/design/20-topology-format.md) is the document
format and carries three worked examples.
[`05-glossary.md`](../../docs/design/05-glossary.md) defines the terms this guide uses.
