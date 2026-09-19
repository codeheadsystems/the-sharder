package com.codeheadsystems.sharder.error;

import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.topology.FencingToken;
import java.util.Optional;

/**
 * The base of the closed condition set of {@code ERR-010}.
 *
 * <p>The hierarchy is sealed, so a {@code switch} over a caught condition is exhaustive without a
 * default and a binding cannot add a leaf of its own, which {@code ERR-062} forbids. Conditions are
 * unchecked, so a routing call composes inside a lambda without a wrapper at every call site.
 *
 * <p>Four sealed groups carry the four numeric blocks of {@code ERR-010}, and the sixteen leaves
 * under them are the whole condition set.
 */
public sealed abstract class SharderException extends RuntimeException
        permits RoutingException, TopologyException, RecipientException, MigrationException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;
    private final transient String reason;
    private final transient FencingToken token;
    private final transient ShardId shard;

    SharderException(ErrorCode errorCode, String reason, String message) {
        this(errorCode, reason, message, null, null, null);
    }

    SharderException(ErrorCode errorCode, String reason, String message, FencingToken token,
                     ShardId shard, Throwable original) {
        super(message, original);
        this.errorCode = errorCode;
        this.reason = reason;
        this.token = token;
        this.shard = shard;
    }

    /** The condition of {@code ERR-010}. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** The permanent numeric code, under {@code ERR-061}. */
    public int code() {
        return errorCode.code();
    }

    /** The permanent name, under {@code ERR-061}. */
    public String errorName() {
        return errorCode.errorName();
    }

    /** Whether a caller retrying the same call may succeed, under {@code ERR-006}. */
    public boolean retryable() {
        return errorCode.retryable();
    }

    /** The closed sub-reason of {@code ERR-004}, where the condition defines a set of them. */
    public Optional<String> cause() {
        return Optional.ofNullable(reason);
    }

    /** The token the condition concerns, where the condition names one. */
    public Optional<FencingToken> token() {
        return Optional.ofNullable(token);
    }

    /** The shard the condition concerns, where the condition names one. */
    public Optional<ShardId> shard() {
        return Optional.ofNullable(shard);
    }
}
