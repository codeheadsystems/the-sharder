package com.codeheadsystems.sharder.core.internal.route;

import com.codeheadsystems.sharder.NodeId;
import java.util.List;
import java.util.Optional;

/**
 * What one routing call answers at the {@code place} conformance level.
 *
 * <p>The shape follows the routing decision of {@code CORE-040}, less the members that belong to
 * surfaces the port has not reached: there is no health state on an entry, no ordering after read
 * affinity, and no explain record. The public {@code RoutingDecision} arrives with the document
 * pipeline, and this record is what the placement engine answers until then.
 */
public record PlacementDecision(
        byte[] routingKey,
        Optional<String> shard,
        String topologyId,
        long epoch,
        int factor,
        int replicaCount,
        List<NodeId> candidates,
        List<NodeId> preferenceList,
        int materialisedEntries,
        List<String> relaxedLevels,
        int spreadStage,
        String shortfall,
        Optional<MatchedOverride> matchedOverride,
        int attemptLimit) {

    /** The override that matched the routing key, and how it composed with the strategy. */
    public record MatchedOverride(int index, String mode) {
    }

    /** The role of {@code REPL-017}: a replica below the achieved count, a fallback above. */
    public String roleAt(int position) {
        return position < replicaCount ? "replica" : "fallback";
    }
}
