package com.codeheadsystems.sharder.error;

import java.util.Optional;

/**
 * The base of the closed condition set of {@code ERR-010}.
 *
 * <p>The hierarchy is sealed, so a {@code switch} over a caught condition is exhaustive without a
 * default and a binding cannot add a leaf of its own, which {@code ERR-062} forbids. Conditions are
 * unchecked, so a routing call composes inside a lambda without a wrapper at every call site.
 *
 * <p>The leaves land with the surfaces that raise them. The permitted list grows as they do, and
 * the closed set of sixteen is what it grows towards.
 */
public sealed abstract class SharderException extends RuntimeException
        permits RoutingException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;
    private final transient String reason;

    SharderException(ErrorCode errorCode, String reason, String message) {
        super(message);
        this.errorCode = errorCode;
        this.reason = reason;
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
}
