package com.codeheadsystems.sharder.core.internal.route;

import com.codeheadsystems.sharder.NodeId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What one routing call answers at the {@code place} and {@code core} conformance levels.
 *
 * <p>The shape follows the routing decision of {@code CORE-040}, less the members that belong to
 * surfaces the port has not reached: there is no health state on an entry, no ordering after read
 * affinity, and no explain record.
 *
 * <p>The decision carries the materialised prefix of {@code CORE-046} and nothing beyond it. The
 * whole candidate ordering and the whole preference list are answered on demand, under
 * {@code CORE-047}, so a topology of a thousand nodes costs a routing call a prefix rather than a
 * thousand entries.
 */
public final class PlacementDecision {

    /** The override that matched the routing key, and how it composed with the strategy. */
    public record MatchedOverride(int index, String mode) {
    }

    private final byte[] routingKey;
    private final Optional<String> shard;
    private final String topologyId;
    private final long epoch;
    private final int factor;
    private final List<NodeId> replicas;
    private final List<NodeId> materialised;
    private final List<String> relaxedLevels;
    private final int spreadStage;
    private final String shortfall;
    private final Optional<MatchedOverride> matchedOverride;
    private final int attemptLimit;
    private final Supplier<List<NodeId>> ordering;

    PlacementDecision(byte[] routingKey, Optional<String> shard, String topologyId, long epoch,
                      int factor, List<NodeId> replicas, List<NodeId> materialised,
                      List<String> relaxedLevels, int spreadStage, String shortfall,
                      Optional<MatchedOverride> matchedOverride, int attemptLimit,
                      Supplier<List<NodeId>> ordering) {
        this.routingKey = routingKey;
        this.shard = shard;
        this.topologyId = topologyId;
        this.epoch = epoch;
        this.factor = factor;
        this.replicas = replicas;
        this.materialised = materialised;
        this.relaxedLevels = relaxedLevels;
        this.spreadStage = spreadStage;
        this.shortfall = shortfall;
        this.matchedOverride = matchedOverride;
        this.attemptLimit = attemptLimit;
        this.ordering = ordering;
    }

    /** The octets the key transform produced. */
    public byte[] routingKey() {
        return routingKey;
    }

    /** The shard the routing key belongs to, where the strategy names one. */
    public Optional<String> shard() {
        return shard;
    }

    /** The identifier of the topology the decision was taken against. */
    public String topologyId() {
        return topologyId;
    }

    /** The epoch the decision was taken at. */
    public long epoch() {
        return epoch;
    }

    /** The effective replication factor. */
    public int factor() {
        return factor;
    }

    /** The achieved replica count, which a shortfall makes lower than the factor. */
    public int replicaCount() {
        return replicas.size();
    }

    /** The count of entries the decision materialised, which {@code CORE-046} bounds. */
    public int materialisedEntries() {
        return materialised.size();
    }

    /** The materialised prefix of the preference list. */
    public List<NodeId> entries() {
        return materialised;
    }

    /** The levels the chosen stage does not enforce, under {@code SPREAD-015}. */
    public List<String> relaxedLevels() {
        return relaxedLevels;
    }

    /** The relaxation stage the policy chose. */
    public int spreadStage() {
        return spreadStage;
    }

    /** The limiting cause of a shortfall, or {@code none}. */
    public String shortfall() {
        return shortfall;
    }

    /** The override that matched, where one did. */
    public Optional<MatchedOverride> matchedOverride() {
        return matchedOverride;
    }

    /** The attempt limit the decision resolved under {@code CORE-048}. */
    public int attemptLimit() {
        return attemptLimit;
    }

    /** The whole candidate ordering, computed on demand. */
    public List<NodeId> candidates() {
        return ordering.get();
    }

    /**
     * The whole preference list of {@code REPL-014}, computed on demand under {@code CORE-047}.
     *
     * <p>The replica prefix comes first and the fallback tail is the rest of the candidate ordering
     * in its own order, so an entry skipped for spread appears in the tail at the position it held
     * in the ordering.
     */
    public List<NodeId> preferenceList() {
        List<NodeId> preference = new ArrayList<>(replicas);
        for (NodeId candidate : ordering.get()) {
            if (!replicas.contains(candidate)) {
                preference.add(candidate);
            }
        }
        return List.copyOf(preference);
    }

    /** The role of {@code REPL-017}: a replica below the achieved count, a fallback above. */
    public String roleAt(int position) {
        return position < replicas.size() ? "replica" : "fallback";
    }
}
