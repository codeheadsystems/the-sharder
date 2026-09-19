package com.codeheadsystems.sharder.error;

import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.topology.FencingToken;

/**
 * The conditions of the recipient block, which are the codes from 301 to 304 of {@code ERR-010}.
 *
 * <p>{@code Recipient.check} raises none of them and answers a verdict. {@code Recipient.admit}
 * applies the configured policy and raises the condition the verdict and the policy produce.
 */
public sealed abstract class RecipientException extends SharderException
        permits NotOwnerException, EpochMismatchException, IdentityMismatchException,
                RedirectExhaustedException {

    private static final long serialVersionUID = 1L;

    RecipientException(ErrorCode errorCode, String reason, String message, FencingToken token,
                       ShardId shard) {
        super(errorCode, reason, message, token, shard, null);
    }
}
