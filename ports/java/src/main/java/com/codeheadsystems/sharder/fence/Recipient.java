package com.codeheadsystems.sharder.fence;

import com.codeheadsystems.sharder.topology.FencingToken;

/**
 * The receiving side of a fenced request, under {@code FENCE-061}.
 *
 * <p>A recipient is obtained from {@code Router.recipient(selfId)}, because the check reads the
 * snapshot in force and the snapshots retained under {@code TOPO-161}.
 */
public interface Recipient {

    /** What the recipient makes of the token, raising nothing. */
    Verdict check(FencingToken token, byte[] routingKey);

    /**
     * Applies the configured policy to the verdict, under {@code FENCE-111} to {@code FENCE-151}.
     *
     * <p>Where the policy refuses, the recipient condition the verdict produces is raised: one of
     * {@code notOwner}, {@code epochMismatch}, or {@code identityMismatch}.
     */
    void admit(FencingToken token, byte[] routingKey);
}
