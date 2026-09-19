package com.codeheadsystems.sharder.config;

import com.codeheadsystems.sharder.MonotonicClock;
import com.codeheadsystems.sharder.error.InvalidArgumentException;
import com.codeheadsystems.sharder.fence.RecipientPolicy;
import com.codeheadsystems.sharder.health.HealthView;
import com.codeheadsystems.sharder.observe.EventSink;
import com.codeheadsystems.sharder.observe.MetricsRegistry;
import com.codeheadsystems.sharder.topology.TopologyProvider;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Everything the integrator supplies at construction, under {@code CFG-001}.
 *
 * <p>The topology document carries what is agreed between callers; configuration carries what is
 * local to one of them. A configuration is built by a builder, is immutable once built, and is
 * validated at build time: a value outside its range refuses construction with
 * {@code invalidArgument} under {@code CFG-003} and {@code ERR-025}, no setting is clamped, and no
 * unrecognised setting is ignored.
 *
 * <p>Every duration is a count of milliseconds carrying the {@code Millis} suffix of
 * {@code CFG-006}. The builder also accepts a {@link Duration} on each such setter, converts it to
 * milliseconds, and refuses a negative value or one with sub-millisecond precision. No setting is a
 * floating-point value, under {@code CFG-005}.
 */
public final class RouterConfig implements ConfigurationView {

    private final ProviderSettings provider;
    private final RoutingSettings routing;
    private final HealthSettings health;
    private final FencingSettings fencing;
    private final ObservabilitySettings observability;
    private final MonotonicClock clock;
    private final HealthView healthView;
    private final ScheduledExecutorService executor;

    private RouterConfig(Builder builder) {
        this.provider = new ProviderSettings(builder.provider, builder.expectedTopologyId,
                builder.minEpoch, builder.pollIntervalMillis, builder.reconcileIntervalMillis,
                builder.initialTimeoutMillis, builder.providerRetryBaseMillis,
                builder.providerRetryCapMillis, builder.providerRetryJitter,
                builder.staleAfterMillis, builder.stalePolicy, builder.retentionDepth,
                builder.maxKeyBytes, builder.directoryWarnEntries,
                builder.rendezvousWarnVirtualNodes, builder.ringWarnTokens);
        this.routing = new RoutingSettings(builder.attemptLimit, builder.retryBudgetWindowMillis,
                builder.retryBudgetPercent, builder.retryBudgetMinimum,
                builder.readAffinityWindow);
        this.health = builder.health;
        this.fencing = new FencingSettings(builder.recipientPolicy, builder.maxRedirects,
                builder.refreshWaitMillis, builder.tokenDigest);
        this.observability = new ObservabilitySettings(
                Optional.ofNullable(builder.metricsRegistry), Optional.ofNullable(builder.eventSink),
                builder.shardLabelLimit, builder.nodeLabelLimit, builder.hotShardFactorPercent,
                builder.keySkewPercent, builder.includeKeysInDiagnostics);
        this.clock = builder.clock;
        this.healthView = builder.healthView;
        this.executor = builder.executor;
    }

    /** A builder carrying every default. */
    public static Builder builder() {
        return new Builder();
    }

    /** Where documents come from and how long one stays in force. */
    public ProviderSettings provider() {
        return provider;
    }

    /** What one routing call inherits. */
    public RoutingSettings routing() {
        return routing;
    }

    /** The parameters of the built-in health state machine. */
    public HealthSettings health() {
        return health;
    }

    /** What a recipient does with a token. */
    public FencingSettings fencing() {
        return fencing;
    }

    /** Where metrics and events go. */
    public ObservabilitySettings observability() {
        return observability;
    }

    /** The instant source every elapsed-interval rule reads. */
    public MonotonicClock clock() {
        return clock;
    }

    /**
     * The health view supplied in place of the built-in one, under {@code HEALTH-013}.
     *
     * <p>Where one is supplied the parameters of {@code HEALTH-055} are ignored, under
     * {@code CFG-032}.
     */
    public Optional<HealthView> healthView() {
        return Optional.ofNullable(healthView);
    }

    /**
     * The executor polling and reconciliation run on, under {@code CFG-012}.
     *
     * <p>Where it is unset the library polls nothing, and {@code refresh} is the only path by which
     * a document arrives on a pull-only provider.
     */
    public Optional<ScheduledExecutorService> executor() {
        return Optional.ofNullable(executor);
    }

    @Override
    public Map<String, String> settings() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("expectedTopologyId", provider.expectedTopologyId().orElse("unset"));
        values.put("minEpoch", provider.minEpoch().isPresent()
                ? Long.toString(provider.minEpoch().getAsLong()) : "unset");
        values.put("pollIntervalMillis", Integer.toString(provider.pollIntervalMillis()));
        values.put("reconcileIntervalMillis",
                Integer.toString(provider.reconcileIntervalMillis()));
        values.put("initialTimeoutMillis", Integer.toString(provider.initialTimeoutMillis()));
        values.put("providerRetryBaseMillis",
                Integer.toString(provider.providerRetryBaseMillis()));
        values.put("providerRetryCapMillis", Integer.toString(provider.providerRetryCapMillis()));
        values.put("providerRetryJitter", Boolean.toString(provider.providerRetryJitter()));
        values.put("staleAfterMillis", Integer.toString(provider.staleAfterMillis()));
        values.put("stalePolicy", provider.stalePolicy().spelling());
        values.put("retentionDepth", Integer.toString(provider.retentionDepth()));
        values.put("maxKeyBytes", Integer.toString(provider.maxKeyBytes()));
        values.put("directoryWarnEntries", Integer.toString(provider.directoryWarnEntries()));
        values.put("rendezvousWarnVirtualNodes",
                Integer.toString(provider.rendezvousWarnVirtualNodes()));
        values.put("ringWarnTokens", Integer.toString(provider.ringWarnTokens()));
        values.put("attemptLimit", routing.attemptLimit().isPresent()
                ? Integer.toString(routing.attemptLimit().getAsInt()) : "the factor plus 2");
        values.put("retryBudgetWindowMillis",
                Integer.toString(routing.retryBudgetWindowMillis()));
        values.put("retryBudgetPercent", Integer.toString(routing.retryBudgetPercent()));
        values.put("retryBudgetMinimum", Integer.toString(routing.retryBudgetMinimum()));
        values.put("readAffinityWindow", routing.readAffinityWindow().isPresent()
                ? Integer.toString(routing.readAffinityWindow().getAsInt()) : "the replica count");
        values.put("healthView", healthView == null ? "the built-in sliding window" : "supplied");
        values.put("windowMillis", Long.toString(health.windowMillis()));
        values.put("bucketCount", Integer.toString(health.bucketCount()));
        values.put("minimumSamples", Integer.toString(health.minimumSamples()));
        values.put("failureRatePercent", Integer.toString(health.failureRatePercent()));
        values.put("consecutiveFailureThreshold",
                Integer.toString(health.consecutiveFailureThreshold()));
        values.put("baseEjectionMillis", Long.toString(health.baseEjectionMillis()));
        values.put("maxEjectionMillis", Long.toString(health.maxEjectionMillis()));
        values.put("probationMillis", Long.toString(health.probationMillis()));
        values.put("probationDivisor", Integer.toString(health.probationDivisor()));
        values.put("outlierMarginPercent", Integer.toString(health.outlierMarginPercent()));
        values.put("outlierMinimumNodes", Integer.toString(health.outlierMinimumNodes()));
        values.put("maxEjectionPercent", Integer.toString(health.maxEjectionPercent()));
        values.put("ejectionResetMillis", Long.toString(health.ejectionResetMillis()));
        values.put("resetOnPlacementReentry",
                Boolean.toString(health.resetOnPlacementReentry()));
        values.put("recipientPolicy", fencing.recipientPolicy().spelling());
        values.put("maxRedirects", Integer.toString(fencing.maxRedirects()));
        values.put("refreshWaitMillis", Integer.toString(fencing.refreshWaitMillis()));
        values.put("tokenDigest", Boolean.toString(fencing.tokenDigest()));
        values.put("metricsRegistry",
                observability.metricsRegistry().isPresent() ? "supplied" : "held internally");
        values.put("eventSink",
                observability.eventSink().isPresent() ? "supplied" : "counted by name");
        values.put("shardLabelLimit", Integer.toString(observability.shardLabelLimit()));
        values.put("nodeLabelLimit", Integer.toString(observability.nodeLabelLimit()));
        values.put("hotShardFactorPercent",
                Integer.toString(observability.hotShardFactorPercent()));
        values.put("keySkewPercent", Integer.toString(observability.keySkewPercent()));
        values.put("includeKeysInDiagnostics",
                Boolean.toString(observability.includeKeysInDiagnostics()));
        values.put("executor", executor == null ? "unset" : "supplied");
        return Map.copyOf(values);
    }

    /** The builder of {@code CFG-003}, which validates at build time and clamps nothing. */
    public static final class Builder {

        private TopologyProvider provider;
        private Optional<String> expectedTopologyId = Optional.empty();
        private OptionalLong minEpoch = OptionalLong.empty();
        private int pollIntervalMillis = 30000;
        private int reconcileIntervalMillis = 300000;
        private int initialTimeoutMillis = 10000;
        private int providerRetryBaseMillis = 1000;
        private int providerRetryCapMillis = 60000;
        private boolean providerRetryJitter = true;
        private int staleAfterMillis;
        private StalePolicy stalePolicy = StalePolicy.SERVE;
        private int retentionDepth = 3;
        private int maxKeyBytes = 65536;
        private int directoryWarnEntries = 10000;
        private int rendezvousWarnVirtualNodes = 4096;
        private int ringWarnTokens = 1000000;
        private OptionalInt attemptLimit = OptionalInt.empty();
        private int retryBudgetWindowMillis = 10000;
        private int retryBudgetPercent = 20;
        private int retryBudgetMinimum = 3;
        private OptionalInt readAffinityWindow = OptionalInt.empty();
        private HealthSettings health = HealthSettings.defaults();
        private HealthView healthView;
        private RecipientPolicy recipientPolicy = RecipientPolicy.STRICT;
        private int maxRedirects = 2;
        private int refreshWaitMillis;
        private boolean tokenDigest;
        private MetricsRegistry metricsRegistry;
        private EventSink eventSink;
        private int shardLabelLimit = 1024;
        private int nodeLabelLimit = 1024;
        private int hotShardFactorPercent = 400;
        private int keySkewPercent = 50;
        private boolean includeKeysInDiagnostics;
        private MonotonicClock clock = MonotonicClock.systemNanoTime();
        private ScheduledExecutorService executor;

        private Builder() {
        }

        /** The provider documents are loaded from, which is required. */
        public Builder provider(TopologyProvider value) {
            this.provider = value;
            return this;
        }

        /** The identifier every document is checked against, under {@code TOPO-091}. */
        public Builder expectedTopologyId(String value) {
            this.expectedTopologyId = Optional.of(value);
            return this;
        }

        /** The monotonicity floor across a restart, under {@code TOPO-071}. */
        public Builder minEpoch(long value) {
            this.minEpoch = OptionalLong.of(value);
            return this;
        }

        /** The period at which a pull-only provider is polled. */
        public Builder pollIntervalMillis(int value) {
            this.pollIntervalMillis = value;
            return this;
        }

        /** The period at which a pull-only provider is polled. */
        public Builder pollInterval(Duration value) {
            return pollIntervalMillis(millis(value, "pollInterval"));
        }

        /** The poll period for a provider that also pushes. */
        public Builder reconcileIntervalMillis(int value) {
            this.reconcileIntervalMillis = value;
            return this;
        }

        /** The poll period for a provider that also pushes. */
        public Builder reconcileInterval(Duration value) {
            return reconcileIntervalMillis(millis(value, "reconcileInterval"));
        }

        /** The wait for a first document before {@code unready}. */
        public Builder initialTimeoutMillis(int value) {
            this.initialTimeoutMillis = value;
            return this;
        }

        /** The wait for a first document before {@code unready}. */
        public Builder initialTimeout(Duration value) {
            return initialTimeoutMillis(millis(value, "initialTimeout"));
        }

        /** The first backoff interval after a provider failure. */
        public Builder providerRetryBaseMillis(int value) {
            this.providerRetryBaseMillis = value;
            return this;
        }

        /** The ceiling on the provider backoff interval. */
        public Builder providerRetryCapMillis(int value) {
            this.providerRetryCapMillis = value;
            return this;
        }

        /** Whether an integer jitter below the interval is subtracted. */
        public Builder providerRetryJitter(boolean value) {
            this.providerRetryJitter = value;
            return this;
        }

        /** The age past which the snapshot is marked stale, 0 disabling it. */
        public Builder staleAfterMillis(int value) {
            this.staleAfterMillis = value;
            return this;
        }

        /** What a routing call does against a stale snapshot. */
        public Builder stalePolicy(StalePolicy value) {
            this.stalePolicy = value;
            return this;
        }

        /** The previous snapshots retained, each with a prepared placement. */
        public Builder retentionDepth(int value) {
            this.retentionDepth = value;
            return this;
        }

        /** The ceiling on a key, above which a routing call answers {@code invalidArgument}. */
        public Builder maxKeyBytes(int value) {
            this.maxKeyBytes = value;
            return this;
        }

        /** The entry count above which the directory event is emitted. */
        public Builder directoryWarnEntries(int value) {
            this.directoryWarnEntries = value;
            return this;
        }

        /** The summed virtual node count above which the event is emitted. */
        public Builder rendezvousWarnVirtualNodes(int value) {
            this.rendezvousWarnVirtualNodes = value;
            return this;
        }

        /** The ring token total above which the event is emitted. */
        public Builder ringWarnTokens(int value) {
            this.ringWarnTokens = value;
            return this;
        }

        /** The limit a call inherits where it supplies none, under {@code CORE-048}. */
        public Builder attemptLimit(int value) {
            this.attemptLimit = OptionalInt.of(value);
            return this;
        }

        /** The accounting window for the retry budget. */
        public Builder retryBudgetWindowMillis(int value) {
            this.retryBudgetWindowMillis = value;
            return this;
        }

        /** The accounting window for the retry budget. */
        public Builder retryBudgetWindow(Duration value) {
            return retryBudgetWindowMillis(millis(value, "retryBudgetWindow"));
        }

        /** The retries permitted as a percentage of first attempts. */
        public Builder retryBudgetPercent(int value) {
            this.retryBudgetPercent = value;
            return this;
        }

        /** The retries permitted in the window regardless of the percentage. */
        public Builder retryBudgetMinimum(int value) {
            this.retryBudgetMinimum = value;
            return this;
        }

        /** The entries read affinity may reorder, under {@code READ-012}. */
        public Builder readAffinityWindow(int value) {
            this.readAffinityWindow = OptionalInt.of(value);
            return this;
        }

        /** The parameters of the built-in health state machine. */
        public Builder health(HealthSettings value) {
            this.health = value;
            return this;
        }

        /** A health view replacing the built-in one, under {@code HEALTH-013}. */
        public Builder healthView(HealthView value) {
            this.healthView = value;
            return this;
        }

        /** The refusal policy for a stale or unfenced request. */
        public Builder recipientPolicy(RecipientPolicy value) {
            this.recipientPolicy = value;
            return this;
        }

        /** The redirects one request may follow, under {@code FENCE-181}. */
        public Builder maxRedirects(int value) {
            this.maxRedirects = value;
            return this;
        }

        /** The wait for the epoch to reach a {@code senderAhead} token. */
        public Builder refreshWaitMillis(int value) {
            this.refreshWaitMillis = value;
            return this;
        }

        /** Whether an emitted token carries the digest, under {@code FENCE-011}. */
        public Builder tokenDigest(boolean value) {
            this.tokenDigest = value;
            return this;
        }

        /** The registry metrics are reported through, under {@code OBS-004}. */
        public Builder metricsRegistry(MetricsRegistry value) {
            this.metricsRegistry = value;
            return this;
        }

        /** The sink events are delivered to, under {@code OBS-023}. */
        public Builder eventSink(EventSink value) {
            this.eventSink = value;
            return this;
        }

        /** The shard count at or below which a shard may be a label. */
        public Builder shardLabelLimit(int value) {
            this.shardLabelLimit = value;
            return this;
        }

        /** The node count at or below which a node identity may be a label. */
        public Builder nodeLabelLimit(int value) {
            this.nodeLabelLimit = value;
            return this;
        }

        /** The share of the expected share at which a shard is hot. */
        public Builder hotShardFactorPercent(int value) {
            this.hotShardFactorPercent = value;
            return this;
        }

        /** The share of a shard's requests one key must draw to be reported as skew. */
        public Builder keySkewPercent(int value) {
            this.keySkewPercent = value;
            return this;
        }

        /** Whether {@code detail} may carry key octets, under {@code ERR-005}. */
        public Builder includeKeysInDiagnostics(boolean value) {
            this.includeKeysInDiagnostics = value;
            return this;
        }

        /** The instant source every elapsed-interval rule reads. */
        public Builder clock(MonotonicClock value) {
            this.clock = value;
            return this;
        }

        /** The executor polling and reconciliation run on, under {@code CFG-012}. */
        public Builder executor(ScheduledExecutorService value) {
            this.executor = value;
            return this;
        }

        /** The configuration, validated whole. */
        public RouterConfig build() {
            return new RouterConfig(this);
        }

        /**
         * The milliseconds a duration carries.
         *
         * <p>A negative duration and one with sub-millisecond precision are both refused, because
         * neither is a value {@code CFG-006} can render and the nearest millisecond is a guess.
         */
        private static int millis(Duration value, String name) {
            if (value.isNegative()) {
                throw new InvalidArgumentException(name + " is not negative: " + value);
            }
            if (value.getNano() % 1_000_000 != 0) {
                throw new InvalidArgumentException(
                        name + " carries sub-millisecond precision: " + value);
            }
            long millis = value.toMillis();
            if (millis > Integer.MAX_VALUE) {
                throw new InvalidArgumentException(name + " exceeds the settable range: " + value);
            }
            return (int) millis;
        }
    }
}
