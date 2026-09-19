package com.codeheadsystems.sharder.migrate;

/**
 * What one call to {@code step} did, under {@code MOVE-061}.
 *
 * <p>{@code Idle} is what the pressure level or the concurrency bounds leave, and a step never
 * spins, waits, or sleeps to avoid answering it, under {@code RATE-111}.
 */
public sealed interface StepOutcome {

    /** Nothing was admissible. */
    record Idle() implements StepOutcome {
    }

    /** A handoff moved data and stayed in its state. */
    record Progressed(HandoffId id, int unitsMoved) implements StepOutcome {
    }

    /** A handoff changed state. */
    record Advanced(HandoffId id, HandoffState from, HandoffState to) implements StepOutcome {
    }

    /** A handoff asked to be called again no sooner than the interval. */
    record Deferred(HandoffId id, int retryAfterMillis) implements StepOutcome {
    }

    /** A handoff reached a terminal state. */
    record Settled(HandoffId id, HandoffState terminalState) implements StepOutcome {
    }
}
