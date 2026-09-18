package com.codeheadsystems.sharder.error;

/**
 * {@code ERR-020}: the candidate ordering is empty.
 *
 * <p>The condition carries a cause from the closed set of {@code ERR-021}, whose rows are evaluated
 * in the order that table writes them, because more than one holds for a routing key an override
 * constrains over a strategy that would have produced no candidate of its own.
 */
public final class NoCandidateException extends RoutingException {

    private static final long serialVersionUID = 1L;

    /** The closed cause set of {@code ERR-021}. */
    public enum Cause {
        /** The placement set is empty, or no other row holds. */
        EMPTY_PLACEMENT_SET("emptyPlacementSet"),
        /** A constraint left the eligible node set empty. */
        CONSTRAINT_EXCLUDED_ALL("constraintExcludedAll"),
        /** Every identity of a pin was filtered out. */
        PIN_EXCLUDED_ALL("pinExcludedAll"),
        /** A directory matched no entry for the routing key. */
        NO_DIRECTORY_ENTRY("noDirectoryEntry"),
        /** No slot assignment entry covers the slot index. */
        NO_SLOT_ENTRY("noSlotEntry"),
        /** No node of the matched authored list is eligible. */
        AUTHORED_LIST_EXCLUDED_ALL("authoredListExcludedAll"),
        /** No eligible node carries a non-zero virtual node count. */
        ZERO_VIRTUAL_NODES("zeroVirtualNodes"),
        /** No eligible node carries a ring token. */
        NO_ELIGIBLE_TOKEN_OWNER("noEligibleTokenOwner");

        private final String spelling;

        Cause(String spelling) {
            this.spelling = spelling;
        }

        /** The spelling of {@code ERR-021}, which a vector and an event join on. */
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
            throw new IllegalArgumentException("no no-candidate cause named " + spelling);
        }
    }

    private final transient Cause reason;

    /** The condition with the cause that spelling names. */
    public NoCandidateException(String spelling) {
        this(Cause.of(spelling));
    }

    /** The condition with that cause. */
    public NoCandidateException(Cause reason) {
        super(ErrorCode.NO_CANDIDATE, reason.spelling(),
                "the candidate ordering is empty: " + reason.spelling());
        this.reason = reason;
    }

    /** The cause of {@code ERR-021}. */
    public Cause reason() {
        return reason;
    }
}
