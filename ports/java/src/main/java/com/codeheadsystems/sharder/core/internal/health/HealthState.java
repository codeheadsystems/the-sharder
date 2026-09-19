package com.codeheadsystems.sharder.core.internal.health;

/**
 * The health state of a node, under {@code HEALTH-001}.
 *
 * <p>The five names stay disjoint from the administrative states of a topology document, under
 * {@code HEALTH-003}: one vocabulary describes what an authority published and the other what a
 * caller has observed, and neither maps onto the other.
 */
public enum HealthState {
    /** No signal has been ingested for the node. */
    UNKNOWN("unknown"),
    /** The node is answering. */
    AVAILABLE("available"),
    /** The node has failed and has not yet met a threshold that ejects it. */
    SUSPECT("suspect"),
    /** The node was ejected and its ejection interval has elapsed. */
    PROBATION("probation"),
    /** The node was ejected and is skipped by the health filter. */
    UNAVAILABLE("unavailable");

    private final String spelling;

    HealthState(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling a vector, an event, and a metric label join on. */
    public String spelling() {
        return spelling;
    }

    /** Whether the health filter leaves such an entry attemptable, under {@code HEALTH-005}. */
    public boolean attemptable() {
        return this != UNAVAILABLE;
    }

    /** The state that spelling names. */
    public static HealthState of(String spelling) {
        for (HealthState state : values()) {
            if (state.spelling.equals(spelling)) {
                return state;
            }
        }
        throw new IllegalArgumentException("no health state named " + spelling);
    }
}
