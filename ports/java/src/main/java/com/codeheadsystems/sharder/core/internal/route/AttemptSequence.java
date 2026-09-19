package com.codeheadsystems.sharder.core.internal.route;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.health.HealthView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The attempt walk of {@code FAIL-023}.
 *
 * <p>The attempt sequence is the subsequence of the preference list a caller's health view leaves
 * attemptable, in preference list order, under {@code FAIL-003}. Nothing is reordered and nothing
 * healthier is promoted: the preference list is agreed between callers and the walk over it is
 * local.
 *
 * <p>The walk is drawn from the whole preference list rather than from the materialised prefix,
 * under {@code FAIL-014}, so an entry the filter skips is replaced from further down the list
 * without changing what the decision materialised.
 */
public final class AttemptSequence {

    private final List<NodeId> sequence;
    private final HealthView health;
    private final RetryBudget budget;
    private final int limit;
    private final List<NodeId> attempted = new ArrayList<>();
    private int position;
    private boolean failedOpen;

    AttemptSequence(List<NodeId> preferenceList, HealthView health, RetryBudget budget, int limit) {
        List<NodeId> attemptable = new ArrayList<>();
        for (NodeId node : preferenceList) {
            if (health.attemptable(node)) {
                attemptable.add(node);
            }
        }
        // FAIL-012: the filter never yields an empty sequence from a non-empty preference list.
        // Where every entry is skipped the sequence is the whole list, in its own order, and the
        // decision records that the filter failed open.
        if (attemptable.isEmpty() && !preferenceList.isEmpty()) {
            attemptable = new ArrayList<>(preferenceList);
            this.failedOpen = true;
        }
        this.sequence = List.copyOf(attemptable);
        this.health = health;
        this.budget = budget;
        // FAIL-022: the limit in force for the walk is the resolved limit clamped to the length of
        // the attempt sequence. The clamp applies to the walk alone.
        this.limit = Math.min(limit, this.sequence.size());
    }

    /** The attempt sequence, which is what a caller walks. */
    public List<NodeId> sequence() {
        return sequence;
    }

    /** Whether the filter failed open, under {@code FAIL-012}. */
    public boolean filterFailedOpen() {
        return failedOpen;
    }

    /** The attempts remaining before the limit is reached. */
    public int remaining() {
        return Math.max(0, limit - attempted.size());
    }

    /** The identities attempted so far, in order. */
    public List<NodeId> attempted() {
        return List.copyOf(attempted);
    }

    /**
     * The next attemptable entry, or nothing where the walk is exhausted.
     *
     * <p>A probe is consumed once for each attempt a caller places, under {@code HEALTH-017}: an
     * entry in {@code probation} the walk reaches is answered where {@code admitProbe} admits it
     * and passed over where it does not. Where the walk reaches the end having answered nothing
     * because every probe was declined, it answers the first entry rather than exhaustion, under
     * {@code FAIL-015}, so a caller always holds one node to attempt.
     */
    public Optional<NodeId> next(long at) {
        if (attempted.size() >= limit) {
            return Optional.empty();
        }
        boolean retry = !attempted.isEmpty();
        if (retry && !budget.permitted(at)) {
            // FAIL-034: the budget refusing a retry exhausts the walk, and names itself the cause.
            return Optional.empty();
        }
        while (position < sequence.size()) {
            NodeId candidate = sequence.get(position++);
            if (health.stateOf(candidate) == com.codeheadsystems.sharder.core.internal.health
                    .HealthState.PROBATION && !health.admitProbe(candidate)) {
                continue;
            }
            budget.account(at, retry);
            attempted.add(candidate);
            return Optional.of(candidate);
        }
        if (attempted.isEmpty() && !sequence.isEmpty()) {
            NodeId first = sequence.get(0);
            budget.account(at, false);
            attempted.add(first);
            return Optional.of(first);
        }
        return Optional.empty();
    }

    /** {@code FAIL-023}: the outcome of one attempt, forwarded to the health view. */
    public void recordOutcome(NodeId node, String outcome, long at) {
        health.report(node, outcome, at);
    }
}
