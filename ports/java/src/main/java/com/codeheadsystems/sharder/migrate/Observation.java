package com.codeheadsystems.sharder.migrate;

import java.util.Optional;

/**
 * What the integrator's durable state says about one handoff, under {@code MOVE-111}.
 *
 * <p>{@code MOVE-211} maps an observation to the state a handoff resumes in, and that mapping is
 * what makes the integrator's state the authority rather than the coordinator's own, under
 * {@code MOVE-201}.
 */
public record Observation(Optional<CutoverRecord> cutoverRecord, boolean destinationPrepared,
                          boolean sourceQuiesced, boolean sourceResidue) {

    /** An observation that found no cutover record. */
    public static Observation withoutRecord(boolean destinationPrepared, boolean sourceQuiesced,
                                            boolean sourceResidue) {
        return new Observation(Optional.empty(), destinationPrepared, sourceQuiesced,
                sourceResidue);
    }
}
