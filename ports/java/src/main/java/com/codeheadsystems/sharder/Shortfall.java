package com.codeheadsystems.sharder;

/**
 * Why a preference list is shorter than the replication factor, under {@code REPL-004}.
 *
 * <p>A short list is an answer rather than a failure, and the cause states which of the three
 * bounds produced it. {@code NONE} is the list that reached the factor.
 */
public enum Shortfall {

    /** The list reached the replication factor. */
    NONE("none"),

    /** The candidate ordering ran out of nodes. */
    NODES("nodes"),

    /** A strict spread level admitted no further placement. */
    DOMAINS("domains");

    private final String spelling;

    Shortfall(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling the specification and the vectors use. */
    public String spelling() {
        return spelling;
    }

    /** The shortfall a spelling names. */
    public static Shortfall of(String spelling) {
        for (Shortfall shortfall : values()) {
            if (shortfall.spelling.equals(spelling)) {
                return shortfall;
            }
        }
        throw new IllegalArgumentException("no shortfall named " + spelling);
    }
}
