package com.codeheadsystems.sharder;

/** Which members of a matched override entry applied, under {@code OVR-010}. */
public enum OverrideMode {

    /** The entry pinned the key to an authored node list. */
    PIN("pin"),

    /** The entry constrained the eligible node set. */
    CONSTRAIN("constrain"),

    /** The entry did both. */
    BOTH("both");

    private final String spelling;

    OverrideMode(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling a vector and an explain record join on. */
    public String spelling() {
        return spelling;
    }

    /** The mode that spelling names. */
    public static OverrideMode of(String spelling) {
        for (OverrideMode mode : values()) {
            if (mode.spelling.equals(spelling)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("no override mode named " + spelling);
    }
}
