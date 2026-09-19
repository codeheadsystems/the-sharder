package com.codeheadsystems.sharder.migrate;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.ShardId;
import java.util.Arrays;
import java.util.Objects;

/**
 * The durable record that moves authority for one shard, under {@code MOVE-111}.
 *
 * <p>The record is the integrator's, written by {@code commitCutover} and read by {@code observe}.
 * The library compares its shard, its epoch, and its owner, and never interprets {@code opaque},
 * under {@code MOVE-141}.
 */
public record CutoverRecord(ShardId shardId, String topologyId, long epoch, NodeId owner,
                            byte[] opaque) {

    /** Every member is required, and the opaque octets are copied in and copied out. */
    public CutoverRecord {
        Objects.requireNonNull(shardId, "shardId");
        Objects.requireNonNull(topologyId, "topologyId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(opaque, "opaque");
        opaque = opaque.clone();
    }

    /** A record carrying no opaque member. */
    public static CutoverRecord of(ShardId shardId, String topologyId, long epoch, NodeId owner) {
        return new CutoverRecord(shardId, topologyId, epoch, owner, new byte[0]);
    }

    @Override
    public byte[] opaque() {
        return opaque.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CutoverRecord record
                && shardId.equals(record.shardId) && topologyId.equals(record.topologyId)
                && epoch == record.epoch && owner.equals(record.owner)
                && Arrays.equals(opaque, record.opaque);
    }

    @Override
    public int hashCode() {
        int hash = shardId.hashCode();
        hash = hash * 31 + topologyId.hashCode();
        hash = hash * 31 + Long.hashCode(epoch);
        hash = hash * 31 + owner.hashCode();
        return hash * 31 + Arrays.hashCode(opaque);
    }

    @Override
    public String toString() {
        return shardId.asText() + "@" + epoch + " to " + owner.asText();
    }
}
