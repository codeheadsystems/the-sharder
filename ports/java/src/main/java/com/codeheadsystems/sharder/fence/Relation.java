package com.codeheadsystems.sharder.fence;

/**
 * How the token's epoch relates to the recipient's, under {@code FENCE-071}.
 *
 * <p>The relation is a comparison of two epochs under one topology identifier. Two epochs under
 * two identifiers are incomparable, which is what {@link #IDENTITY_MISMATCH} names.
 */
public enum Relation {

    /** The token and the recipient name the same epoch. */
    SAME("same"),

    /** The token names an epoch below the recipient's. */
    SENDER_BEHIND("senderBehind"),

    /** The token names an epoch above the recipient's. */
    SENDER_AHEAD("senderAhead"),

    /** The recipient holds no snapshot, so it holds no epoch to compare against. */
    UNKNOWN_EPOCH("unknownEpoch"),

    /** The token names a topology the recipient does not serve. */
    IDENTITY_MISMATCH("identityMismatch");

    private final String spelling;

    Relation(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling a vector and an event join on. */
    public String spelling() {
        return spelling;
    }

    /** The relation that spelling names. */
    public static Relation of(String spelling) {
        for (Relation relation : values()) {
            if (relation.spelling.equals(spelling)) {
                return relation;
            }
        }
        throw new IllegalArgumentException("no relation named " + spelling);
    }
}
