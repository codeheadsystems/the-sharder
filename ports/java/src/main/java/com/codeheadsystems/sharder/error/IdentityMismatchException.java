package com.codeheadsystems.sharder.error;

import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.topology.FencingToken;

/**
 * {@code ERR-044}: the token names a topology the recipient does not serve.
 *
 * <p>The condition is not retryable at any node: two topologies sharing a transport is a
 * misconfiguration rather than a race.
 */
public final class IdentityMismatchException extends RecipientException {

    private static final long serialVersionUID = 1L;

    /** The condition, with the token whose identifier the recipient refused. */
    public IdentityMismatchException(FencingToken token, ShardId shard) {
        super(ErrorCode.IDENTITY_MISMATCH, null,
                "the recipient does not serve " + token.topologyId(), token, shard);
    }
}
