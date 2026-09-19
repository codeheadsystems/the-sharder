package com.codeheadsystems.sharder.core.internal.fence;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.TopologyLoader;
import com.codeheadsystems.sharder.error.EpochMismatchException;
import com.codeheadsystems.sharder.error.IdentityMismatchException;
import com.codeheadsystems.sharder.error.NotOwnerException;
import com.codeheadsystems.sharder.error.UnreadyException;
import com.codeheadsystems.sharder.fence.Ownership;
import com.codeheadsystems.sharder.fence.RecipientPolicy;
import com.codeheadsystems.sharder.fence.Relation;
import com.codeheadsystems.sharder.fence.Verdict;
import com.codeheadsystems.sharder.topology.FencingToken;
import java.util.Optional;

/**
 * The receiving side of a fenced request, over the snapshots one router holds.
 *
 * <p>The recipient reads the snapshot in force and the snapshots retained under {@code TOPO-161},
 * because {@code FENCE-091} evaluates stable ownership against the preference list at the token's
 * epoch. It never installs a document from a token: a token is evidence about the sender and never
 * a topology update, under {@code FENCE-051}.
 */
public final class DefaultRecipient implements com.codeheadsystems.sharder.fence.Recipient {

    private final TopologyLoader pipeline;
    private final NodeId selfId;
    private final RecipientPolicy policy;

    /** The recipient for one node's own identity under one policy. */
    public DefaultRecipient(TopologyLoader pipeline, NodeId selfId, RecipientPolicy policy) {
        this.pipeline = pipeline;
        this.selfId = selfId;
        this.policy = policy;
    }

    @Override
    public Verdict check(FencingToken token, byte[] routingKey) {
        Recipient.Verdict verdict = Recipient.check(pipeline.engine(), pipeline.retained(),
                token.topologyId(), token.epoch(), routingKey, selfId);
        return new Verdict(Relation.of(verdict.relation()), Ownership.of(verdict.ownership()),
                verdict.ownershipStable(), verdict.currentOwner(),
                verdict.localEpoch().map(epoch -> FencingToken.of(
                        pipeline.snapshot().orElseThrow().topologyId(), epoch)));
    }

    @Override
    public void admit(FencingToken token, byte[] routingKey) {
        Recipient.Verdict verdict = Recipient.check(pipeline.engine(), pipeline.retained(),
                token.topologyId(), token.epoch(), routingKey, selfId);
        Optional<String> refusal = Recipient.refusal(verdict, policy.spelling(), true);
        if (refusal.isEmpty()) {
            return;
        }
        throw switch (refusal.get()) {
            case "identityMismatch" -> new IdentityMismatchException(token, null);
            case "unready" -> new UnreadyException("the recipient holds no snapshot");
            case "notOwner" -> new NotOwnerException(
                    verdict.currentOwner().orElse(selfId), token, null);
            default -> new EpochMismatchException(
                    Relation.SENDER_AHEAD.spelling().equals(verdict.relation())
                            ? EpochMismatchException.Cause.SENDER_AHEAD
                            : EpochMismatchException.Cause.SENDER_BEHIND, token, null);
        };
    }
}
