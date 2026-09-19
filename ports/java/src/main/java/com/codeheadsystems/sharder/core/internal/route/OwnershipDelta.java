package com.codeheadsystems.sharder.core.internal.route;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The ownership delta of {@code TOPO-211}: the shards whose replica set differs between two
 * snapshots, with the nodes gained and lost for each.
 *
 * <p>The delta is computed on demand rather than at installation, under
 * {@code adr/0049}, and it reads the replica prefix of each shard rather than the whole preference
 * list: a node that a shard falls back to has not gained the shard.
 */
public final class OwnershipDelta {

    /** One shard whose replica set moved, with what it gained and lost. */
    public record ShardChange(String shard, List<NodeId> before, List<NodeId> after,
                              List<NodeId> gained, List<NodeId> lost) {
    }

    private OwnershipDelta() {
    }

    /**
     * The member two snapshots differ in where their shard identity is not comparable, under
     * {@code TOPO-231}, and nothing where the delta may be computed.
     *
     * <p>A delta joins two snapshots on shard identifiers, so a difference that renames every
     * shard makes the two incomparable rather than wholly changed.
     */
    public static Optional<String> incomparable(TopologyDocument before, TopologyDocument after) {
        if (!before.strategy().kind().equals(after.strategy().kind())) {
            return Optional.of("strategy.kind");
        }
        if (!before.keyTransform().equals(after.keyTransform())) {
            return Optional.of("keyTransform");
        }
        if (!before.seedEquals(after)) {
            return Optional.of("hash.seed");
        }
        if ("slot".equals(before.strategy().kind())
                && before.strategy().slotCount() != after.strategy().slotCount()) {
            return Optional.of("slotCount");
        }
        return Optional.empty();
    }

    /**
     * The shards whose replica sets differ, in the order {@code TOPO-213} fixes: first the shards
     * the later snapshot enumerates, in its own enumeration order, then the shards only the
     * earlier snapshot enumerates, in that snapshot's enumeration order.
     *
     * <p>A shard the earlier snapshot did not enumerate has an empty before set, and a shard the
     * later one does not enumerate has an empty after set. Neither is empty on both sides, so a
     * shard that vanished reports every node it lost. Where the two snapshots enumerate the same
     * shards, which is every pair under {@code slot} at one {@code slotCount}, the second group is
     * empty and the order is the later snapshot's alone.
     */
    public static List<ShardChange> between(PlacementEngine before, PlacementEngine after) {
        List<ShardChange> changes = new ArrayList<>();
        List<String> later = after.placement().shards();
        Set<String> enumeratedLater = new LinkedHashSet<>(later);
        List<String> ordered = new ArrayList<>(later);
        for (String shard : before.placement().shards()) {
            if (!enumeratedLater.contains(shard)) {
                ordered.add(shard);
            }
        }
        for (String shard : ordered) {
            List<NodeId> was = replicas(before, shard);
            List<NodeId> now = replicas(after, shard);
            if (was.equals(now)) {
                continue;
            }
            // What a shard gained and lost is a set rather than an ordering, so each is reported
            // in ascending node identity under PLACE-020.
            List<NodeId> gained = new ArrayList<>(now);
            gained.removeAll(was);
            gained.sort(NodeId::compareTo);
            List<NodeId> lost = new ArrayList<>(was);
            lost.removeAll(now);
            lost.sort(NodeId::compareTo);
            changes.add(new ShardChange(shard, was, now, List.copyOf(gained), List.copyOf(lost)));
        }
        return List.copyOf(changes);
    }

    /** The replica prefix of one shard: the first entries the factor and the spread admit. */
    public static List<NodeId> replicas(PlacementEngine engine, String shard) {
        List<String> shards = engine.placement().shards();
        if (!shards.contains(shard)) {
            return List.of();
        }
        List<NodeId> candidates =
                engine.placement().candidatesForShard(shard, engine.placementEligible());
        int factor = engine.factor();
        Set<NodeId> prefix = new LinkedHashSet<>(
                engine.ladder().chosen(candidates, factor).selected());
        return List.copyOf(prefix);
    }
}
