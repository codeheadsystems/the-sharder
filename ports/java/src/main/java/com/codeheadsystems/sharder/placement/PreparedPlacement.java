package com.codeheadsystems.sharder.placement;

import com.codeheadsystems.sharder.NodeSet;
import com.codeheadsystems.sharder.RoutingKey;
import com.codeheadsystems.sharder.ShardId;
import java.util.Iterator;
import java.util.Optional;

/**
 * One snapshot's strategy, prepared once at installation and read by every routing call.
 *
 * <p>Preparation is where a ring is built and an assignment is indexed, under {@code CORE-011}, so
 * that a routing call performs the hashing and the walk and nothing else. A prepared placement is
 * immutable and safe to read from any number of units of execution.
 */
public interface PreparedPlacement {

    /** The shard the key belongs to, where the strategy names shards, under {@code PLACE-030}. */
    Optional<ShardId> shardOf(RoutingKey routingKey);

    /** The candidate ordering for the key over {@code eligible}, under {@code CORE-012}. */
    CandidateCursor candidates(RoutingKey routingKey, NodeSet eligible);

    /** Every shard the strategy names, under {@code PLACE-031}. */
    Iterator<ShardId> shards();

    /** The candidate ordering for a shard, which a delta and a plan read. */
    CandidateCursor candidatesForShard(ShardId shard, NodeSet eligible);
}
