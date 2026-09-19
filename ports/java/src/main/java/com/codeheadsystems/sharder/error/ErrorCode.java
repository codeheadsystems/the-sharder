package com.codeheadsystems.sharder.error;

import java.util.List;

/**
 * The closed condition set of {@code ERR-010}.
 *
 * <p>Each condition carries a permanent numeric code, a permanent name, a fixed retryable flag, the
 * condition it names, and the response {@code ERR-011} requires of a caller. The code and the name
 * are what a metric label, a log line, a serialised error, and a conformance vector join on under
 * {@code ERR-002}, so {@link #errorName()} answers the spelling of the specification rather than
 * the constant's own name and a rename of a constant cannot reach the wire.
 *
 * <p>{@code ERR-062} closes the set: a binding may group these conditions and may not add one.
 */
public enum ErrorCode {

    /** The candidate ordering is empty. */
    NO_CANDIDATE(101, "noCandidate", "no",
            "the candidate ordering is empty",
            "inspect the topology; the same key answers the same way"),
    /** The attempt sequence ran out after at least one attempt. */
    EXHAUSTED(102, "exhausted", "yes",
            "the attempt sequence ran out after at least one attempt",
            "back off, then retry; a retryBudget cause means the cluster is shedding"),
    /** No snapshot is in force. */
    UNREADY(103, "unready", "yes",
            "no snapshot is in force",
            "wait for a first document, bounded by initialTimeoutMillis"),
    /** The snapshot in force is stale and the policy is refuse. */
    STALE_SNAPSHOT(104, "staleSnapshot", "yes",
            "the snapshot in force is stale and the policy is refuse",
            "wait for the provider, or serve the request from another region"),
    /** A caller-supplied argument is outside the contract. */
    INVALID_ARGUMENT(105, "invalidArgument", "no",
            "a caller-supplied argument is outside the contract",
            "correct the call"),
    /** A document failed schema or semantic validation. */
    INVALID_TOPOLOGY(201, "invalidTopology", "no",
            "a document failed schema or semantic validation",
            "fix the document at the authority; the snapshot in force is unchanged"),
    /** A differing identifier, or an equal epoch with a new digest. */
    TOPOLOGY_CONFLICT(202, "topologyConflict", "no",
            "a differing identifier, or an equal epoch with a new digest",
            "an authority defect; two writers are publishing one identifier"),
    /** An arriving epoch is below the epoch in force or below the configured minimum. */
    STALE_DOCUMENT(203, "staleDocument", "no",
            "an arriving epoch is below the epoch in force or below minEpoch",
            "none at the caller; the provider is serving a lagging replica"),
    /** The provider failed to deliver a document. */
    PROVIDER_ERROR(204, "providerError", "yes",
            "the provider failed to deliver a document",
            "none; the snapshot in force stays in force and the backoff applies"),
    /** The recipient does not hold the shard for the key. */
    NOT_OWNER(301, "notOwner", "at another node",
            "the recipient does not hold the shard for the key",
            "retry at currentOwner, bounded by maxRedirects"),
    /** The sender is behind or ahead of the recipient. */
    EPOCH_MISMATCH(302, "epochMismatch", "after a refresh",
            "the sender is behind or ahead of the recipient",
            "refresh the topology, then retry"),
    /** The two topology identifiers differ. */
    IDENTITY_MISMATCH(303, "identityMismatch", "no",
            "the two topologyId values differ",
            "operator action; epochs under two identifiers are incomparable"),
    /** A redirect walk reached its bound or revisited a node. */
    REDIRECT_EXHAUSTED(304, "redirectExhausted", "no",
            "a redirect walk reached its bound or revisited a node",
            "surface the failure; a retryBudget cause means the cluster is shedding"),
    /** A plan cannot be built from the two snapshots and the policy. */
    PLAN_REFUSED(401, "planRefused", "no",
            "a plan cannot be built from the two snapshots and the policy",
            "correct the snapshots or the policy member named in cause"),
    /** The shard is inside the cutover window. */
    QUIESCED(402, "quiesced", "yes",
            "the shard is inside the cutover window",
            "retry after the window, which commitDeadlineMillis bounds"),
    /** A handoff reached the failed state. */
    HANDOFF_FAILED(403, "handoffFailed", "no",
            "a handoff reached failed",
            "operator action, directed by the failure kind in cause; undetermined takes a"
                    + " re-observation");

    /**
     * The order {@code ERR-008} reports in where more than one condition holds for one call.
     */
    public static final List<ErrorCode> REPORT_ORDER = List.of(
            INVALID_ARGUMENT, UNREADY, STALE_SNAPSHOT, NO_CANDIDATE, EXHAUSTED);

    /** The members a condition carries, under {@code ERR-004} and {@code ERR-061}. */
    public static final List<String> MEMBERS = List.of(
            "code", "name", "retryable", "detail", "token", "shard", "cause", "currentOwner");

    private final int code;
    private final String errorName;
    private final String retryable;
    private final String condition;
    private final String callerResponse;

    ErrorCode(int code, String errorName, String retryable, String condition,
              String callerResponse) {
        this.code = code;
        this.errorName = errorName;
        this.retryable = retryable;
        this.condition = condition;
        this.callerResponse = callerResponse;
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
        return !"no".equals(retryable);
    }

    /**
     * The retryable column of {@code ERR-010}, which qualifies two of the sixteen.
     *
     * <p>A recipient condition is retryable somewhere other than where it was raised:
     * {@code notOwner} is retryable at another node and {@code epochMismatch} after a refresh, and
     * a flag that answered yes to both would tell a caller to retry the call it just made.
     */
    public String retryableSpelling() {
        return retryable;
    }

    /** The condition {@code ERR-010} names. */
    public String condition() {
        return condition;
    }

    /** The response {@code ERR-011} requires of a caller. */
    public String callerResponse() {
        return callerResponse;
    }

    /**
     * The closed cause set the condition defines, which is empty where it defines none.
     *
     * <p>Each set is the one its own requirement states: {@code ERR-021} for the no-candidate
     * causes, {@code ERR-022} for exhaustion, {@code ERR-041} for an epoch mismatch,
     * {@code ERR-043} for an exhausted redirect walk, {@code ERR-050} for a refused plan, and
     * {@code ERR-052} for a failed handoff.
     */
    public List<String> causes() {
        return switch (this) {
            case NO_CANDIDATE -> List.of("emptyPlacementSet", "constraintExcludedAll",
                    "noDirectoryEntry", "pinExcludedAll", "noSlotEntry", "zeroVirtualNodes",
                    "authoredListExcludedAll", "noEligibleTokenOwner");
            case EXHAUSTED -> List.of("preferenceList", "attemptLimit", "retryBudget");
            case EPOCH_MISMATCH -> List.of("senderBehind", "senderAhead", "unfenced");
            case REDIRECT_EXHAUSTED -> List.of("boundReached", "revisitedNode", "unknownNode",
                    "retryBudget");
            case PLAN_REFUSED -> List.of("incomparableShards", "epochNotAdvancing",
                    "strategyUnsupported", "destinationOutsidePlacementSet", "policyInvalid",
                    "topologyMismatch");
            case HANDOFF_FAILED -> List.of("unverified", "residue", "undetermined",
                    "rollbackFailed");
            default -> List.of();
        };
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
