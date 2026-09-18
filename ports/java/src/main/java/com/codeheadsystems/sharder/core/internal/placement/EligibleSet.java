package com.codeheadsystems.sharder.core.internal.placement;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.Node;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The eligible node set of {@code PLACE-003}: the placement set, filtered by the constraint of the
 * matched override where one applies.
 *
 * <p>It is computed before the strategy runs, and the strategy is presented with no node outside
 * it, under {@code PLACE-004}. The set carries no order of its own: every ordering over it is
 * computed by a rule the specification states, under {@code CORE-002}.
 */
public final class EligibleSet {

    private final Set<NodeId> identities;

    private EligibleSet(Set<NodeId> identities) {
        this.identities = identities;
    }

    /** The eligible set holding exactly these nodes. */
    public static EligibleSet of(List<Node> nodes) {
        Set<NodeId> identities = new LinkedHashSet<>();
        nodes.forEach(node -> identities.add(node.id()));
        return new EligibleSet(identities);
    }

    /** Whether the identity is eligible. */
    public boolean contains(NodeId id) {
        return identities.contains(id);
    }

    /** The count of eligible identities. */
    public int size() {
        return identities.size();
    }

    /** Whether no identity is eligible. */
    public boolean isEmpty() {
        return identities.isEmpty();
    }
}
