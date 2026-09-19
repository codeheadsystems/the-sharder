package com.codeheadsystems.sharder.core.internal.fence;

import com.codeheadsystems.sharder.NodeId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The redirect walk of {@code FENCE-221}.
 *
 * <p>A caller refused with a {@code currentOwner} may retry against that node. The walk holds the
 * identities already attempted for the request, the bound of {@code CFG-040}, and the retry budget,
 * and it refuses in the order {@code FENCE-221} writes: the bound, an identity already attempted,
 * an identity the snapshot does not hold, and last the budget. A walk refused for one of the first
 * three reasons is not accounted against the budget.
 */
public final class RedirectWalk {

    /** Why a walk stopped, which is the cause {@code ERR-043} carries. */
    public record Refusal(String cause) {
    }

    private RedirectWalk() {
    }

    /**
     * Whether the walk may follow one more redirect to {@code owner}.
     *
     * @param followed how many redirects the walk has followed already
     * @param maxRedirects the bound of {@code FENCE-181}
     * @param attempted the identities already attempted for this request
     * @param owner the identity the refusal named
     * @param known the identities the caller's own snapshot holds, which include nodes outside
     *     the placement set
     * @param budgetPermits whether the retry budget admits a further retry
     */
    public static Optional<Refusal> refusal(int followed, int maxRedirects, Set<NodeId> attempted,
                                            NodeId owner, List<NodeId> known,
                                            boolean budgetPermits) {
        if (followed >= maxRedirects) {
            return Optional.of(new Refusal("boundReached"));
        }
        if (attempted.contains(owner)) {
            return Optional.of(new Refusal("revisitedNode"));
        }
        if (!known.contains(owner)) {
            return Optional.of(new Refusal("unknownNode"));
        }
        if (!budgetPermits) {
            return Optional.of(new Refusal("retryBudget"));
        }
        return Optional.empty();
    }
}
