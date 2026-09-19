package com.codeheadsystems.sharder.error;

/**
 * {@code ERR-023}: no snapshot is in force.
 *
 * <p>A router answers this from construction until the first document is installed, and a caller
 * retrying the same call succeeds once one is.
 */
public final class UnreadyException extends RoutingException {

    private static final long serialVersionUID = 1L;

    /** The condition, with what the caller was doing when no snapshot was in force. */
    public UnreadyException(String message) {
        super(ErrorCode.UNREADY, null, message);
    }
}
