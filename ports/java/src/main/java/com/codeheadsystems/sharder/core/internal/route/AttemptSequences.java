package com.codeheadsystems.sharder.core.internal.route;

import com.codeheadsystems.sharder.health.HealthView;

/**
 * The attempt walk over one routing decision.
 *
 * <p>The walk is drawn from the whole preference list under {@code FAIL-014}, which
 * {@code CORE-047} answers on demand, rather than from the materialised prefix, whose length
 * {@code CORE-046} fixes and which continuing must not change.
 */
public final class AttemptSequences {

    private AttemptSequences() {
    }

    /** The walk a caller holding this decision and this health view performs. */
    public static DefaultAttemptSequence of(PlacementDecision decision, HealthView health,
                                     RetryBudget budget) {
        return over(decision.preferenceList(), decision.attemptLimit(), health, budget);
    }

    /** The walk over a list a caller supplies, which a scenario confines to a prefix. */
    public static DefaultAttemptSequence over(java.util.List<com.codeheadsystems.sharder.NodeId> list,
                                       int attemptLimit, HealthView health,
                                       RetryBudget budget) {
        return new DefaultAttemptSequence(list, health, budget, attemptLimit);
    }
}
