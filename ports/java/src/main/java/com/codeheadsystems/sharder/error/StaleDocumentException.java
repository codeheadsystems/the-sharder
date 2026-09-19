package com.codeheadsystems.sharder.error;

/**
 * {@code ERR-032}: a document names an epoch below the epoch in force.
 *
 * <p>An epoch is never assigned, incremented, or inferred, under {@code TOPO-081}, so an authority
 * reverting an assignment republishes it at a higher epoch.
 */
public final class StaleDocumentException extends TopologyException {

    private static final long serialVersionUID = 1L;

    /** The condition, stating the epoch the document named and the epoch in force. */
    public StaleDocumentException(String message) {
        super(ErrorCode.STALE_DOCUMENT, null, message);
    }
}
