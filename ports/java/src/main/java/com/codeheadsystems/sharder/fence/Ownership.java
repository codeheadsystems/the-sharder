package com.codeheadsystems.sharder.fence;

/**
 * Whether the recipient owns the key at its own snapshot, under {@code FENCE-071}.
 *
 * <p>Ownership is the replica prefix rather than the whole preference list: a node the key falls
 * back to is attemptable and is not an owner.
 */
public enum Ownership {

    /** The recipient is within the replica prefix for the key. */
    OWNER("owner"),

    /** The recipient is outside it. */
    NOT_OWNER("notOwner"),

    /** The recipient holds no snapshot, or none under the token's identifier. */
    UNKNOWN("unknown");

    private final String spelling;

    Ownership(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling a vector and an event join on. */
    public String spelling() {
        return spelling;
    }

    /** The ownership that spelling names. */
    public static Ownership of(String spelling) {
        for (Ownership ownership : values()) {
            if (ownership.spelling.equals(spelling)) {
                return ownership;
            }
        }
        throw new IllegalArgumentException("no ownership named " + spelling);
    }
}
