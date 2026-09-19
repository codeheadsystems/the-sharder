package com.codeheadsystems.sharder.core.internal.fence;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.route.PlacementEngine;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The recipient-side check of {@code FENCE-061} through {@code FENCE-101}.
 *
 * <p>The check is a pure function of the token, the routing key, the node identity, and the
 * retained snapshots. It reads no health, no clock, no randomness, and no handoff state, and a
 * received token never changes the recipient's own snapshot: a token is evidence about the sender
 * and never a topology update.
 */
public final class Recipient {

    /** The verdict of {@code FENCE-061}. */
    public record Verdict(String relation, String ownership, boolean ownershipStable,
                          Optional<NodeId> currentOwner, Optional<Long> localEpoch) {
    }

    private Recipient() {
    }

    /**
     * The verdict a recipient reaches, evaluating the rows of {@code FENCE-071} in order.
     *
     * @param inForce the snapshot in force, absent where the recipient holds none
     * @param retained the snapshots retained under {@code TOPO-161}, by epoch
     * @param tokenTopologyId the identifier the token carries
     * @param tokenEpoch the epoch the token carries
     * @param routingKey the key the sender routed
     * @param selfId the recipient's own identity
     */
    public static Verdict check(Optional<PlacementEngine> inForce,
                                Map<Long, PlacementEngine> retained,
                                String tokenTopologyId, long tokenEpoch,
                                byte[] routingKey, NodeId selfId) {
        if (inForce.isEmpty()) {
            // FENCE-083: no snapshot is in force, so no preference list exists.
            return new Verdict("unknownEpoch", "unknown", false, Optional.empty(),
                    Optional.empty());
        }
        PlacementEngine engine = inForce.get();
        long epoch = engine.document().epoch();
        if (!engine.document().topologyId().equals(tokenTopologyId)) {
            // FENCE-082: the routing key was derived under the sender's topology, so the recipient
            // holds no evidence about the sender's shard and reports no owner of its own.
            return new Verdict("identityMismatch", "unknown", false, Optional.empty(),
                    Optional.of(epoch));
        }
        String relation = tokenEpoch == epoch ? "same"
                : tokenEpoch < epoch ? "senderBehind" : "senderAhead";

        List<NodeId> preference = engine.route(routingKey).preferenceList();
        int replicas = engine.route(routingKey).replicaCount();
        boolean owner = preference.subList(0, replicas).contains(selfId);
        Optional<NodeId> currentOwner = preference.isEmpty()
                ? Optional.empty() : Optional.of(preference.get(0));

        boolean stable = false;
        if ("senderBehind".equals(relation)) {
            PlacementEngine at = retained.get(tokenEpoch);
            if (at != null) {
                // FENCE-091: stable where the recipient is a replica under both the retained
                // snapshot and the one in force. An epoch that is not retained is not stable,
                // whatever the ownership in force.
                List<NodeId> then = at.route(routingKey).preferenceList();
                int replicasThen = at.route(routingKey).replicaCount();
                stable = owner && then.subList(0, replicasThen).contains(selfId);
            }
        }
        return new Verdict(relation, owner ? "owner" : "notOwner", stable, currentOwner,
                Optional.of(epoch));
    }

    /**
     * The condition a policy reaches for a verdict, or nothing where the request is served.
     *
     * <p>{@code ERR-045} orders the conditions where more than one holds, so the rows below are
     * evaluated in that order: identity, ownership, the sender ahead, the sender behind.
     */
    public static Optional<String> refusal(Verdict verdict, String policy, boolean fenced) {
        if ("identityMismatch".equals(verdict.relation())) {
            // FENCE-111: epochs under two identifiers are incomparable, so no recovery is
            // available at the recipient.
            return Optional.of("identityMismatch");
        }
        if ("unknownEpoch".equals(verdict.relation())) {
            // FENCE-151: the unready condition rather than the stale one.
            return Optional.of("unready");
        }
        if ("notOwner".equals(verdict.ownership())) {
            return Optional.of("notOwner");
        }
        if ("senderAhead".equals(verdict.relation())) {
            return Optional.of("epochMismatch");
        }
        if (!fenced) {
            // FENCE-131: the policy governs an unfenced request whose ownership is owner.
            return "stable".equals(policy) ? Optional.empty() : Optional.of("epochMismatch");
        }
        if ("senderBehind".equals(verdict.relation())) {
            return "stable".equals(policy) && verdict.ownershipStable()
                    ? Optional.empty() : Optional.of("epochMismatch");
        }
        return Optional.empty();
    }
}
