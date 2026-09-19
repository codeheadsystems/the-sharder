package com.codeheadsystems.sharder.migrate;

/**
 * What one {@code verify} call compared, under {@code MOVE-111}.
 *
 * <p>A mismatch fails the handoff with a kind of {@code unverified} rather than retrying it: the
 * copies differ, and a further comparison of the same copies answers the same thing.
 */
public sealed interface VerifyResult {

    /** The destination copy matches the source. */
    record Matched() implements VerifyResult {
    }

    /** The copies differ. */
    record Mismatched(String detail) implements VerifyResult {
    }

    /** The comparison failed in a way a further attempt may resolve. */
    record Retryable(String reason) implements VerifyResult {
    }
}
