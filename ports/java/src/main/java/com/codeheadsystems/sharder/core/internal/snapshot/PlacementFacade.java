package com.codeheadsystems.sharder.core.internal.snapshot;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.NodeSet;
import com.codeheadsystems.sharder.RoutingKey;
import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.core.internal.placement.EligibleSet;
import com.codeheadsystems.sharder.placement.CandidateCursor;
import com.codeheadsystems.sharder.placement.PreparedPlacement;
import java.util.Iterator;
import java.util.Optional;

/**
 * The public reading of one prepared strategy.
 *
 * <p>The traversal stays lazy across the boundary: a cursor here advances the internal walk one
 * candidate at a time, under {@code PLACE-015}, rather than draining it into a list the caller
 * reads a prefix of.
 */
public final class PlacementFacade implements PreparedPlacement {

    private final com.codeheadsystems.sharder.core.internal.placement.PreparedPlacement prepared;

    /** The public placement over a prepared one. */
    public PlacementFacade(
            com.codeheadsystems.sharder.core.internal.placement.PreparedPlacement prepared) {
        this.prepared = prepared;
    }

    @Override
    public Optional<ShardId> shardOf(RoutingKey routingKey) {
        return prepared.shardOf(routingKey.toBytes()).map(ShardId::of);
    }

    @Override
    public CandidateCursor candidates(RoutingKey routingKey, NodeSet eligible) {
        return cursor(prepared.cursor(routingKey.toBytes(), eligibleSet(eligible)));
    }

    @Override
    public Iterator<ShardId> shards() {
        Iterator<String> shards = prepared.shardCursor();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return shards.hasNext();
            }

            @Override
            public ShardId next() {
                return ShardId.of(shards.next());
            }
        };
    }

    @Override
    public CandidateCursor candidatesForShard(ShardId shard, NodeSet eligible) {
        return cursor(prepared.cursorForShard(shard.asText(), eligibleSet(eligible)));
    }

    private static EligibleSet eligibleSet(NodeSet eligible) {
        return EligibleSet.ofIdentities(eligible.asList());
    }

    /**
     * The cursor over an internal walk.
     *
     * <p>{@code CandidateCursor} advances and then reads, and an {@code Iterator} reads and then
     * advances, so the current candidate is held here for the one call that reads it.
     */
    private static CandidateCursor cursor(Iterator<NodeId> walk) {
        return new CandidateCursor() {
            private NodeId current;

            @Override
            public boolean advance() {
                if (!walk.hasNext()) {
                    current = null;
                    return false;
                }
                current = walk.next();
                return true;
            }

            @Override
            public NodeId node() {
                if (current == null) {
                    throw new IllegalStateException("advance() answered false or was not called");
                }
                return current;
            }
        };
    }
}
