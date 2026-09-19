package com.codeheadsystems.sharder.migrate;

/**
 * What one re-observation of a terminal handoff established, under {@code MOVE-233}.
 *
 * <p>A re-observation is the one call that moves a handoff out of a terminal state, and the
 * integrator makes it for one named handoff rather than the library making it of itself.
 */
public sealed interface ReobserveOutcome {

    /** The handoff resumed in the state the observation maps to, under {@code MOVE-211}. */
    record Resumed(HandoffId id, HandoffState state) implements ReobserveOutcome {
    }

    /** The observation established nothing, so the handoff is untouched. */
    record Unresolved() implements ReobserveOutcome {
    }

    /** The handoff is not one a re-observation applies to. */
    record Refused(String reason) implements ReobserveOutcome {
    }
}
