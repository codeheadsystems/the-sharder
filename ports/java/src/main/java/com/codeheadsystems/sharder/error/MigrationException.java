package com.codeheadsystems.sharder.error;

import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.topology.FencingToken;

/**
 * The conditions of the migration block, which are the codes from 401 to 403 of {@code ERR-010}.
 */
public sealed abstract class MigrationException extends SharderException
        permits PlanRefusedException, QuiescedException, HandoffFailedException {

    private static final long serialVersionUID = 1L;

    MigrationException(ErrorCode errorCode, String reason, String message, FencingToken token,
                       ShardId shard) {
        super(errorCode, reason, message, token, shard, null);
    }
}
