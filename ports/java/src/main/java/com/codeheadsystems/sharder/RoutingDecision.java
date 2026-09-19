package com.codeheadsystems.sharder;

import com.codeheadsystems.sharder.core.internal.route.PlacementEngine;
import com.codeheadsystems.sharder.core.internal.snapshot.DocumentSnapshot;
import com.codeheadsystems.sharder.health.HealthState;
import com.codeheadsystems.sharder.observe.ExplainRecord;
import com.codeheadsystems.sharder.topology.FencingToken;
import com.codeheadsystems.sharder.topology.TopologySnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What one routing call answered, under {@code CORE-040}.
 *
 * <p>A decision is immutable and is read against one snapshot from first candidate to last, under
 * {@code CORE-041}, so a caller holding it across an installation reads what was decided rather
 * than what would be decided now.
 *
 * <p>{@code entries} is the materialised prefix of {@code CORE-046} rather than the whole
 * preference list: its length is the lesser of the length of the preference list and the greater of
 * the replica count and the attempt limit, which at factor 3 with the defaults is five entries over
 * a cluster of any size. {@link #preferenceList()} answers the whole list under {@code CORE-047},
 * recomputing it from the snapshot and the routing key, and belongs off the routing path.
 *
 * <p>The decision carries no key, no address, no tags, no override note, and no metadata, under
 * {@code CORE-043}.
 */
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
        Optional<ExplainRecord> explain,
        TopologySnapshot snapshot) {

    /** Every list is copied, so a decision a caller holds changes with nothing. */
    public RoutingDecision {
        entries = List.copyOf(entries);
        ordered = List.copyOf(ordered);
        relaxedLevels = List.copyOf(relaxedLevels);
    }

    /**
     * The head of the preference list, under {@code CORE-042}.
     *
     * <p>It is an accessor over the head of {@code entries} rather than a stored field, so the two
     * cannot disagree.
     */
    public PreferenceEntry primary() {
        if (entries.isEmpty()) {
            throw new IllegalStateException("an empty decision has no primary");
        }
        return entries.get(0);
    }

    /** Whether the candidate ordering was empty. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * The whole preference list, under {@code CORE-047}.
     *
     * <p>It is recomputed from the snapshot and the routing key and allocates its result on each
     * call. An attempt sequence walks past the materialised prefix under {@code FAIL-014} without
     * it.
     */
    public List<PreferenceEntry> preferenceList() {
        if (!(snapshot instanceof DocumentSnapshot document)) {
            return entries;
        }
        PlacementEngine engine = document.engine();
        List<NodeId> whole = engine.route(routingKey.toBytes()).preferenceList();
        List<PreferenceEntry> list = new ArrayList<>(whole.size());
        for (int position = 0; position < whole.size(); position++) {
            NodeId node = whole.get(position);
            PreferenceEntry known = position < entries.size() ? entries.get(position) : null;
            HealthState health = known != null && known.node().equals(node)
                    ? known.health() : HealthState.UNKNOWN;
            boolean attemptable = known != null && known.node().equals(node)
                    ? known.attemptable() : health.attemptable();
            list.add(new PreferenceEntry(node, position,
                    position < replicaCount ? Role.REPLICA : Role.FALLBACK, health, attemptable));
        }
        return List.copyOf(list);
    }
}
