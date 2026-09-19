package com.codeheadsystems.sharder.fence;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.topology.FencingToken;
import java.util.Optional;

/**
 * What a recipient makes of one token, under {@code FENCE-061}.
 *
 * <p>A verdict is an answer rather than a refusal: {@code Recipient.check} raises nothing, and it
 * is {@code Recipient.admit} that applies the configured policy and raises where the policy
 * refuses.
 *
 * <p>{@code ownershipStable} is the evidence of {@code FENCE-091}: the recipient owned the key at
 * the token's epoch and owns it now, which is what lets a sender behind be served rather than
 * refused.
 */
public record Verdict(Relation relation, Ownership ownership, boolean ownershipStable,
                      Optional<NodeId> currentOwner, Optional<FencingToken> localToken) {
}
