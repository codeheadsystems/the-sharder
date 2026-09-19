package com.codeheadsystems.sharder.error;

/**
 * {@code ERR-025}: a caller-supplied argument is outside the contract.
 *
 * <p>It is raised for a key above {@code maxKeyBytes}, for an attempt limit of zero, for a setting
 * outside its range, and for an affinity request {@code READ-011} or {@code READ-017} refuses. It
 * is not raised for a key whose octets are not valid text, under {@code KEY-013}.
 */
public final class InvalidArgumentException extends RoutingException {

    private static final long serialVersionUID = 1L;

    /** The condition, carrying what was wrong with the argument. */
    public InvalidArgumentException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, null, message);
    }
}
