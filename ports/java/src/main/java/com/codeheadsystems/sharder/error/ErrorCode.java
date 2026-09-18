package com.codeheadsystems.sharder.error;

/**
 * The closed condition set of {@code ERR-010}.
 *
 * <p>Each condition carries a permanent numeric code, a permanent name, and a fixed retryable flag.
 * The code and the name are what a metric label, a log line, a serialised error, and a conformance
 * vector join on under {@code ERR-002}, so {@link #errorName()} answers the spelling of the
 * specification rather than the constant's own name and a rename of a constant cannot reach the
 * wire.
 *
 * <p>{@code ERR-062} closes the set: a binding may group these conditions and may not add one.
 */
public enum ErrorCode {

    /** The candidate ordering is empty. */
    NO_CANDIDATE(101, "noCandidate", false),
    /** The attempt sequence ran out after at least one attempt. */
    EXHAUSTED(102, "exhausted", true),
    /** No snapshot is in force. */
    UNREADY(103, "unready", true),
    /** The snapshot in force is stale and the policy is refuse. */
    STALE_SNAPSHOT(104, "staleSnapshot", true),
    /** A caller-supplied argument is outside the contract. */
    INVALID_ARGUMENT(105, "invalidArgument", false),
    /** A document failed schema or semantic validation. */
    INVALID_TOPOLOGY(201, "invalidTopology", false),
    /** A differing identifier, or an equal epoch with a new digest. */
    TOPOLOGY_CONFLICT(202, "topologyConflict", false),
    /** An arriving epoch is below the epoch in force or below the configured minimum. */
    STALE_DOCUMENT(203, "staleDocument", false),
    /** The provider failed to deliver a document. */
    PROVIDER_ERROR(204, "providerError", true),
    /** The recipient does not hold the shard for the key. */
    NOT_OWNER(301, "notOwner", true),
    /** The sender is behind or ahead of the recipient. */
    EPOCH_MISMATCH(302, "epochMismatch", true),
    /** The two topology identifiers differ. */
    IDENTITY_MISMATCH(303, "identityMismatch", false),
    /** A redirect walk reached its bound or revisited a node. */
    REDIRECT_EXHAUSTED(304, "redirectExhausted", false),
    /** A plan cannot be built from the two snapshots and the policy. */
    PLAN_REFUSED(401, "planRefused", false),
    /** The shard is inside the cutover window. */
    QUIESCED(402, "quiesced", true),
    /** A handoff reached the failed state. */
    HANDOFF_FAILED(403, "handoffFailed", false);

    private final int code;
    private final String errorName;
    private final boolean retryable;

    ErrorCode(int code, String errorName, boolean retryable) {
        this.code = code;
        this.errorName = errorName;
        this.retryable = retryable;
    }

    /** The permanent numeric code of {@code ERR-010}. */
    public int code() {
        return code;
    }

    /** The permanent name of {@code ERR-010}, in the spelling that document uses. */
    public String errorName() {
        return errorName;
    }

    /** Whether a caller retrying the same call may succeed, under {@code ERR-006}. */
    public boolean retryable() {
        return retryable;
    }

    /** The condition {@code code} names. */
    public static ErrorCode ofCode(int code) {
        for (ErrorCode candidate : values()) {
            if (candidate.code == code) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("no condition carries the code " + code);
    }

    /** The condition {@code name} names, in the spelling of {@code ERR-010}. */
    public static ErrorCode ofName(String name) {
        for (ErrorCode candidate : values()) {
            if (candidate.errorName.equals(name)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("no condition carries the name " + name);
    }
}
