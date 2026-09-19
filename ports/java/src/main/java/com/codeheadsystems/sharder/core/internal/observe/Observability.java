package com.codeheadsystems.sharder.core.internal.observe;

import java.util.List;
import java.util.Map;

/**
 * The metrics of {@code OBS-010} and the events of {@code OBS-020}, by the surface each belongs to.
 *
 * <p>A metric and an event each belong to the surface the first segment of its name gives:
 * {@code health.} is `failover`, {@code fencing.} is `fencing`, {@code migration.} is `migration`,
 * and every other segment is `routing`. The rows below are that last set, transcribed from the
 * tables of the specification, which an implementation exposes whole under {@code OBS-001}.
 *
 * <p>The inventory is data rather than call sites, because a conformance vector asserts the names,
 * the instruments, the labels, the severities, and the payload members, and a name spelled in one
 * place cannot drift from a name spelled in another.
 */
public final class Observability {

    /** One metric: its name, the instrument that carries it, and its labels. */
    public record Metric(String name, String instrument, List<String> labels) {
    }

    /** One event: its name, the severity it always carries, and its payload beyond the common. */
    public record Event(String name, String severity, List<String> payload) {
    }

    private static final String PREFIX = "sharder.";

    private Observability() {
    }

    /** Every metric of one surface, in the order the specification tables them. */
    public static List<Metric> metricsOf(String surface) {
        return switch (surface) {
            case "failover" -> FAILOVER_METRICS;
            case "fencing" -> FENCING_METRICS;
            case "migration" -> MIGRATION_METRICS;
            default -> METRICS;
        };
    }

    /** Every event of one surface, in the order the specification tables them. */
    public static List<Event> eventsOf(String surface) {
        return switch (surface) {
            case "failover" -> FAILOVER_EVENTS;
            case "fencing" -> FENCING_EVENTS;
            case "migration" -> MIGRATION_EVENTS;
            default -> EVENTS;
        };
    }

    /** The metrics of the {@code migration} surface, named {@code migration.}. */
    public static final List<Metric> MIGRATION_METRICS = List.of(
            counter("migration.units_moved", "topology_id"),
            gauge("migration.handoffs", "topology_id", "state"),
            gauge("migration.pressure", "scope", "level"),
            histogram("migration.cutover_window_millis", "topology_id"),
            histogram("migration.hook_duration_millis", "hook", "outcome"));

    /** The events of the {@code migration} surface. */
    public static final List<Event> MIGRATION_EVENTS = List.of(
            // LIN-031: the count per class, which is where an operator reads whether an
            // epoch was a refinement or a redistribution. Under 0091 nothing acts on it.
            event("migration.lineage", "info", "unchanged", "moved", "divided", "merged",
                    "fresh", "split", "folded", "vacated"),
            event("migration.planned", "info", "handoffCount", "policy"),
            event("migration.state_changed", "info", "handoff", "shard", "from", "to", "trigger"),
            event("migration.cutover_committed", "info", "shard", "source", "destination",
                    "windowMillis"),
            event("migration.quiesce_expired", "error", "handoff", "shard", "leaseMillis",
                    "marginMillis"),
            event("migration.failed", "error", "shard", "kind", "source", "destination"),
            event("migration.superseded", "warning", "installedEpoch", "abortedCount",
                    "finishingCount"),
            event("migration.rebase_pending", "warning", "installedEpoch", "targetEpoch"),
            event("migration.rebased", "info", "fromEpoch", "toEpoch", "rebased", "aborted",
                    "unchanged"),
            event("migration.reobserved", "info", "handoff", "shard", "answer", "resumedState"));

    /** The metrics of the {@code fencing} surface, which are the ones named {@code fencing.}. */
    public static final List<Metric> FENCING_METRICS = List.of(
            counter("fencing.verdicts", "relation", "ownership", "served"),
            counter("fencing.redirects", "topology_id"));

    /** The events of the {@code fencing} surface. */
    public static final List<Event> FENCING_EVENTS = List.of(
            event("fencing.refused", "warning", "relation", "ownership", "currentOwner", "token"));

    /** The metrics of the {@code failover} surface, which are the ones named {@code health.}. */
    public static final List<Metric> FAILOVER_METRICS = List.of(
            counter("health.transitions", "topology_id", "from", "to"),
            counter("health.ejections", "node"),
            counter("health.ejections_refused", "topology_id"),
            gauge("health.nodes", "topology_id", "state"),
            gauge("health.failure_percent", "node"));

    /** The events of the {@code failover} surface. */
    public static final List<Event> FAILOVER_EVENTS = List.of(
            event("health.transition", "info", "node", "from", "to", "trigger"),
            event("health.ejection_refused", "warning", "node", "ejected", "setSize"));

    /** Every metric of the {@code routing} surface, in the order the specification tables them. */
    public static final List<Metric> METRICS = List.of(
            counter("routing.decisions", "topology_id", "strategy", "outcome"),
            counter("routing.shortfall", "topology_id", "cause"),
            counter("routing.spread_relaxation", "topology_id", "stage"),
            counter("routing.filter_failed_open", "topology_id"),
            counter("routing.override_matched", "topology_id", "mode"),
            counter("routing.errors", "topology_id", "code"),
            counter("attempts.total", "topology_id", "role", "outcome"),
            counter("attempts.exhausted", "topology_id", "cause"),
            counter("attempts.retries_refused", "topology_id"),
            counter("topology.documents", "topology_id", "outcome"),
            counter("topology.weight_clamped", "topology_id"),
            counter("shard.requests", "topology_id", "shard"),
            gauge("topology.epoch", "topology_id"),
            gauge("topology.snapshot_age_millis", "topology_id"),
            gauge("topology.stale", "topology_id"),
            gauge("topology.nodes", "topology_id", "state"),
            gauge("topology.retained_snapshots", "topology_id"),
            gauge("placement.prepared_entries", "topology_id"),
            gauge("balance.observed_share", "topology_id", "node"),
            histogram("routing.duration_millis", "topology_id"),
            histogram("routing.preference_list_length", "topology_id"),
            histogram("routing.replica_count", "topology_id"),
            histogram("topology.prepare_duration_millis", "topology_id"));

    /** The closed label value sets, for the labels that carry one. */
    public static final Map<String, Map<String, List<String>>> LABEL_VALUES = Map.of(
            PREFIX + "routing.decisions", Map.of("outcome", List.of("decided", "failed")),
            PREFIX + "routing.shortfall", Map.of("cause", List.of("nodes", "domains")),
            PREFIX + "routing.override_matched",
            Map.of("mode", List.of("pin", "constrain", "both")),
            PREFIX + "attempts.exhausted",
            Map.of("cause", List.of("preferenceList", "attemptLimit", "retryBudget")),
            PREFIX + "topology.documents",
            Map.of("outcome", List.of("installed", "noop", "unchanged", "rejected")));

    /** Every event of the {@code routing} surface, in the order the specification tables them. */
    public static final List<Event> EVENTS = List.of(
            event("topology.installed", "info",
                    "digest", "nodeCount", "placementSetCount", "prepareDurationMillis"),
            event("topology.rejected", "error", "condition", "rejectedEpoch", "digest"),
            event("topology.unchanged", "info", "digest"),
            event("topology.stale", "warning", "ageMillis", "stalePolicy"),
            event("topology.fresh", "info", "ageMillis"),
            event("topology.provider_error", "error", "condition", "backoffMillis"),
            event("topology.weight_clamped", "warning", "node", "requestedCount", "grantedCount"),
            event("topology.directory_large", "warning", "entryCount", "threshold"),
            event("topology.rendezvous_large", "warning", "nodeCount", "total", "threshold"),
            event("topology.ring_large", "warning", "nodeCount", "total", "threshold"),
            event("topology.spread_infeasible", "warning", "level", "domainCount", "factor",
                    "stage"),
            event("topology.default_seed", "warning", "evidence"),
            event("topology.delta", "info", "shardsChanged", "nodesGained", "nodesLost"),
            event("routing.shortfall", "warning", "factor", "achieved", "cause", "shard", "token"),
            event("routing.spread_relaxed", "info", "relaxedLevels", "stage", "shard", "token"),
            event("routing.filter_failed_open", "warning", "preferenceListLength"),
            event("routing.exhausted", "error", "attempted", "preferenceListLength", "cause",
                    "shard", "token"),
            event("shard.hot", "warning", "shard", "observedShare", "expectedShare"),
            event("shard.key_skew", "warning", "shard", "hottestKeyRequests", "requests"),
            event("observability.bound_reached", "warning",
                    "bounded", "setting", "bound", "count"));

    /** The members every event carries, under {@code OBS-021}. */
    public static final List<String> COMMON_EVENT_MEMBERS =
            List.of("name", "instant", "topologyId", "epoch", "severity");

    /** The closed severity set of {@code OBS-021}. */
    public static final List<String> SEVERITIES = List.of("info", "warning", "error");

    private static Metric counter(String name, String... labels) {
        return new Metric(PREFIX + name, "counter", List.of(labels));
    }

    private static Metric gauge(String name, String... labels) {
        return new Metric(PREFIX + name, "gauge", List.of(labels));
    }

    private static Metric histogram(String name, String... labels) {
        return new Metric(PREFIX + name, "histogram", List.of(labels));
    }

    private static Event event(String name, String severity, String... payload) {
        return new Event(PREFIX + name, severity, List.of(payload));
    }
}
