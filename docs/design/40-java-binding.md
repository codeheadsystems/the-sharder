# Java binding

Java is the first implementation of the sharder library. This binding fixes the artifact set, the
package layout, the public type set, the minimum JDK, the dependency policy, the thread-safety
contracts, the conformance harness, and the build gates. Every shape below renders a requirement of
[`10-specification.md`](10-specification.md), and the identifier given beside a shape names the
requirement that constrains it.

The reader is an implementer writing the Java port, an integrator reading the types before adopting
them, and a reviewer checking that the port has not quietly changed the contract.

## Artifacts and modules

### Artifact set

The Maven and Gradle group is `com.codeheadsystems`. Every artifact name begins with `sharder`. The
repository is `codeheadsystems/the-sharder`, and the `the-` prefix belongs to the repository name
alone; it appears in no artifact, package, or module name.

| Artifact | Module | Contents |
|---|---|---|
| `sharder-bom` | | a `java-platform` aligning the versions of the artifacts below |
| `sharder-api` | `com.codeheadsystems.sharder` | the core model types, the extension point interfaces, the error taxonomy, the configuration records |
| `sharder-core` | `com.codeheadsystems.sharder.core` | the hash, the document pipeline, the five strategies, routing, health, fencing, observability, the in-memory provider |
| `sharder-migrate` | `com.codeheadsystems.sharder.migrate` | the handoff coordinator, the movement hooks, the migration policy, split and merge lineage |
| `sharder-provider-file` | `com.codeheadsystems.sharder.provider.file` | the static file reference provider |
| `sharder-conformance` | `com.codeheadsystems.sharder.conformance` | the JUnit harness that drives the vectors |
| `sharder-conformance-vectors` | | the `conformance/` tree packaged as classpath resources |
| `sharder-bench` | | JMH benchmarks, not published |

What each split lets a consumer avoid is the reason the split exists.

| Split | The consumer avoids |
|---|---|
| `sharder-api` from `sharder-core` | A third-party topology provider, health view, or placement strategy compiles against the types alone. It gains no dependency on the routing engine, the JSON reader, or any core internal, and it does not recompile when a core internal changes. |
| `sharder-migrate` from `sharder-core` | The handoff state machine, the movement hook types, the pressure gauge, and the split lineage classifier. An integrator routing a tenant identifier to one of five clusters depends on `sharder-core` and never sees a handoff type on the classpath or in an IDE completion list. |
| `sharder-provider-file` from `sharder-core` | Filesystem access and a poll schedule. A deployment whose control plane pushes documents supplies its own provider or uses the in-memory provider in `sharder-core`, and reads no file. |
| `sharder-conformance` from everything | The JUnit dependency. The harness is the only artifact that carries a test framework, and it is consumed at test scope by the port and by a third-party strategy author who runs the suite against their own build. |
| `sharder-conformance-vectors` from `sharder-conformance` | A repository checkout. The harness resolves vectors from the classpath, so a downstream consumer runs the suite from published artifacts alone. |

`sharder-core` carries the in-memory reference provider because that provider reads nothing, opens
nothing, and schedules nothing; it is a sink an authority pushes into, and it is what a test and a
control-plane integration both use.

The ownership delta of `TOPO-211` lives in `sharder-core` rather than in `sharder-migrate`, because
a delta is computed and published at snapshot installation under `MOVE-101` by an integrator who may
never build a plan. Split advice and key skew detection under `SPLIT-021` to `SPLIT-051` and
`OBS-030` to `OBS-035` live in `sharder-core` for the same reason: both consume a `ShardReport` and
emit an event, and neither plans anything. The split lineage classification of `SPLIT-061` to
`SPLIT-211` lives in `sharder-migrate`, because it exists to decide whether a plan is admissible.

### Gradle project structure

The build is Gradle with the Kotlin DSL and a version catalogue at `gradle/libs.versions.toml`.

```
the-sharder/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── buildSrc/
│   └── src/main/kotlin/
│       ├── sharder.java-library.gradle.kts        convention: --release, -Werror, JPMS, tests
│       ├── sharder.published.gradle.kts           convention: POM, signing, reproducible jars
│       └── com/codeheadsystems/sharder/build/
│           ├── VerifyDocLinksTask.kt
│           ├── VerifyDocStyleTask.kt
│           ├── VerifyUnsignedTask.kt
│           └── Acronyms.kt
├── sharder-bom/
├── sharder-api/
├── sharder-core/
├── sharder-migrate/
├── sharder-provider-file/
├── sharder-conformance/
├── sharder-conformance-vectors/
├── sharder-bench/
├── conformance/                            the vector tree, packaged by the resources project
└── docs/
```

Project dependencies run in one direction only.

| Project | Depends on |
|---|---|
| `sharder-api` | nothing |
| `sharder-core` | `sharder-api` as `api` |
| `sharder-migrate` | `sharder-core` as `api` |
| `sharder-provider-file` | `sharder-api` as `api`, `sharder-core` at test scope |
| `sharder-conformance` | `sharder-core` and `sharder-migrate` as `api`, JUnit as `api` |
| `sharder-bench` | `sharder-core`, `sharder-migrate` |

`sharder-migrate` depends on `sharder-core` rather than on `sharder-api` alone, because `plan`
evaluates preference lists over two snapshots under `TOPO-211` and `MOVE-081`, which is core work.

### Module descriptors

Every published artifact carries a `module-info.java`. No package is split between two modules, so
each artifact owns its package roots outright and the modular and classpath builds agree.

`sharder-core` exports its public package and opens two internal packages to named modules only.

```java
module com.codeheadsystems.sharder.core {
    requires transitive com.codeheadsystems.sharder;
    exports com.codeheadsystems.sharder.core;
    exports com.codeheadsystems.sharder.core.internal.json
        to com.codeheadsystems.sharder.conformance;
    exports com.codeheadsystems.sharder.core.internal.hash
        to com.codeheadsystems.sharder.conformance;
}
```

The qualified exports are what let the conformance harness read a vector file and verify a framed
hash without a JSON dependency of its own and without the internals reaching a consumer.

## Package layout

### Public packages

A package maps to a section of the specification. The types that carry data are records; the types
that carry behaviour or that an integrator implements are interfaces.

| Package | Artifact | Specification section | Principal types |
|---|---|---|---|
| `com.codeheadsystems.sharder` | api | Core model, Replication and failover | `Router`, `RouteOptions`, `RoutingDecision`, `PreferenceEntry`, `AttemptSequence`, `AffinityRequest`, `NodeId`, `ShardId`, `RoutingKey`, `NodeSet`, `Digest`, `MonotonicClock`, `Role`, `Shortfall` |
| `com.codeheadsystems.sharder.topology` | api | Topology change and rebalancing | `TopologySnapshot`, `Node`, `AdministrativeState`, `FencingToken`, `ValidationError`, `OwnershipDelta`, `ShardChange`, `TopologyProvider`, `TopologySink`, `Subscription` |
| `com.codeheadsystems.sharder.placement` | api | Routing keys and placement | `PlacementStrategy`, `PreparedPlacement`, `CandidateCursor`, `StrategyKind` |
| `com.codeheadsystems.sharder.health` | api | Node health state machine | `HealthView`, `HealthState`, `HealthSignal`, `Outcome`, `HintObserver` |
| `com.codeheadsystems.sharder.fence` | api | Fencing | `Recipient`, `Verdict`, `Relation`, `Ownership`, `RecipientPolicy` |
| `com.codeheadsystems.sharder.error` | api | Error taxonomy | `ErrorCode`, `SharderException` and its sealed descendants, the cause enumerations |
| `com.codeheadsystems.sharder.observe` | api | Observability | `MetricsRegistry`, `Labels`, `MetricsView`, `EventSink`, `Event`, `Severity`, `ExplainRecord`, `Exclusion`, `ShardMetricsSource`, `ShardReport` |
| `com.codeheadsystems.sharder.config` | api | Configuration surface | `RouterConfig`, `ProviderSettings`, `RoutingSettings`, `HealthSettings`, `FencingSettings`, `ObservabilitySettings`, `ConfigurationView` |
| `com.codeheadsystems.sharder.core` | core | the whole of it | `Sharder`, `TopologyLoader`, `InMemoryTopologyProvider` |
| `com.codeheadsystems.sharder.migrate` | migrate | Shard ownership handoff, Migration rate control, Range splits and merges | `HandoffCoordinator`, `MigrationPlan`, `MigrationPolicy`, `MovementHooks`, `HandoffContext`, `HandoffState`, `HandoffId`, `HookResult`, `CutoverResult`, `StepOutcome`, `PressureGauge` |
| `com.codeheadsystems.sharder.provider.file` | provider-file | Topology provider contract | `FileTopologyProvider` |
| `com.codeheadsystems.sharder.conformance` | conformance | the conformance suite | `ConformanceSuite`, `VectorSource`, `VectorManifest`, `ConformanceLevel`, `ConformanceReport` |

### Internal packages

Everything under `com.codeheadsystems.sharder.core.internal` and
`com.codeheadsystems.sharder.migrate.internal` is unexported, unsupported, and free to change
between any two versions. A class outside those roots is public API and changes under the
compatibility rules of the release it belongs to.

| Internal package | Contents |
|---|---|
| `core.internal.hash` | `SipHash24`, `Frame`, `U64` |
| `core.internal.json` | `JsonReader`, `JsonValue`, `JcsWriter` |
| `core.internal.document` | `TopologyDocument`, `StructuralValidator`, `SemanticValidator` |
| `core.internal.placement` | `RingPlacement`, `RendezvousPlacement`, `SlotPlacement`, `RangePlacement`, `DirectoryPlacement`, `Matchers`, `KeyTransforms` |
| `core.internal.route` | `DefaultRouter`, `OverrideTable`, `PreferenceListBuilder`, `SpreadLadder`, `DefaultAttemptSequence`, `RetryBudget` |
| `core.internal.snapshot` | `SnapshotHolder`, `LoadPipeline`, `RetentionRing`, `ProviderDriver` |
| `core.internal.health` | `SlidingWindowHealthView`, `Buckets`, `OutlierEjection` |
| `core.internal.observe` | `MetricRecorder`, `EventEmitter`, `ExplainBuilder`, `SkewDetector` |
| `migrate.internal` | `DefaultCoordinator`, `HandoffMachine`, `RateAdmission`, `RangeLineage` |

## Integer widths

### Unsigned sixty-four bit values

The specification compares hashes, tokens, and scores as unsigned 64-bit integers. Java has no
unsigned long. Every such value is a `long` whose bit pattern is the value, and the discipline below
is what keeps the bit pattern from being read as a signed quantity. A signed comparison here
produces a ring order that is wrong only for tokens at or above 2^63, which is half of them, and the
result is a placement that is internally consistent and disagrees with every other port.

These quantities are unsigned 64-bit.

| Quantity | Source |
|---|---|
| `keyHash(rk)` and every framed hash output | `10-specification.md` notation, STAGE1 hash specification |
| a ring token value, derived or decoded from `tokens` | `RING-003`, `RING-010` |
| a rendezvous score, a slot score, a range score | `RV-003`, `SLOT-021`, `RANGE-031` |
| `bulkRemaining`, `residue`, `reTransferResidualThreshold` | `MOVE-111`, `CFG-050` |

The sanctioned operations are these, and no others.

| Operation | Java |
|---|---|
| compare | `Long.compareUnsigned(a, b)` |
| greater or equal, as `RING-020` needs | `Long.compareUnsigned(a, b) >= 0` |
| maximum, as `RV-003` needs | `Long.compareUnsigned(a, b) >= 0 ? a : b` |
| remainder, as `SLOT-001` needs | `Long.remainderUnsigned(hash, slotCount)` |
| quotient | `Long.divideUnsigned(a, b)` |
| render as sixteen hexadecimal digits | `HexFormat.of().toHexDigits(value)` |
| parse sixteen hexadecimal digits | `Long.parseUnsignedLong(text, 16)` |
| widen for reporting | `Long.toUnsignedString(value)` |

These forms are forbidden on an unsigned 64-bit value, and a build check refuses them in
`core.internal.hash` and `core.internal.placement`.

- The relational operators `<`, `<=`, `>`, and `>=`.
- `Long.compare`, `Math.max`, `Math.min`, and `Long.signum`.
- `Comparator.comparingLong` and `Comparator.naturalOrder`. This is the sharpest trap of the set,
  because the comparator a ring or a rendezvous ordering wants is the one an author reaches for
  first and it is signed.
- `Long.MAX_VALUE` as the greatest value and `Long.MIN_VALUE` as the least. The unsigned greatest is
  `-1L` and the unsigned least is `0L`.
- Any conversion to `double`, `float`, or `BigDecimal`.

`U64` in `core.internal.hash` is the only place the sanctioned forms are written. It is a final
class of static methods with a private constructor, and it carries the comparators the strategies
use.

```java
static int compare(long a, long b);
static long max(long a, long b);
static long mod(long value, long divisor);
static String toHex(long value);
static long parseHex(CharSequence text);
static final Comparator<RingEntry> RING_LESS;       // RING-010
static final Comparator<ScoredNode> RV_LESS;        // RV-010
```

A conformance vector pins a topology whose derived tokens straddle 2^63, so that a signed comparison
reorders the ring and fails the vector rather than passing every unit test the port wrote for
itself.

### Epoch and instant

`Epoch` is declared `u64` in `CORE-001` and bounded by 9007199254740991. `Instant` is declared `u64`
and counts milliseconds from a monotonic source. Both fit a signed `long` with room, so both are
compared with the relational operators and the build check exempts them by type: an epoch is carried
as a `long` named `epoch` on `FencingToken` and `TopologySnapshot`, and an instant is carried as a
`long` named `at`, `now`, `observed`, or `installedAt`.

`MonotonicClock.systemNanoTime()` records a base reading at construction and answers
`(System.nanoTime() - base) / 1_000_000L`, so the first reading is 0 and no reading is negative.
`System.nanoTime` has an arbitrary origin that is frequently negative, and a negative instant would
make the elapsed-interval arithmetic of `HEALTH-044`, `HEALTH-045`, and `MOVE-331` read as though a
deadline had already passed.

### Unsigned thirty-two bit values

Indices, counts, weights, slot identifiers, replication factors, percentages, and millisecond
durations are `int`. Every one of them is bounded below 2^31 by the specification or by the format,
so the ordinary signed operations are correct.

| Quantity | Bound |
|---|---|
| `weight` | 1000000, `PLACE-040` |
| `slotCount` | 1048576, `SLOT-001` |
| a virtual node count | 4096, `PLACE-050` |
| `factor`, `position`, `attemptLimit`, `unitsMoved` | bounded by the node count or the policy |
| every `Millis` setting | bounded by `CFG-003` validation at construction |

Two products escape 32 bits and are computed in `long`.

- `weight * perWeightUnit` under `PLACE-051` reaches 4096000000. The product is formed in `long`,
  compared against the cap in `long`, and narrowed only after `Math.min`.
- The retry backoff of `RATE-051`, `retryBackoffBaseMillis * 2^(attempt - 1)`, is computed as the
  cap where the shift exceeds 62 and as the shifted value otherwise.

Four threshold comparisons in the specification are products compared against products, and
`CORE-005` requires each to hold over the exact products: `HEALTH-034`, `FAIL-031`, `OBS-031`, and
`SPLIT-041`. The last two take operands `SPLIT-021` types as u64, so their products exceed a `long`
within the declared range. All four are evaluated through one helper that compares `a * b` against
`c * d` in 128 bits using `Math.multiplyHigh`, so a threshold never inverts under overflow.

```java
static int compareProducts(long a, long b, long c, long d);   // sign of (a*b - c*d), exact
```

### Floating point

No value that reaches a placement, validation, ordering, fencing, admission, budget, or threshold
decision is a `double` or a `float`. The permitted uses are the metric values of `OBS-002`, which
enter `MetricsRegistry.gauge` and `MetricsRegistry.histogram` and are read by nothing the library
computes with. No arithmetic in `sharder-api`, `sharder-core`, or `sharder-migrate` outside
`core.internal.observe` uses a floating-point type.

## Value types and absence

### Opaque identifiers

`NodeId`, `ShardId`, and `RoutingKey` are octet sequences in the specification and are value types
in the binding rather than `byte[]`. A `byte[]` has identity equality, no useful `hashCode`, and no
protection against a caller mutating it after the library has retained it, and every one of those is
a correctness hazard against `CORE-041` and `PLACE-013`.

```java
public final class NodeId implements Comparable<NodeId> {
    public static NodeId of(String text);            // UTF-8, no byte order mark, KEY-003
    public static NodeId ofBytes(byte[] octets);     // copies
    public int length();
    public byte[] toBytes();                         // copies
    public String asText();
    @Override public int compareTo(NodeId other);    // Arrays.compareUnsigned, PLACE-020
    @Override public boolean equals(Object other);
    @Override public int hashCode();                 // cached
}
```

`Arrays.compareUnsigned(byte[], byte[])` is exactly the comparison of `PLACE-020` and `PLACE-021`,
including the rule that a proper prefix compares less, so the binding writes no comparison loop of
its own. `ShardId` and `RoutingKey` have the same shape. `Digest` has the same shape over exactly
32 octets and adds `toHex()`.

A domain level name, a domain identifier, a tag key, a tag value, and `topologyId` are `String`.
`OVR-026` and `FENCE-071` need equality over their octets and never need an order over them, and
UTF-8 octet equality and `String.equals` agree for every well-formed string. A document carrying an
unpaired surrogate is rejected during structural validation, which is what keeps that agreement
total.

A key is not wrapped. `route` accepts a `byte[]` and a `String`, retains neither under `CORE-070`,
and copies the octets only where an `ExplainRecord` holds them.

### Node sets

`NodeSet` is a purpose-built final class, not a `java.util.Set`. `CORE-002` forbids the iteration
order of a node set from reaching a result, and a `Set` hands a caller `stream()`, `iterator()`, and
`parallelStream()` in hash order, which is an invitation to exactly that defect.

```java
public final class NodeSet implements Iterable<NodeId> {
    public static NodeSet of(Collection<NodeId> ids);
    public boolean contains(NodeId id);
    public int size();
    public boolean isEmpty();
    @Override public Iterator<NodeId> iterator();    // ascending node identity, always
}
```

Iteration is in ascending node identity order. The order is a defined total order rather than a hash
order, so an implementation that leans on it accidentally produces the same result on every JVM and
in every run, and the permuted-`nodes` vectors of `PROP-005` are what catch the lean.

### Absence

`Optional` marks absence on a public return type where absence is a documented answer the caller
handles. Null crosses no API boundary, in either direction, and a record component is never null.

| Specification shape | Requirement | Java |
|---|---|---|
| `shardOf` answering with no shard | `DIR-020` | `Optional<ShardId>` |
| `snapshot` answering with no snapshot | `CORE-032` | `Optional<TopologySnapshot>` |
| `matchedOverride` absent | `CORE-040` | `Optional<MatchedOverride>` |
| `installedAt` absent | `CORE-020` | `OptionalLong` |
| a token's diagnostic digest absent | `FENCE-011` | `Optional<Digest>` |
| `next` answering `exhausted` | `FAIL-023` | `Optional<NodeId>` |
| `attemptLimit` unset | `CORE-030` | `OptionalInt` |
| `report` answering with no report | `SPLIT-021` | `Optional<ShardReport>` |

Internal code uses a sentinel or a null field and neither escapes. The candidate cursor of the next
section carries no `Optional`, because it is consumed once per candidate on the routing path.

### Records and sealed hierarchies

A type that carries data and no behaviour is a record. Every record with a collection component
copies it in a compact constructor with `List.copyOf` or `Map.copyOf`, which is what makes
`CORE-041` hold for a `RoutingDecision` a caller holds across an installation.

A type whose specification shape is `one of { ... }` over dissimilar payloads is a sealed interface
whose permitted implementations are records. A type whose shape is `one of { ... }` over bare names
is an enum.

| Shape | Java |
|---|---|
| `HookResult`, `MOVE-111` | sealed interface, records `Success`, `Deferred`, `Retryable`, `Permanent` |
| `CutoverResult`, `MOVE-111` | sealed interface, six records |
| `VerifyResult`, `MOVE-111` | sealed interface, three records |
| `StepOutcome`, `MOVE-061` | sealed interface, five records |
| `PressureScope`, `RATE-061` | sealed interface, records `Cluster` and `Node` |
| `HealthState`, `Outcome`, `Relation`, `Ownership`, `HandoffState`, `Role` | enums |

`TransferResult`, `CatchUpResult`, and `QuiesceResult` are written `HookResult plus { ... }` in
`MOVE-111`. They compose rather than extend, because `HookResult` is sealed over records and a
record is final.

```java
public record TransferResult(HookResult result, int unitsMoved, long bulkRemaining) { }
```

Sealing is what makes a `switch` over these types exhaustive without a default branch, so a variant
added in a later version fails the compilation of every handler rather than falling through at
runtime.

```java
HandoffState next = switch (hooks.commitCutover(ctx)) {
    case CutoverResult.Committed c        -> record(c, HandoffState.VERIFYING);
    case CutoverResult.AlreadyCommitted c -> record(c, HandoffState.VERIFYING);
    case CutoverResult.Lost ignored       -> HandoffState.ABORTING;         // MOVE-351
    case CutoverResult.Undetermined u     -> fail(FailureKind.UNDETERMINED); // MOVE-231
    case CutoverResult.Retryable r        -> retry(r);
    case CutoverResult.Permanent p        -> fail(FailureKind.UNDETERMINED);
};
```

## Public interface set

### Placement extension point

```java
public interface PlacementStrategy {
    String name();                                                          // CORE-010
    List<ValidationError> validate(StrategyConfig config, List<Node> nodes,
                                   List<String> domainLevels);
    PreparedPlacement prepare(TopologySnapshot snapshot);                   // CORE-011
    boolean supportsOrchestratedMigration();                                // MOVE-261
}

public interface PreparedPlacement {
    Optional<ShardId> shardOf(RoutingKey routingKey);                       // CORE-010, PLACE-030
    CandidateCursor candidates(RoutingKey routingKey, NodeSet eligible);    // CORE-012
    Iterator<ShardId> shards();                                             // PLACE-031
    CandidateCursor candidatesForShard(ShardId shard, NodeSet eligible);
}
```

A strategy is registered explicitly on the configuration. The binding discovers no strategy through
`ServiceLoader`, so what is on a classpath never changes how a key routes.

### Candidate cursor

The **candidate cursor** is the lazy ordered traversal that `PLACE-015` requires, so that a
preference list builder consumes only the prefix it needs. At factor 3 over a thousand-node ring the
builder reads a handful of entries out of a thousand.

```java
public interface CandidateCursor {
    boolean advance();      // true where a further candidate exists
    NodeId node();          // the current candidate, valid after advance() returned true
}
```

It is not an `Iterator`, because `hasNext` forces the next element to be computed and buffered
before a caller has decided it wants one, and a ring walk's next element is a scan over ring entries
whose owners have already been emitted. It is not a `Stream`, because `Stream` offers `parallel`,
which `CORE-012` forbids outright, and `collect`, which discards the laziness the requirement
exists to provide.

A cursor belongs to one unit of execution under `CORE-012` and `CORE-057`. It takes no lock. An
assertion checks the owning thread when assertions are enabled, so the conformance and test builds
catch a shared cursor and a production build pays nothing.

`shards()` returns an `Iterator<ShardId>`. It is off the routing path, it is enumerated by delta
computation and by plan construction, and it carries no restriction beyond an iterator's own.

### Topology snapshot

```java
public interface TopologySnapshot {
    String topologyId();                        // CORE-020
    long epoch();
    Digest digest();
    FencingToken token();
    List<Node> nodes();                         // document order, includes joining and leaving
    NodeSet placementSet();                     // PLACE-001
    PreparedPlacement placement();
    List<String> domainLevels();
    int factor();                               // replication.factor, REPL-002
    boolean seedIsDefault();                    // SEC-011
    OptionalLong installedAt();                 // absent for a snapshot from TOPO-191
}

public interface Node {
    NodeId id();
    AdministrativeState state();                // ACTIVE, JOINING, DRAINING, LEAVING
    int weight();
    Map<String, String> domains();
    Map<String, String> tags();
    Optional<String> address();
    int tokenCount();                           // explicit ring assignment only
    long token(int index);                      // unsigned, RING-003
}
```

`Node` exposes `tokenCount` and `token` rather than a list, so that preparing a ring of four
thousand tokens per node boxes nothing and copies nothing.

The snapshot exposes no accessor for the hash seed, under `SEC-010`.

### Router

```java
public interface Router extends AutoCloseable {
    RoutingDecision route(byte[] key);                                      // CORE-030
    RoutingDecision route(byte[] key, RouteOptions options);
    RoutingDecision route(String key, RouteOptions options);                // UTF-8, KEY-003
    RoutingDecision routeForRead(byte[] key, AffinityRequest affinity, RouteOptions options);
    AttemptSequence attempts(RoutingDecision decision);
    ExplainRecord explain(byte[] key);                                      // OBS-040
    Optional<TopologySnapshot> snapshot();                                  // CORE-032
    HealthView health();
    void refresh();                                                         // CORE-033
    Recipient recipient(NodeId selfId);                                     // FENCE-061
    TopologyLoader loader();                                                // TOPO-191
    ConfigurationView configuration();                                      // CFG-007
    MetricsView metrics();                                                  // OBS-004
    @Override void close();                                                 // CORE-034
}

public record RouteOptions(OptionalInt attemptLimit, boolean explain) {
    public static final RouteOptions DEFAULTS = new RouteOptions(OptionalInt.empty(), false);
    public RouteOptions withAttemptLimit(int limit);
    public RouteOptions withExplain(boolean explain);
}
```

`RouteOptions.explain` is how a caller asks for the explain record that `CORE-040` carries and that
`OBS-046` forbids computing unasked. `CORE-030` declares it, defaulting to false.

`Router` is constructed once from `Sharder`, held for the life of the process under `CORE-071`, and
closed at shutdown.

```java
public final class Sharder {
    public static Router router(RouterConfig config);
    public static TopologyLoader loader(RouterConfig config);
    private Sharder() { }
}

public interface TopologyLoader {
    TopologySnapshot validate(byte[] document);          // TOPO-191, stages 1 to 4 and 6
}
```

`TopologyLoader` is the home `TOPO-191` needs and does not name. Its result is admissible as a plan
target under `TOPO-201` and is never installed.

`OwnershipDelta.between(TopologySnapshot from, TopologySnapshot to)` is a static factory, because
`TOPO-221` makes the delta a pure function of two snapshots and nothing of the router reaches it.

### Routing decision

```java
public record PreferenceEntry(NodeId node, int position, Role role,
                              HealthState health, boolean attemptable) { }

public record RoutingDecision(
        RoutingKey routingKey,
        Optional<ShardId> shard,
        FencingToken token,
        int factor,
        int replicaCount,
        int attemptLimit,
        List<PreferenceEntry> entries,
        List<PreferenceEntry> ordered,
        List<String> relaxedLevels,
        Shortfall shortfall,
        boolean filterFailedOpen,
        Optional<MatchedOverride> matchedOverride,
        Optional<ExplainRecord> explain) {

    public PreferenceEntry primary();        // CORE-042, the head of entries
    public boolean isEmpty();
}

public record MatchedOverride(int index, OverrideMode mode) { }   // PIN, CONSTRAIN, BOTH
```

`primary()` renders the `primary` member `CORE-040` declares and `CORE-042` constrains, as an
accessor over the head of `entries` rather than as a second stored field. `attemptLimit` is the
member `CORE-045` requires: the value `RouteOptions` supplied or that `FAIL-022` defaulted, carried
on the decision because `Router.attempts(decision)` has no other path to it and `CORE-041` forbids
the router holding per-decision state.

The record's compact constructor copies `entries`, `ordered`, and `relaxedLevels`. The decision
carries no key, no address, no tags, no override note, and no metadata, under `CORE-043`.

### Attempt sequence

```java
public interface AttemptSequence {
    Optional<NodeId> next();                                        // FAIL-023, empty is exhausted
    void recordOutcome(NodeId node, Outcome outcome, long at);
    void recordOutcome(NodeId node, Outcome outcome);               // reads the supplied clock
    int remaining();
}
```

An attempt sequence belongs to one request and one unit of execution under `CORE-057`. It is not
synchronized, and the thread-owner assertion of the candidate cursor applies to it unchanged.
`next` never waits, sleeps, or backs off, under `FAIL-027`; spacing between attempts belongs to the
caller.

### Health surface

```java
public interface HealthView {
    HealthState stateOf(NodeId node);                               // HEALTH-010
    void report(HealthSignal signal);
    void advance(long now);
    default void onSnapshotInstalled(TopologySnapshot snapshot) { } // HEALTH-030, HEALTH-034
    default boolean admitProbe(NodeId node) { return true; }        // HEALTH-051
}

public record HealthSignal(NodeId node, Outcome outcome, long observed) { }
```

`onSnapshotInstalled` and `admitProbe` are declared by `HEALTH-010` and given defaults here, so an
existing implementation stays valid. `onSnapshotInstalled` is how a health view learns the placement
set that outlier ejection compares against under `HEALTH-030` and that the ejection ceiling of
`HEALTH-034` is a percentage of, and `HEALTH-016` makes ignoring it mean running no outlier
ejection. `admitProbe` separates the probation probe counter of `HEALTH-051`, which `route`
increments under `HEALTH-017`, from `stateOf`, which `explain` reads and which `OBS-045` forbids
from changing state.

The built-in view is `SlidingWindowHealthView`, implementing `HEALTH-020` to `HEALTH-055` over
`bucketCount` buckets of `int` success and failure counts.

### Topology provider

```java
public interface TopologyProvider extends AutoCloseable {
    Capabilities capabilities();                    // at least one of the two is true
    byte[] load();                                  // present where pull; throws on failure
    Subscription watch(TopologySink sink);          // present where push
    @Override void close();

    record Capabilities(boolean pull, boolean push) { }
}

public interface TopologySink {
    void onDocument(byte[] document);
    void onError(Throwable error);
}

public interface Subscription extends AutoCloseable {
    void cancel();
    @Override default void close() { cancel(); }
}
```

A provider delivers octets. Validation, canonicalisation, digesting, monotonicity, and preparation
are the library's, and a provider never constructs a snapshot. A provider that throws any
`Throwable` has it caught, counted, reported as `providerError` with the original attached as the
Java cause, and followed by the backoff of `CFG-010`, under `ERR-063`.

### Fencing recipient

```java
public interface Recipient {
    Verdict check(FencingToken token, byte[] routingKey);           // FENCE-061
    void admit(FencingToken token, byte[] routingKey);       // applies FENCE-111 to FENCE-151
}

public record Verdict(Relation relation, Ownership ownership, boolean ownershipStable,
                      Optional<NodeId> currentOwner, FencingToken localToken) { }
```

`Recipient` is obtained from `Router.recipient(selfId)`, because the check reads the snapshot in
force and the retained snapshots of `TOPO-161`. `check` answers a verdict and raises nothing.
`admit` applies the configured `recipientPolicy` and raises the recipient condition where the policy
refuses, which is what turns a verdict into one of `notOwner`, `epochMismatch`, or
`identityMismatch`.

### Observability surface

```java
public interface MetricsRegistry {
    void counter(String name, Labels labels, long delta);
    void gauge(String name, Labels labels, double value);
    void histogram(String name, Labels labels, double value);
}

public interface EventSink { void accept(Event event); }

public record Event(String name, long at, String topologyId, long epoch,
                    Severity severity, Map<String, String> payload) { }
```

`Labels` is a small fixed-arity value type rather than a `Map`, because a metric is recorded on the
routing path and a map allocation per decision is not.

An event reaches the sink synchronously, on the unit of execution that produced it, under
`OBS-023`. The emitter wraps every sink call in a `catch (Throwable)`, counts the failure under
`sharder.events.sink_failures`, and does not rethrow, so a defective sink cannot fail a routing
call. This is one of two places the binding catches `Throwable`; the other is the provider boundary
of `ERR-063`.

Where no registry is supplied the values are held internally and read whole through
`Router.metrics()`, under `OBS-004`. Where no sink is supplied events are counted by name and their
payloads are not buffered, under `OBS-025`.

`ExplainRecord.toJson()` serialises the record with the member names of `OBS-041`, octet-valued
fields in lowercase hexadecimal, through the canonical writer in `core.internal.json`. That is how
`OBS-047` is met without a JSON dependency.

### Configuration surface

`RouterConfig` is built by a builder and is immutable once built. Every setting is validated at
build time, and a value outside its range refuses construction with `invalidArgument` under
`CFG-003` and `ERR-025`. No setting is clamped and no unrecognised setting is ignored.

```java
RouterConfig config = RouterConfig.builder()
        .provider(new FileTopologyProvider(Path.of("/etc/sharder/objects.topology.json")))
        .clock(MonotonicClock.systemNanoTime())
        .expectedTopologyId("objects-prod")
        .executor(scheduler)                       // CFG-012
        .eventSink(events)
        .build();
```

The settings are grouped as nested records reachable from `RouterConfig`: `ProviderSettings`,
`RoutingSettings`, `HealthSettings`, `FencingSettings`, and `ObservabilitySettings`.
`MigrationPolicy` is separate and lives in `sharder-migrate`, because it is supplied to `plan`
rather than to the router.

Every duration is an `int` or `long` count of milliseconds carrying the `Millis` suffix of
`CFG-006`, and is what `ConfigurationView` reports. The builder also accepts a `java.time.Duration`
on each such setter for readability, converts it to milliseconds, and refuses a negative value or
one with sub-millisecond precision. No setting is a floating-point value, under `CFG-005`.

`ConfigurationView` answers the values in force, including defaults the integrator did not set, as
an ordered map of setting name to rendered value, under `CFG-007`.

### Migration surface

```java
public interface HandoffCoordinator {
    MigrationPlan plan(TopologySnapshot from, TopologySnapshot to,
                       MovementHooks hooks, MigrationPolicy policy);        // MOVE-061
}

public interface MigrationPlan {
    OwnershipDelta delta();
    Iterator<HandoffId> handoffs();
    HandoffState state(HandoffId id);
    StepOutcome step(MonotonicClock clock);                                 // MOVE-071
    void recover(MonotonicClock clock);                                     // MOVE-211
    void abort(HandoffId id, String reason);
    void abortAll(String reason);
    void onSnapshotInstalled(TopologySnapshot snapshot);                    // MOVE-091
    Map<HandoffState, Integer> summary();
}
```

`MovementHooks` is declared in `sharder-migrate` rather than in `sharder-api`, because only a
consumer of `sharder-migrate` implements it. It carries `splitLocal` and `mergeLocal` from
`SPLIT-141` on the same interface.

`step` takes the clock per call, as `MOVE-061` writes it, although the router already holds a
monotonic source from `CORE-004`. `MOVE-062` makes the supplied clock the source the plan reads for
the duration of the call.

## Error model

### Error code enumeration

```java
public enum ErrorCode {
    NO_CANDIDATE(101, "noCandidate", false),
    EXHAUSTED(102, "exhausted", true),
    UNREADY(103, "unready", true),
    STALE_SNAPSHOT(104, "staleSnapshot", true),
    INVALID_ARGUMENT(105, "invalidArgument", false),
    INVALID_TOPOLOGY(201, "invalidTopology", false),
    TOPOLOGY_CONFLICT(202, "topologyConflict", false),
    STALE_DOCUMENT(203, "staleDocument", false),
    PROVIDER_ERROR(204, "providerError", true),
    NOT_OWNER(301, "notOwner", true),
    EPOCH_MISMATCH(302, "epochMismatch", true),
    IDENTITY_MISMATCH(303, "identityMismatch", false),
    REDIRECT_EXHAUSTED(304, "redirectExhausted", false),
    PLAN_REFUSED(401, "planRefused", false),
    QUIESCED(402, "quiesced", true),
    HANDOFF_FAILED(403, "handoffFailed", false);

    public int code();
    public String errorName();          // the spelling of ERR-010, not the enum constant
    public boolean retryable();
    public static ErrorCode ofCode(int code);
}
```

The enum is the value a metric label, a log line, a conformance vector, and a serialised error all
join on, under `ERR-002`. `errorName` answers the camel-case spelling of `ERR-010` and not the
constant's own name, so a rename of the constant cannot change the wire spelling.

### Exception hierarchy

The binding raises exceptions. `ERR-060` requires one idiom consistently, and a binding that
returned a result type for some conditions and raised for others would violate it. Exceptions are
unchecked, so that `route` composes inside a lambda and a stream without a wrapper at every call
site.

```java
public sealed abstract class SharderException extends RuntimeException
        permits RoutingException, TopologyException, RecipientException, MigrationException {
    public ErrorCode errorCode();
    public int code();                                  // ERR-061
    public String errorName();                          // ERR-061
    public boolean retryable();                         // ERR-006
    public Optional<String> cause();                    // ERR-004, the closed sub-reason
    public Optional<FencingToken> token();
    public Optional<ShardId> shard();
}
```

Four sealed intermediate classes group the conditions by their numeric block, and sixteen final
leaves carry the conditions themselves. `ERR-062` permits the grouping and forbids a leaf of the
binding's own, so the leaf set is closed at sixteen.

| Group | Leaves |
|---|---|
| `RoutingException` | `NoCandidateException`, `ExhaustedException`, `UnreadyException`, `StaleSnapshotException`, `InvalidArgumentException` |
| `TopologyException` | `InvalidTopologyException`, `TopologyConflictException`, `StaleDocumentException`, `ProviderException` |
| `RecipientException` | `NotOwnerException`, `EpochMismatchException`, `IdentityMismatchException`, `RedirectExhaustedException` |
| `MigrationException` | `PlanRefusedException`, `QuiescedException`, `HandoffFailedException` |

Sealing the base and the groups makes a `switch` over a caught `SharderException` exhaustive without
a default, so a caller writes a total handler and the compiler proves it total.

`ERR-004` types `cause` as a closed sub-reason and carries the node identity of `ERR-040` in a
`currentOwner` member of its own. The binding keeps `cause()` at the base for serialisation and
gives each leaf the typed accessor its own requirement implies.

```java
public final class NoCandidateException extends RoutingException {
    public NoCandidateCause reason();               // ERR-021, a closed enum
}
public final class ExhaustedException extends RoutingException {
    public ExhaustedCause reason();                 // ERR-022
    public List<PreferenceEntry> preferenceList();
    public List<NodeId> attempted();
    public List<Outcome> outcomes();
}
public final class NotOwnerException extends RecipientException {
    public NodeId currentOwner();                   // ERR-040
}
public final class InvalidTopologyException extends TopologyException {
    public List<ValidationError> errors();          // ERR-030, every error, not the first
}
public final class HandoffFailedException extends MigrationException {
    public FailureKind kind();                      // ERR-052, MOVE-011
}
```

`SharderException.cause()` and `Throwable.getCause()` are different things on the same object. The
first is the closed sub-reason of `ERR-004`; the second is the original failure of an extension
point, attached where `ERR-063` requires the original to survive.

### Conditions that never raise

A document rejected by the load pipeline is ordinary operation, not a failure of a call the
integrator made. A lower epoch arriving from a lagging provider replica calls for no response at the
caller under `ERR-011`. The library therefore does not raise `staleDocument`, `topologyConflict`,
`invalidTopology`, or `providerError` out of `TopologySink.onDocument` or out of a poll the executor
ran. It records the condition, increments `sharder.topology.documents`, and emits
`sharder.topology.rejected`.

The same conditions are raised out of `refresh()` and out of `TopologyLoader.validate()`, because
both are calls an integrator made and both have a caller waiting for an answer.

A replication shortfall raises nothing, under `ERR-009` and `REPL-025`. It is reported on the
decision through `shortfall()` and `replicaCount()`, and through the event of `OBS-020`.

`Recipient.check` raises nothing. `Recipient.admit` raises.

## JDK baseline

The binding compiles and runs on Java 21. Artifacts are compiled with `--release 21`. The build
itself runs on the current long-term-support release and cross-compiles.

The features the design depends on, and the release that finalised each.

| Feature | Release | Where the design uses it |
|---|---|---|
| Records | 16 | every data-carrying type in the core model |
| Sealed classes and interfaces | 17 | `HookResult`, `CutoverResult`, `StepOutcome`, `PressureScope`, the exception hierarchy |
| `HexFormat` | 17 | sixteen-digit token rendering, digest rendering, matcher `base16` decoding |
| Pattern matching for `switch` | 21 | exhaustive handling of every sealed result type with no default branch |
| Record patterns | 21 | destructuring a `CutoverResult` and a `StepOutcome` in one step |
| `Arrays.compareUnsigned` | 9 | `PLACE-020` node identity comparison and `RANGE-001` bound comparison |
| `Math.multiplyHigh` | 9 | the exact product comparison of `HEALTH-034`, `FAIL-031`, `OBS-031`, `SPLIT-041` |
| `Long.compareUnsigned`, `Long.remainderUnsigned` | 8 | every unsigned 64-bit operation |

Java 21 is a long-term-support release with a support window that outlasts several versions of this
library, and it is the baseline a library meant to be embedded widely can take without excluding a
deployment that has not moved. The trade against a floor of 17 and a floor of 25 is in
[`adr/0031-jdk-baseline.md`](adr/0031-jdk-baseline.md).

The library starts no unit of execution, holds no lock across a call into an extension point under
`CORE-063`, and blocks a routing call on nothing under `CORE-064`. A caller running on virtual
threads therefore pins no carrier inside the library.

## Dependency policy

### Runtime dependencies

`sharder-api`, `sharder-core`, and `sharder-migrate` require `java.base` and nothing else. Their
module descriptors name no other module, their POMs declare no compile or runtime dependency, and a
build check fails on a POM that gains one.

`sharder-provider-file` requires `java.base` and nothing else.

The library logs nothing and depends on no logging facade. What a logging framework would carry
travels through `EventSink` under `OBS-023` and through `MetricsRegistry` under `OBS-004`, both of
which are interfaces the integrator implements against whatever they already run. An adapter to a
metrics library is a third-party artifact written against `sharder-api`.

### JSON reading

The topology format is JSON and the library parses it without a JSON library. `core.internal.json`
carries a reader over the profile the format actually uses.

The reader accepts the JSON grammar of RFC 8259 and rejects, as a structural validation failure
rather than as a parse failure, everything the format forbids: a number that is not an integer, an
integer outside 0 to 9007199254740991, a duplicate member name within one object, a byte order mark,
and a string containing an unpaired surrogate. Rejecting a duplicate member name matters because two
parsers resolve a duplicate differently and the two resulting digests differ. Rejecting an unpaired
surrogate matters because it has no UTF-8 encoding, so a canonical form over it is not well defined.

The reader produces a `JsonValue` tree that preserves member insertion order and exact integer
values. It reads a document whose size the provider already bounded, in one pass, with no
reflection, no annotation processing, and no generated code.

### Canonical form and digest

`JcsWriter` implements RFC 8785 for the subset the format reaches. The scheme's hardest rule is its
number serialisation, which reproduces the ECMAScript `Number.prototype.toString` output for an
arbitrary double. The format never reaches that rule: `ADR 0008` restricts every JSON number to an
integer in 0 to 9007199254740991, and structural validation rejects a fractional or out-of-range
number at stage 2 of `TOPO-001`, which precedes the canonicalisation at stage 4. What remains of
RFC 8785 is a recursive sort of object members by the UTF-16 code unit sequence of their names, the
scheme's string escaping rules, plain decimal integers, and UTF-8 output. That is a few hundred
lines and it is exercised by the canonicalisation vectors of the conformance suite against every
other port.

`MessageDigest.getInstance("SHA-256")` computes the topology digest. It is in `java.base` and is a
dependency of nothing.

### Schema and semantic validation

The binding runs no general-purpose JSON Schema validator. Stage 2 of `TOPO-001` is a hand-written
structural validator in `core.internal.document` that enforces exactly what
[`topology-v1.schema.json`](topology-v1.schema.json) declares, including the unknown-member refusal
of `ADR 0008` at every level, and stage 3 is a semantic validator that enforces the rules of
[`20-topology-format.md`](20-topology-format.md). Both accumulate errors and neither stops at the
first, under `ERR-030`.

The published schema stays the cross-language contract. A conformance suite in the schema family
asserts that the structural validator accepts exactly the documents the schema accepts and rejects
exactly the ones it rejects, over an accept corpus and a reject corpus the vectors carry. That is
what keeps a hand-written validator and a published schema from drifting apart.

### Hash

`SipHash24` in `core.internal.hash` is `HASH-001`: the Aumasson and Bernstein construction with two
compression rounds, four finalisation rounds, and 64-bit output, against the 128-bit key `HASH-010`
takes from `hash.seed`. `Frame` builds the framed input of `HASH-021`, `u32be(len(f)) || f`
concatenated over the fields, with the domain tag of `HASH-023` as the first field. `HASH-003`
requires both to be verified against the reference vectors of the original paper before any
conformance vector runs, and `SEC-014` forbids a comparison that short-circuits on the seed's
octets.

### Test and build dependencies

Test and build dependencies reach no consumer, and the policy above does not bind them.

| Dependency | Scope | Use |
|---|---|---|
| JUnit 5 | `sharder-conformance` api, every project's test | the harness and the unit tests |
| AssertJ | test | assertions |
| ASM | `buildSrc` | the bytecode scan of the unsigned comparison check |
| JMH | `sharder-bench` | benchmarks |
| JaCoCo | build | coverage report and gate |

## Thread safety

### Immutability and publication

`CORE-050` and `CORE-051` require that construction of a snapshot, including every product of its
placement preparation, is ordered before the update of the reference a routing call reads, and that
a reader observing the reference observes the whole snapshot.

Every field of a snapshot, of a prepared placement, and of every index either of them derives is
`final`, and every array either holds is copied at construction and never handed out. Java's final
field semantics then guarantee that a reader which sees the object at all sees those fields
initialised, whatever path the reference travelled.

The reference itself is a `volatile` field of the router, written once per installation and read
once per routing call. The volatile write of a reference to a fully constructed object, followed by
the volatile read, establishes the ordering `CORE-050` asks for over everything written before it.
A `VarHandle` with `setRelease` and `getAcquire` is an equivalent expression of the same ordering.
An unordered write to a plain field is not, and a port that publishes that way is non-conforming
whether or not a test catches it.

### Snapshot installation

Installation is one assignment to the volatile field, under `TOPO-111` and `CORE-054`. No snapshot
is ever mutated, the load pipeline never writes into the snapshot in force, and no lock is taken on
the routing path, under `CORE-052`.

The load pipeline is serialised by a `ReentrantLock` held for the duration of stages 1 to 7 of
`TOPO-001`, under `TOPO-041`. That lock excludes a second document and excludes no routing call, so
`CORE-065` holds. The lock is released before any event reaches the sink, under `CORE-063`.

A routing call reads the field into a local at entry and computes its whole result from that local,
under `TOPO-121` and `CORE-053`. A snapshot stays reachable while any call holds that local, so
`TOPO-131` and `CORE-055` need no reference counting and no explicit release: the garbage collector
is the retention mechanism, and the bounded retention of `TOPO-161` is a separate array of strong
references the installation path maintains.

### Concurrent use

| Value | Concurrent use | Mechanism |
|---|---|---|
| `Router` | any number of threads | no mutable state outside the volatile reference, the health view, the retry budget, and the metric counters |
| `TopologySnapshot`, `PreparedPlacement`, `Node` | any number | final fields, no escaping array |
| `RoutingDecision`, `ExplainRecord`, `FencingToken`, `Verdict` | any number | records over copied collections |
| `HealthView` | any number | `stateOf` reads a volatile per-node state record; `report` and `advance` take one lock |
| `MigrationPlan` | any number | `step` claims one handoff by compare-and-set |
| `AttemptSequence` | one thread | thread-confined, asserted under `-ea` |
| `CandidateCursor` | one thread | thread-confined, asserted under `-ea` |

The built-in health view holds one lock across the whole node map rather than a lock per node,
because `HEALTH-015` requires timer evaluation in node identity order and `HEALTH-034` is a
cross-node invariant over the count of ejected nodes. `stateOf` does not take that lock; it reads a
volatile reference to an immutable per-node state record, so a routing call never blocks on the
health view even though `CORE-064` would permit it to. `admitProbe` increments the probation probe
counter of `HEALTH-051` with an atomic increment, taking no lock.

`MigrationPlan.step` claims a handoff with a compare-and-set on a per-handoff claim flag, calls at
most one hook with no monitor held, and releases the claim. That satisfies `MOVE-071` and
`CORE-063` together, and it is what lets an integrator drive a plan from a pool of threads.

The retry budget of `FAIL-030` is one per router across every key under `FAIL-033`. Its sliding
window is a small array of `LongAdder` counters, so accounting a first attempt costs no contended
write.

### Driving the passive surfaces

The library creates no thread, timer, executor, or task, under `CORE-060`.

Where `RouterConfig.executor` is set, it is a `ScheduledExecutorService` the integrator supplied and
sized. The router registers the poll of `CFG-010` and the reconcile of `CFG-011` on it at
construction and cancels both in `close()`. Scheduling on a supplied executor is what `CORE-060`
permits; creating one is what it forbids.

Where `executor` is unset, the library polls nothing and advances no timer of its own, under
`CORE-062`. Three calls are then the only path by which anything happens.

| Call | Drives |
|---|---|
| `Router.refresh()` | one pull from the provider and one pass of the load pipeline |
| `HealthView.advance(now)` | the ejection, probation, and window timers of `HEALTH-044` to `HEALTH-047` |
| `MigrationPlan.step(clock)` | one handoff by one hook, under `MOVE-071` |

A push provider needs no executor. It delivers on its own thread into `TopologySink.onDocument`, and
the load pipeline runs on that thread.

`MigrationPlan.step` returns `StepOutcome.Idle` where the pressure level or the concurrency bounds
leave nothing admissible, and never spins, waits, or sleeps, under `RATE-111`. An integrator drives
it from a loop whose pacing is the integrator's.

### Caller obligations

- Construct one `Router` and hold it for the life of the process, under `CORE-071`.
- Supply a `MonotonicClock`. `java.time.Clock` is a wall clock and is not accepted;
  `System.currentTimeMillis` is not read anywhere in the library, under `CORE-004`.
- Do not modify a key buffer while a call that reads it has not returned, under `CORE-070`.
- Do not share an `AttemptSequence` or a `CandidateCursor` across threads, under `CORE-057`.
- Supply an executor, or call `refresh`, `advance`, and `step`, under `CORE-062`.
- Make every supplied extension point safe to call from any thread on which the library is called,
  under `CORE-072`.
- Call `close()` at shutdown.

## Conformance harness

### Vector source and manifest

The harness reads one entry point, the **vector manifest** at `conformance/manifest.json`, and
discovers every suite, level, vector file, and vector kind from it. No Java source names a vector
file, and a vector added to the tree and listed in the manifest runs without a change to the
harness.

```java
public interface VectorSource {
    static VectorSource ofClasspath();                  // sharder-conformance-vectors
    static VectorSource ofDirectory(Path root);         // a working tree
    VectorManifest manifest();
    byte[] read(String path);                           // a path the manifest named
}
```

`VectorSource.ofClasspath()` is the default. The system property `sharder.conformance.dir`
substitutes a directory, so a developer runs the suite against an edited vector tree without
rebuilding the resources artifact. `sharder-conformance-vectors` is a resources-only project whose
`processResources` copies `conformance/` from the repository root verbatim, so the packaged tree and
the repository tree are the same bytes.

### Test generation

`ConformanceSuite` is a JUnit 5 `@TestFactory` that returns a tree of `DynamicContainer` and
`DynamicTest`, one container per suite and per conformance level and one test per vector. A test's
display name is the vector identifier followed by the requirement identifiers the vector asserts, so
a failure report names `PLACE-065` rather than a file and a line.

```java
public final class ConformanceSuite {
    @TestFactory
    Stream<DynamicNode> conformance();
}
```

A dynamic test factory is what the manifest coupling needs, because the suite shape is data. A
`@ParameterizedTest` would fix the suite set at compile time.

A manifest entry naming a suite kind, a vector kind, or a conformance level the harness does not
recognise produces one failing test that names the unrecognised value. It is never skipped, because
a silently skipped vector family is a port that claims conformance it does not have.

A vector for a level the binding declares out of scope is recorded as a declared exclusion in the
conformance report, with the level and the declaration, and is not reported as a pass.

### Suite kinds

| Suite kind | What the harness does |
|---|---|
| hash | frames the fields the vector gives and compares the 64-bit output, through the qualified export of `core.internal.hash` |
| canonicalisation | canonicalises the document and compares the byte sequence and the digest |
| validation | runs `TopologyLoader.validate` and compares the accept or reject outcome and, on reject, the `ValidationError` set |
| routing | installs the document, routes each key, and compares the routing key, the shard, the candidate ordering, the preference list, the roles, and the fencing token |
| property | evaluates the bounds of `PROP-*` over a sample the vector specifies |
| simulation | interprets a scenario as a sequence of steps against a router and a settable clock |

A routing vector is compared by the rule of `PROP-002`: the ordering is serialised as a JSON array
of strings and the two serialisations are compared byte for byte.

### Property and simulation suites

The bounds of `PROP-015`, `PROP-020`, `PROP-021`, `PROP-022`, and `PROP-023` are integer
inequalities with stated sample sizes. The harness evaluates each one exactly as the specification
writes it, in `BigInteger` so that no product overflows, and applies no tolerance of its own.

Sample keys come from a deterministic generator the vector names and seeds, not from
`java.util.Random`, so a failure in the Java port reproduces in the Go port on the same keys. The
specification states the distribution and not the generator, which is recorded for the
specification.

A simulation scenario is a list of steps, each of which maps to one library call: install a
document, advance the clock, report a health signal, route a key, record an outcome, take a plan
step, assert a decision, assert a health state, assert a plan summary. The interpreter is a switch
over the step kind and is the whole of the simulation harness. The library's refusal to read a wall
clock under `CORE-004` and to start a thread under `CORE-060` is what makes a scenario reproducible:
the harness supplies a settable `MonotonicClock` and drives every timer itself.

### Conformance report

`ConformanceReport` writes a machine-readable summary after the suite runs: the manifest revision,
each suite, each level, the vectors passed, the vectors failed, the declared exclusions, and the
union of requirement identifiers the run exercised. The Gradle task `conformanceReport` produces it
and the release process attaches it, so the rule by which the port declares conformance is checked
against evidence rather than against a claim.

## Build and quality gates

### Gradle configuration

Convention plugins in `buildSrc` carry the settings every project shares: `--release 21`, `-Werror`
with `-Xlint:all`, a `module-info.java` on every published project, reproducible archives, a JaCoCo
report with a line coverage floor, and the JUnit 5 platform.

`check` runs the unit tests, the conformance suite at level core, `verifyDocLinks`,
`verifyDocStyle`, `verifyUnsignedComparisons`, and the dependency check that fails a published POM
carrying a compile or runtime dependency. All of it runs offline, needs no container runtime, and
completes in seconds.

### Documentation checks

Both documentation checks live in `buildSrc` and attach to the root project.

`verifyDocLinks` proves that every cross-reference resolves. It walks every Markdown file under
`docs/` and the repository `README.md`, and for each reference it checks:

- a relative link target exists on disk;
- an anchor resolves against a heading of the target file, under GitHub's slug rules;
- a requirement identifier of the form `PREFIX-NNN` names a requirement that
  [`10-specification.md`](10-specification.md) defines, and the prefix is one the prefix table
  declares;
- an ADR reference names a file that exists under `docs/design/adr/`.

Failures are reported with file, line, and the unresolved reference, and every failure is reported
rather than the first.

`verifyDocStyle` refuses four things and no more.

| Refusal | Rule |
|---|---|
| dash convention | no em dash, no en dash used as a dash, no spaced double hyphen |
| capitalised stress | a word of two or more capitals is refused unless it is in the acronym list in `buildSrc`, or is an RFC 2119 keyword inside `10-specification.md`, or is inside a code span or a code block |
| bold ceiling | the count of bold spans in a file is at or below a stated ceiling, and no bold span exceeds a stated word count |
| argumentative headings | a heading carries no comma, no clause-joining conjunction, no question mark, no verb of judgement from the list in `buildSrc`, and no more than eight words |

Neither check has a per-line suppression. The acronym vocabulary and the judgement-verb vocabulary
are lists in `buildSrc`, so teaching either one a new entry is a change somebody reviews.

Register, justification, and terminology stay with the reviewer. Whether a sentence describes the
design or argues for it is not a property a regular expression sees.

### Unsigned comparison check

`verifyUnsignedComparisons` reads the compiled classes of the `core.internal.hash` and
`core.internal.placement` packages and fails on a signed comparison of a 64-bit value. It refuses
the `LCMP` opcode, an invocation of `Long.compare`, `Math.max(long, long)`, `Math.min(long, long)`,
`Long.signum`, or `Comparator.comparingLong`, and a conversion from `long` to `double` or
`float`.

The allowlist is a method list in `buildSrc`, not an annotation and not a comment, so adding to it
is a change somebody reviews. It holds the epoch and instant comparisons named under "Epoch and
instant" and nothing else. `U64` is exempt from the check, because it is where the sanctioned forms
are written.

The check is the mechanical half of the rule. The other half is the conformance vector whose derived
tokens straddle 2^63.

### Benchmarks

`sharder-bench` uses JMH through the `me.champeau.jmh` plugin and is not part of `check`. JMH is too
slow and too variable on shared continuous integration hardware to gate a merge. The benchmarks run
on demand and on a nightly job on fixed hardware, and a regression is read from the trend.

| Benchmark | Shape |
|---|---|
| `route` | factor 1 and 3, each strategy, 10, 100, and 1000 nodes |
| `routeForRead` | factor 3, with and without a matching affinity path |
| `explain` | 1000 nodes, to confirm the cost `OBS-046` keeps off the routing path |
| `prepare` | stage 6 of `TOPO-001`, 1000 nodes at 4096 tokens each |
| `canonicalise` | RFC 8785 plus SHA-256 over a 1000-node document |

One placement gate does run in `check`. A test measures the allocation of a `route` call at factor
3 over a hundred-node ring with `ThreadMXBean.getThreadAllocatedBytes` and fails above a stated
ceiling with headroom. A candidate cursor quietly turned into a materialised list, or a `Labels`
turned into a `Map`, shows up there and nowhere else.

## Decision records

| Record | Subject |
|---|---|
| [`adr/0028-java-module-and-artifact-layout.md`](adr/0028-java-module-and-artifact-layout.md) | the artifact split and the module boundaries |
| [`adr/0029-exception-idiom-for-the-taxonomy.md`](adr/0029-exception-idiom-for-the-taxonomy.md) | exceptions against a result type, the sealed hierarchy, unchecked |
| [`adr/0030-unsigned-integer-discipline.md`](adr/0030-unsigned-integer-discipline.md) | unsigned 64-bit arithmetic and its build check |
| [`adr/0031-jdk-baseline.md`](adr/0031-jdk-baseline.md) | the Java 21 floor |
| [`adr/0032-dependency-free-json-and-canonicalisation.md`](adr/0032-dependency-free-json-and-canonicalisation.md) | the JSON reader, the canonicaliser, and the absence of a schema validator |
| [`adr/0033-opaque-identifier-value-types.md`](adr/0033-opaque-identifier-value-types.md) | value types over `byte[]`, and the `Optional` policy |
| [`adr/0034-lazy-candidate-traversal-surface.md`](adr/0034-lazy-candidate-traversal-surface.md) | the candidate cursor against `Iterator` and `Stream` |
| [`adr/0035-manifest-driven-conformance-harness.md`](adr/0035-manifest-driven-conformance-harness.md) | dynamic tests, the manifest coupling, and the vector artifact |
