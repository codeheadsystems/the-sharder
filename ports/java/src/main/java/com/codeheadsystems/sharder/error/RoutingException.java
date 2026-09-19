package com.codeheadsystems.sharder.error;

/**
 * The conditions of the routing block, which are the codes from 101 to 105 of {@code ERR-010}.
 */
public sealed abstract class RoutingException extends SharderException
        permits NoCandidateException, ExhaustedException, UnreadyException, StaleSnapshotException,
                InvalidArgumentException {

    private static final long serialVersionUID = 1L;

    RoutingException(ErrorCode errorCode, String reason, String message) {
        super(errorCode, reason, message);
    }
}
