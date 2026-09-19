package com.codeheadsystems.sharder.migrate;

import com.codeheadsystems.sharder.error.InvalidArgumentException;
import java.util.Optional;

/**
 * What bounds a migration, under {@code RATE-011} and {@code CFG-050}.
 *
 * <p>A policy is supplied to {@code plan} rather than to the router, because it belongs to one
 * migration rather than to a caller's routing. Every value is validated at construction, under
 * {@code RATE-021}: a step budget of zero admits nothing, and a re-transfer threshold at or below
 * the catch-up threshold would return a handoff to {@code transferring} at the moment it reached
 * {@code cutover}.
 */
public record MigrationPolicy(
        int maxConcurrentHandoffs,
        int maxConcurrentPerSourceNode,
        int maxConcurrentPerDestinationNode,
        int initialStepBudget,
        int stepDeadlineMillis,
        int maxAttemptsPerStep,
        int retryBackoffBaseMillis,
        int retryBackoffCapMillis,
        int commitDeadlineMillis,
        int quiesceLeaseMarginMillis,
        long catchUpResidualThreshold,
        long reTransferResidualThreshold,
        Optional<PressureGauge> pressureGauge) {

    /** The refusals of {@code RATE-021}, evaluated at construction. */
    public MigrationPolicy {
        if (pressureGauge == null) {
            throw new InvalidArgumentException("an absent gauge is Optional.empty()");
        }
        atLeast("maxConcurrentHandoffs", maxConcurrentHandoffs, 1);
        atLeast("maxConcurrentPerSourceNode", maxConcurrentPerSourceNode, 1);
        atLeast("maxConcurrentPerDestinationNode", maxConcurrentPerDestinationNode, 1);
        atLeast("initialStepBudget", initialStepBudget, 1);
        atLeast("stepDeadlineMillis", stepDeadlineMillis, 0);
        atLeast("maxAttemptsPerStep", maxAttemptsPerStep, 1);
        atLeast("retryBackoffBaseMillis", retryBackoffBaseMillis, 1);
        atLeast("retryBackoffCapMillis", retryBackoffCapMillis, retryBackoffBaseMillis);
        atLeast("commitDeadlineMillis", commitDeadlineMillis, 0);
        atLeast("quiesceLeaseMarginMillis", quiesceLeaseMarginMillis, 0);
        // RATE-021: the thresholds are unsigned, and the re-transfer threshold sits above the
        // catch-up threshold or the two describe a handoff that never settles.
        if (Long.compareUnsigned(reTransferResidualThreshold, catchUpResidualThreshold) <= 0) {
            throw new InvalidArgumentException(
                    "reTransferResidualThreshold is above catchUpResidualThreshold");
        }
    }

    /** The defaults of {@code CFG-050}. */
    public static MigrationPolicy defaults() {
        return new MigrationPolicy(4, 1, 1, 1, 30000, 5, 1000, 60000, 30000, 1000, 0L, -1L,
                Optional.empty());
    }

    /** The same policy under a gauge. */
    public MigrationPolicy withPressureGauge(PressureGauge gauge) {
        return new MigrationPolicy(maxConcurrentHandoffs, maxConcurrentPerSourceNode,
                maxConcurrentPerDestinationNode, initialStepBudget, stepDeadlineMillis,
                maxAttemptsPerStep, retryBackoffBaseMillis, retryBackoffCapMillis,
                commitDeadlineMillis, quiesceLeaseMarginMillis, catchUpResidualThreshold,
                reTransferResidualThreshold, Optional.of(gauge));
    }

    /** The same policy under one step budget. */
    public MigrationPolicy withInitialStepBudget(int budget) {
        return new MigrationPolicy(maxConcurrentHandoffs, maxConcurrentPerSourceNode,
                maxConcurrentPerDestinationNode, budget, stepDeadlineMillis, maxAttemptsPerStep,
                retryBackoffBaseMillis, retryBackoffCapMillis, commitDeadlineMillis,
                quiesceLeaseMarginMillis, catchUpResidualThreshold, reTransferResidualThreshold,
                pressureGauge);
    }

    /** The same policy under one concurrency bound across the plan. */
    public MigrationPolicy withMaxConcurrentHandoffs(int bound) {
        return new MigrationPolicy(bound, maxConcurrentPerSourceNode,
                maxConcurrentPerDestinationNode, initialStepBudget, stepDeadlineMillis,
                maxAttemptsPerStep, retryBackoffBaseMillis, retryBackoffCapMillis,
                commitDeadlineMillis, quiesceLeaseMarginMillis, catchUpResidualThreshold,
                reTransferResidualThreshold, pressureGauge);
    }

    /**
     * The backoff of {@code RATE-051} for one attempt.
     *
     * <p>The shift is clamped before it is applied, so it never overflows, and the doubling stops
     * at the cap.
     */
    public long retryBackoffMillis(int attempt) {
        int shift = Math.min(Math.max(attempt - 1, 0), 20);
        return Math.min((long) retryBackoffBaseMillis << shift, retryBackoffCapMillis);
    }

    private static void atLeast(String name, long value, long floor) {
        if (value < floor) {
            throw new InvalidArgumentException(name + " is at least " + floor + ", not " + value);
        }
    }
}
