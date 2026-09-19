package com.codeheadsystems.sharder.health;

/**
 * What one attempt against a node did, under {@code HEALTH-020}.
 *
 * <p>{@code failure}, {@code timeout}, and {@code refused} count as failures, and {@code cancelled}
 * counts as neither a success nor a failure: a caller that gave up on its own has observed nothing
 * about the node.
 */
public enum Outcome {

    /** The node answered. */
    SUCCESS("success", true, false),

    /** The node answered with a failure. */
    FAILURE("failure", false, true),

    /** The node did not answer inside the caller's bound. */
    TIMEOUT("timeout", false, true),

    /** The node refused the attempt. */
    REFUSED("refused", false, true),

    /** The caller abandoned the attempt, so the node was not observed. */
    CANCELLED("cancelled", false, false);

    private final String spelling;
    private final boolean success;
    private final boolean failure;

    Outcome(String spelling, boolean success, boolean failure) {
        this.spelling = spelling;
        this.success = success;
        this.failure = failure;
    }

    /** The spelling a vector, an event, and a metric label join on. */
    public String spelling() {
        return spelling;
    }

    /** Whether the outcome counts as a success in the sliding window. */
    public boolean success() {
        return success;
    }

    /** Whether the outcome counts as a failure in the sliding window. */
    public boolean failure() {
        return failure;
    }

    /** The outcome that spelling names. */
    public static Outcome of(String spelling) {
        for (Outcome outcome : values()) {
            if (outcome.spelling.equals(spelling)) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("no outcome named " + spelling);
    }
}
