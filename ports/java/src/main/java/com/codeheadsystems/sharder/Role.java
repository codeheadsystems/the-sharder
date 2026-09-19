package com.codeheadsystems.sharder;

/**
 * The part one entry of a preference list plays, under {@code REPL-010}.
 *
 * <p>An entry the effective replication factor covers is a replica, and an entry beyond it is a
 * fallback. A fallback is attemptable under {@code FAIL-020} and it is not an owner, which is the
 * distinction {@code FENCE-071} turns on at a recipient. The head of the list is the primary, and
 * {@code RoutingDecision.primary()} reads it rather than a role of its own.
 */
public enum Role {

    /** An entry the effective replication factor covers. */
    REPLICA("replica"),

    /** An entry beyond the effective replication factor. */
    FALLBACK("fallback");

    private final String spelling;

    Role(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling the specification and the vectors use. */
    public String spelling() {
        return spelling;
    }

    /** The role a spelling names. */
    public static Role of(String spelling) {
        for (Role role : values()) {
            if (role.spelling.equals(spelling)) {
                return role;
            }
        }
        throw new IllegalArgumentException("no role named " + spelling);
    }
}
