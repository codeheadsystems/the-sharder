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
public record HandoffContext(ShardId shardId, String topologyId, long fromEpoch, long toEpoch,
                             NodeId source, NodeId destination, int attempt, int deadlineMillis) {
}
