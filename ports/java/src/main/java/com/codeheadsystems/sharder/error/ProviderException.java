package com.codeheadsystems.sharder.error;

/**
 * {@code ERR-033}: the provider failed.
 *
 * <p>The original failure is attached as the Java cause, under {@code ERR-063}, which is one of the
 * two places this library catches {@code Throwable}. {@code SharderException.cause()} and
 * {@code Throwable.getCause()} are different things on the same object: the first is the closed
 * sub-reason of {@code ERR-004} and the second is the failure the extension point raised.
 */
public final class ProviderException extends TopologyException {

    private static final long serialVersionUID = 1L;

    /** The condition, with the failure the provider raised attached. */
    public ProviderException(String message, Throwable original) {
        super(ErrorCode.PROVIDER_ERROR, null, message, null, null, original);
    }
}
