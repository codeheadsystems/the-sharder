package com.codeheadsystems.sharder.core.internal.route;

import com.codeheadsystems.sharder.AffinityRequest;
import com.codeheadsystems.sharder.AttemptSequence;
import com.codeheadsystems.sharder.MatchedOverride;
import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.OverrideMode;
import com.codeheadsystems.sharder.PreferenceEntry;
import com.codeheadsystems.sharder.Role;
import com.codeheadsystems.sharder.RouteOptions;
import com.codeheadsystems.sharder.Router;
import com.codeheadsystems.sharder.RoutingDecision;
import com.codeheadsystems.sharder.RoutingKey;
import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.Shortfall;
import com.codeheadsystems.sharder.config.ConfigurationView;
import com.codeheadsystems.sharder.config.RouterConfig;
import com.codeheadsystems.sharder.config.StalePolicy;
import com.codeheadsystems.sharder.core.internal.document.Digests;
import com.codeheadsystems.sharder.core.internal.document.TopologyLoader;
import com.codeheadsystems.sharder.core.internal.fence.DefaultRecipient;
import com.codeheadsystems.sharder.core.internal.health.SlidingWindowHealthView;
import com.codeheadsystems.sharder.core.internal.json.JsonReader;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import com.codeheadsystems.sharder.core.internal.observe.MetricsHolder;
import com.codeheadsystems.sharder.core.internal.snapshot.DocumentSnapshot;
import com.codeheadsystems.sharder.error.ErrorCode;
import com.codeheadsystems.sharder.error.InvalidArgumentException;
import com.codeheadsystems.sharder.error.InvalidTopologyException;
import com.codeheadsystems.sharder.error.ProviderException;
import com.codeheadsystems.sharder.error.StaleDocumentException;
import com.codeheadsystems.sharder.error.StaleSnapshotException;
import com.codeheadsystems.sharder.error.TopologyConflictException;
import com.codeheadsystems.sharder.error.UnreadyException;
import com.codeheadsystems.sharder.fence.Recipient;
import com.codeheadsystems.sharder.health.HealthState;
import com.codeheadsystems.sharder.health.HealthView;
import com.codeheadsystems.sharder.observe.Event;
import com.codeheadsystems.sharder.observe.ExplainRecord;
import com.codeheadsystems.sharder.observe.Labels;
import com.codeheadsystems.sharder.observe.MetricsView;
import com.codeheadsystems.sharder.observe.Severity;
import com.codeheadsystems.sharder.topology.Loaded;
import com.codeheadsystems.sharder.topology.SourceVersion;
import com.codeheadsystems.sharder.topology.Subscription;
import com.codeheadsystems.sharder.topology.TopologyProvider;
import com.codeheadsystems.sharder.topology.TopologySink;
import com.codeheadsystems.sharder.topology.TopologySnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The router the library hands an integrator.
 *
 * <p>It holds one snapshot in force, replaced whole at an installation. A routing call reads the
 * reference once and answers against that snapshot from first candidate to last, under
 * {@code CORE-041}, so an installation racing a routing call changes nothing the call already
 * decided.
 *
 * <p>Nothing here is observed by the library itself: documents arrive from the provider, health
 * arrives as reported signals, and time arrives from the configured clock.
 */
public final class DefaultRouter implements Router {

    private final RouterConfig config;
    private final TopologyProvider provider;
    private final TopologyLoader pipeline = new TopologyLoader();
    private final HealthView health;
    private final RetryBudget budget;
    private final MetricsHolder metrics;
    private final Object loadLock = new Object();

    private volatile DocumentSnapshot inForce;
    private volatile long installedAt;
    private Optional<SourceVersion> known = Optional.empty();
    private Subscription subscription;
    private ScheduledFuture<?> polling;

    /** The router over one configuration, which begins loading as it is constructed. */
    public DefaultRouter(RouterConfig config) {
        this.config = config;
        this.provider = config.provider().provider();
        this.health = config.healthView().orElseGet(
                () -> new SlidingWindowHealthView(config.health()));
        this.budget = new RetryBudget(config.routing().retryBudgetWindowMillis(),
                config.routing().retryBudgetPercent(), config.routing().retryBudgetMinimum());
        this.metrics = new MetricsHolder(config.observability().metricsRegistry(),
                config.observability().eventSink());
        start();
    }

    /** The metrics and events the router holds, which a test and an integrator both read. */
    public MetricsHolder holder() {
        return metrics;
    }

    private void start() {
        TopologyProvider.Capabilities capabilities = provider.capabilities();
        if (capabilities.push()) {
            subscription = provider.watch(new TopologySink() {
                @Override
                public void onDocument(byte[] document, Optional<SourceVersion> version) {
                    // The conditions that never raise: a document refused here is ordinary
                    // operation and no call the integrator made is waiting on it.
                    accept(document, version, false);
                }

                @Override
                public void onError(Throwable error) {
                    providerFailure(error);
                }
            });
        }
        if (capabilities.pull()) {
            try {
                pull(false);
            } catch (RuntimeException failure) {
                providerFailure(failure);
            }
            config.executor().ifPresent(executor -> {
                long period = capabilities.push()
                        ? config.provider().reconcileIntervalMillis()
                        : config.provider().pollIntervalMillis();
                polling = executor.scheduleWithFixedDelay(this::poll, period, period,
                        TimeUnit.MILLISECONDS);
            });
        }
    }

    private void poll() {
        try {
            pull(false);
        } catch (RuntimeException failure) {
            providerFailure(failure);
        }
    }

    private void providerFailure(Throwable error) {
        metrics.counter("sharder.topology.provider_errors", Labels.none(), 1);
        metrics.emit(new Event("sharder.topology.provider_error", config.clock().millis(),
                topologyId(), epoch(), Severity.ERROR,
                Map.of("message", String.valueOf(error.getMessage()))));
    }

    private void pull(boolean raise) {
        Loaded loaded;
        try {
            loaded = provider.load(known);
        } catch (Throwable failure) {
            // ERR-063: the original survives as the Java cause, and the provider boundary is one
            // of the two places this library catches Throwable.
            if (raise) {
                throw new ProviderException("the provider failed to load a document", failure);
            }
            providerFailure(failure);
            return;
        }
        switch (loaded) {
            case Loaded.Document document -> {
                known = document.version();
                accept(document.document(), document.version(), raise);
            }
            case Loaded.Unchanged ignored -> {
                if (known.isEmpty()) {
                    // CORE-086: answering unchanged for an empty `known` is a provider error.
                    ProviderException failure = new ProviderException(
                            "the provider answered unchanged for no known version", null);
                    if (raise) {
                        throw failure;
                    }
                    providerFailure(failure);
                    return;
                }
                metrics.counter("sharder.topology.unchanged", Labels.none(), 1);
            }
        }
    }

    private void accept(byte[] document, Optional<SourceVersion> version, boolean raise) {
        synchronized (loadLock) {
            JsonObject parsed;
            try {
                parsed = JsonReader.read(new String(document, StandardCharsets.UTF_8)).asObject();
            } catch (RuntimeException failure) {
                if (raise) {
                    throw new InvalidTopologyException(List.of(
                            new com.codeheadsystems.sharder.topology.ValidationError("", "json",
                                    String.valueOf(failure.getMessage()))));
                }
                rejected("json");
                return;
            }
            TopologyLoader.Arrival arrival = pipeline.accept(parsed);
            metrics.counter("sharder.topology.documents",
                    Labels.of("outcome", arrival.outcome().spelling()), 1);
            switch (arrival.outcome()) {
                case INSTALLED -> {
                    long at = config.clock().millis();
                    DocumentSnapshot snapshot = new DocumentSnapshot(pipeline.engine().orElseThrow(),
                            arrival.digest(), OptionalLong.of(at));
                    inForce = snapshot;
                    installedAt = at;
                    known = version;
                    health.onSnapshotInstalled(snapshot);
                    metrics.emit(new Event("sharder.topology.installed", at, snapshot.topologyId(),
                            snapshot.epoch(), Severity.INFO,
                            Map.of("digest", snapshot.digest().toHex())));
                }
                case NOOP -> metrics.counter("sharder.topology.noop", Labels.none(), 1);
                case REJECTED -> {
                    rejected(arrival.condition().map(ErrorCode::errorName).orElse("unknown"));
                    if (raise) {
                        throw refusal(arrival);
                    }
                }
            }
        }
    }

    private void rejected(String condition) {
        metrics.counter("sharder.topology.rejected", Labels.of("condition", condition), 1);
        metrics.emit(new Event("sharder.topology.rejected", config.clock().millis(), topologyId(),
                epoch(), Severity.WARNING, Map.of("condition", condition)));
    }

    private RuntimeException refusal(TopologyLoader.Arrival arrival) {
        ErrorCode condition = arrival.condition().orElse(ErrorCode.INVALID_TOPOLOGY);
        String detail = arrival.detail().orElse("the document was refused");
        return switch (condition) {
            case INVALID_TOPOLOGY -> new InvalidTopologyException(arrival.errors());
            case STALE_DOCUMENT -> new StaleDocumentException(detail);
            default -> new TopologyConflictException(detail);
        };
    }

    private String topologyId() {
        DocumentSnapshot snapshot = inForce;
        return snapshot == null ? "" : snapshot.topologyId();
    }

    private long epoch() {
        DocumentSnapshot snapshot = inForce;
        return snapshot == null ? 0L : snapshot.epoch();
    }

    @Override
    public RoutingDecision route(byte[] key) {
        return route(key, RouteOptions.DEFAULTS);
    }

    @Override
    public RoutingDecision route(byte[] key, RouteOptions options) {
        return decide(key, options, null);
    }

    @Override
    public RoutingDecision route(String key, RouteOptions options) {
        // KEY-003: a textual key is its UTF-8 octets, and nothing else about it is read.
        return route(key.getBytes(StandardCharsets.UTF_8), options);
    }

    @Override
    public RoutingDecision routeForRead(byte[] key, AffinityRequest affinity,
                                        RouteOptions options) {
        return decide(key, options, affinity);
    }

    private RoutingDecision decide(byte[] key, RouteOptions options, AffinityRequest affinity) {
        DocumentSnapshot snapshot = ready(key);
        PlacementEngine engine = snapshot.engine();
        PlacementDecision decision = engine.route(key, limit(snapshot, options));
        List<PreferenceEntry> entries = entries(decision);
        // FAIL-003: the attempt order is the materialised prefix with the health filter applied,
        // in preference list order. The walk of FAIL-014 reads past the prefix, and `attempts`
        // builds it, because recomputing the whole preference list here would put the cost
        // CORE-046 exists to avoid back on the routing path.
        List<NodeId> attemptable = new ArrayList<>(entries.size());
        entries.forEach(entry -> {
            if (entry.attemptable()) {
                attemptable.add(entry.node());
            }
        });
        boolean failedOpen = attemptable.isEmpty() && !entries.isEmpty();
        if (failedOpen) {
            // FAIL-012: the filter never yields an empty sequence from a non-empty list.
            entries.forEach(entry -> attemptable.add(entry.node()));
        }
        List<NodeId> ordering = attemptable;
        if (affinity != null) {
            int window = config.routing().readAffinityWindow().orElse(decision.replicaCount());
            ordering = ReadAffinity.reorder(snapshot.document(), ordering,
                    decision.replicaCount(),
                    new ReadAffinity.Request(affinity.level(), affinity.path(),
                            OptionalInt.of(affinity.window().orElse(window))));
        }
        metrics.counter("sharder.routing.decisions",
                Labels.of("strategy", snapshot.document().strategy().kind()), 1);
        if (!"none".equals(decision.shortfall())) {
            metrics.counter("sharder.routing.shortfalls",
                    Labels.of("cause", decision.shortfall()), 1);
        }
        return new RoutingDecision(
                RoutingKey.ofBytes(decision.routingKey()),
                decision.shard().map(ShardId::of),
                token(snapshot),
                decision.factor(),
                decision.replicaCount(),
                decision.attemptLimit(),
                entries,
                ordered(ordering, entries, decision),
                decision.relaxedLevels(),
                Shortfall.of(decision.shortfall()),
                failedOpen,
                decision.matchedOverride().map(matched ->
                        new MatchedOverride(matched.index(), OverrideMode.of(matched.mode()))),
                options.explain()
                        ? Optional.of(ExplainBuilder.of(snapshot, key, health,
                                config.fencing().tokenDigest()))
                        : Optional.empty(),
                snapshot);
    }

    /** The snapshot a call routes against, or the condition that stops it. */
    private DocumentSnapshot ready(byte[] key) {
        if (key == null) {
            throw new InvalidArgumentException("a key is never null");
        }
        // CFG-013: the length is compared before the key transform, and the key is never truncated.
        if (key.length > config.provider().maxKeyBytes()) {
            throw new InvalidArgumentException("a key carries at most "
                    + config.provider().maxKeyBytes() + " octets, not " + key.length);
        }
        DocumentSnapshot snapshot = inForce;
        if (snapshot == null) {
            throw new UnreadyException("no snapshot is in force");
        }
        int staleAfter = config.provider().staleAfterMillis();
        if (staleAfter > 0 && config.provider().stalePolicy() == StalePolicy.REFUSE
                && config.clock().millis() - installedAt > staleAfter) {
            throw new StaleSnapshotException("the snapshot in force is older than " + staleAfter
                    + " milliseconds");
        }
        return snapshot;
    }

    private OptionalInt limit(DocumentSnapshot snapshot, RouteOptions options) {
        if (options.attemptLimit().isPresent()) {
            return options.attemptLimit();
        }
        return config.routing().attemptLimit();
    }

    private com.codeheadsystems.sharder.topology.FencingToken token(DocumentSnapshot snapshot) {
        return config.fencing().tokenDigest()
                ? snapshot.token()
                : com.codeheadsystems.sharder.topology.FencingToken.of(snapshot.topologyId(),
                        snapshot.epoch());
    }

    /** The materialised prefix of {@code CORE-046}, with the health state of each entry. */
    private List<PreferenceEntry> entries(PlacementDecision decision) {
        List<NodeId> materialised = decision.entries();
        List<PreferenceEntry> entries = new ArrayList<>(materialised.size());
        for (int position = 0; position < materialised.size(); position++) {
            NodeId node = materialised.get(position);
            HealthState state = health.stateOf(node);
            entries.add(new PreferenceEntry(node, position,
                    position < decision.replicaCount() ? Role.REPLICA : Role.FALLBACK, state,
                    state.attemptable()));
        }
        return List.copyOf(entries);
    }

    /** The order a caller attempts in, which the health filter and read affinity produce. */
    private List<PreferenceEntry> ordered(List<NodeId> ordering, List<PreferenceEntry> entries,
                                          PlacementDecision decision) {
        List<PreferenceEntry> reading = new ArrayList<>(ordering.size());
        for (int position = 0; position < ordering.size(); position++) {
            NodeId node = ordering.get(position);
            PreferenceEntry known = entries.stream()
                    .filter(entry -> entry.node().equals(node)).findFirst().orElse(null);
            HealthState state = known == null ? health.stateOf(node) : known.health();
            reading.add(new PreferenceEntry(node, position,
                    known == null
                            ? (position < decision.replicaCount() ? Role.REPLICA : Role.FALLBACK)
                            : known.role(),
                    state, state.attemptable()));
        }
        return List.copyOf(reading);
    }

    private DefaultAttemptSequence sequence(DocumentSnapshot snapshot, PlacementDecision decision) {
        List<NodeId> known = new ArrayList<>();
        snapshot.document().nodes().forEach(node -> known.add(node.id()));
        return new DefaultAttemptSequence(decision.preferenceList(), health, budget,
                decision.attemptLimit(), known, config.clock(), config.fencing().maxRedirects());
    }

    @Override
    public AttemptSequence attempts(RoutingDecision decision) {
        TopologySnapshot snapshot = decision.snapshot();
        if (!(snapshot instanceof DocumentSnapshot document)) {
            throw new InvalidArgumentException("the decision was not produced by this library");
        }
        return sequence(document, document.engine().route(decision.routingKey().toBytes(),
                OptionalInt.of(decision.attemptLimit())));
    }

    @Override
    public ExplainRecord explain(byte[] key) {
        // OBS-048: `unready` where no snapshot is in force, and a record rather than a condition
        // where the candidate ordering is empty.
        return ExplainBuilder.of(ready(key), key, health, config.fencing().tokenDigest());
    }

    @Override
    public Optional<TopologySnapshot> snapshot() {
        return Optional.ofNullable(inForce);
    }

    @Override
    public HealthView health() {
        return health;
    }

    @Override
    public void refresh() {
        // A call the integrator made, so the topology conditions are raised rather than recorded.
        synchronized (loadLock) {
            pull(true);
        }
    }

    @Override
    public Recipient recipient(NodeId selfId) {
        return new DefaultRecipient(pipeline, selfId, config.fencing().recipientPolicy());
    }

    @Override
    public com.codeheadsystems.sharder.core.TopologyLoader loader() {
        return document -> {
            TopologyLoader standalone = new TopologyLoader();
            JsonObject parsed = JsonReader.read(
                    new String(document, StandardCharsets.UTF_8)).asObject();
            TopologyLoader.Arrival arrival = standalone.accept(parsed);
            if (arrival.outcome() == TopologyLoader.Outcome.REJECTED) {
                throw refusal(arrival);
            }
            // TOPO-191: the snapshot was never in force, so it carries no installation instant.
            return new DocumentSnapshot(standalone.engine().orElseThrow(),
                    Digests.of(com.codeheadsystems.sharder.core.internal.json.JcsWriter
                            .canonicalise(parsed)), OptionalLong.empty());
        };
    }

    @Override
    public ConfigurationView configuration() {
        return config;
    }

    @Override
    public MetricsView metrics() {
        return metrics;
    }

    @Override
    public void close() {
        if (polling != null) {
            polling.cancel(false);
        }
        if (subscription != null) {
            subscription.cancel();
        }
        provider.close();
    }
}
