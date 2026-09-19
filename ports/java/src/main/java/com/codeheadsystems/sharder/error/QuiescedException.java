package com.codeheadsystems.sharder.error;

import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.topology.FencingToken;

/**
 * {@code ERR-051}: the shard is quiesced for a cutover.
 *
 * <p>The condition is retryable: the quiesce window of {@code MOVE-331} is bounded, and a caller
 * that waits it out reaches the shard at its new owner.
 */
public final class QuiescedException extends MigrationException {

    private static final long serialVersionUID = 1L;

    /** The condition, with the shard the cutover holds. */
    public QuiescedException(ShardId shard, FencingToken token) {
        super(ErrorCode.QUIESCED, null, "the shard " + shard.asText() + " is quiesced", token,
                shard);
    }
}
