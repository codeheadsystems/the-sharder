package com.codeheadsystems.sharder.error;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.topology.FencingToken;

/**
 * {@code ERR-040}: the recipient does not own the key at the token's epoch.
 *
 * <p>The condition carries the current owner, which is what makes a redirect possible rather than
 * a retry at the same node.
 */
public final class NotOwnerException extends RecipientException {

    private static final long serialVersionUID = 1L;

    private final transient NodeId currentOwner;

    /** The condition, with the owner the recipient computed. */
    public NotOwnerException(NodeId currentOwner, FencingToken token, ShardId shard) {
        super(ErrorCode.NOT_OWNER, null, "the key belongs to " + currentOwner.asText(), token,
                shard);
        this.currentOwner = currentOwner;
    }

    /** The node the recipient computed as the owner, under {@code ERR-040}. */
    public NodeId currentOwner() {
        return currentOwner;
    }
}
