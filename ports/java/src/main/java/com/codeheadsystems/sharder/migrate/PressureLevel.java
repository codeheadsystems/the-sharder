package com.codeheadsystems.sharder.migrate;

/**
 * How much backpressure a scope reports, under {@code RATE-061}.
 *
 * <p>The three levels are ordered, and a step takes the highest of the cluster reading, the source
 * reading, and the destination reading, under {@code RATE-071}.
 */
public enum PressureLevel {

    /** Nothing is withheld. */
    NONE("none"),

    /** No handoff leaves {@code planned}, and every other step continues. */
    SOFT("soft"),

    /** No handoff leaves {@code planned}, and {@code transfer} and {@code catchUp} are withheld. */
    HARD("hard");

    private final String spelling;

    PressureLevel(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling a vector and a scenario join on. */
    public String spelling() {
        return spelling;
    }

    /** The higher of two levels, which is what {@code RATE-071} takes of three readings. */
    public PressureLevel max(PressureLevel other) {
        return compareTo(other) >= 0 ? this : other;
    }

    /** The level that spelling names. */
    public static PressureLevel of(String spelling) {
        for (PressureLevel level : values()) {
            if (level.spelling.equals(spelling)) {
                return level;
            }
        }
        throw new IllegalArgumentException("no pressure level named " + spelling);
    }
}
