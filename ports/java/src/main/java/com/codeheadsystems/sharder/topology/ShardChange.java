package com.codeheadsystems.sharder.topology;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.ShardId;
import java.util.List;

/**
 * One shard whose replica set moved between two snapshots, under {@code TOPO-211}.
 *
 * <p>{@code before} and {@code after} hold the entries whose role is {@code REPLICA}, which is the
 * achieved replica prefix rather than the whole preference list: a node a shard falls back to has
 * not gained the shard.
 */
public record ShardChange(ShardId shard, List<NodeId> before, List<NodeId> after,
                          List<NodeId> gained, List<NodeId> lost) {

    /** Every list is copied, so a caller holds a change across an installation unchanged. */
    public ShardChange {
        before = List.copyOf(before);
        after = List.copyOf(after);
        gained = List.copyOf(gained);
        lost = List.copyOf(lost);
    }
}
