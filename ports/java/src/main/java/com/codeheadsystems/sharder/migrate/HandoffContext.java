package com.codeheadsystems.sharder.migrate;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.ShardId;

/**
 * What one hook call is told about the handoff it is advancing, under {@code MOVE-111}.
 *
 * <p>{@code toEpoch} is the handoff's own target epoch rather than the plan's: a handoff that
 * entered {@code cutover} keeps the epoch it held at that transition and runs to a terminal state
 * under it, under {@code MOVE-099}, so the two differ after a rebase.
 */
public record HandoffContext(ShardId shardId, ShardId sourceShardId, String topologyId,
                             long fromEpoch, long toEpoch, NodeId source, NodeId destination,
                             int attempt, int deadlineMillis) {

    /**
     * The context of a handoff whose contents come from the shard itself.
     *
     * <p>{@code sourceShardId} differs from {@code shardId} only where a lineage divided or folded
     * an extent, under {@code LIN-041}. A hook that read {@code shardId} alone would search the
     * source for a shard the source does not hold.
     */
    public HandoffContext(ShardId shardId, String topologyId, long fromEpoch, long toEpoch,
                          NodeId source, NodeId destination, int attempt, int deadlineMillis) {
        this(shardId, shardId, topologyId, fromEpoch, toEpoch, source, destination, attempt,
                deadlineMillis);
    }
}
