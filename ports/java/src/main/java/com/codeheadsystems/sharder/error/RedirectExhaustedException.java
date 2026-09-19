package com.codeheadsystems.sharder.error;

/**
 * {@code ERR-042}: the redirect walk of {@code FENCE-221} offers no further node.
 *
 * <p>The four causes are the refusal order the walk evaluates, and the first that holds is the
 * cause the condition carries.
 */
public final class RedirectExhaustedException extends RecipientException {

    private static final long serialVersionUID = 1L;

    /** None */
    public enum Cause {
        /** The redirect bound of {@code CFG-040} was reached. */
        BOUND_REACHED("boundReached"),
        /** The redirect named a node the sequence already attempted. */
        REVISITED_NODE("revisitedNode"),
        /** The redirect named a node the snapshot does not carry. */
        UNKNOWN_NODE("unknownNode"),
        /** The retry budget refused the redirect, under {@code FENCE-231}. */
        RETRY_BUDGET("retryBudget");

        private final String spelling;

        Cause(String spelling) {
            this.spelling = spelling;
        }

        /** The spelling a vector and an event join on. */
        public String spelling() {
            return spelling;
        }

        /** The cause that spelling names. */
        public static Cause of(String spelling) {
            for (Cause cause : values()) {
                if (cause.spelling.equals(spelling)) {
                    return cause;
                }
            }
            throw new IllegalArgumentException("no cause named " + spelling);
        }
    }

    /** The condition, with the refusal that ended the walk. */
    public RedirectExhaustedException(Cause reason) {
        super(ErrorCode.REDIRECT_EXHAUSTED, reason.spelling(),
                "the redirect walk ended at " + reason.spelling(), null, null);
    }

    /** The refusal that ended the walk. */
    public Cause reason() {
        return Cause.of(cause().orElseThrow());
    }
}
