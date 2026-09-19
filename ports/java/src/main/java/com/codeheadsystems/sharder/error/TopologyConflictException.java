package com.codeheadsystems.sharder.error;

/**
 * {@code ERR-031}: a document conflicts with the snapshot in force.
 *
 * <p>A differing topology identifier and an equal epoch whose digest differs are the two shapes of
 * the conflict, and both are a publishing fault rather than a race.
 */
public final class TopologyConflictException extends TopologyException {

    private static final long serialVersionUID = 1L;

    /** The condition, stating which of the two shapes the document took. */
    public TopologyConflictException(String message) {
        super(ErrorCode.TOPOLOGY_CONFLICT, null, message);
    }
}
