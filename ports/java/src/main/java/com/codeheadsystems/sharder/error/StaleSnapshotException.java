package com.codeheadsystems.sharder.error;

/**
 * {@code ERR-024}: the snapshot in force is older than the freshness bound of {@code CFG-011}.
 */
public final class StaleSnapshotException extends RoutingException {

    private static final long serialVersionUID = 1L;

    /** The condition, stating how long the snapshot in force has gone unrefreshed. */
    public StaleSnapshotException(String message) {
        super(ErrorCode.STALE_SNAPSHOT, null, message);
    }
}
