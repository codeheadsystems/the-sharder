package com.codeheadsystems.sharder.observe;

/** The severity one event carries, under {@code OBS-021}. */
public enum Severity {

    /** An ordinary transition an operator reads after the fact. */
    INFO("info"),

    /** A condition an operator acts on. */
    WARNING("warning"),

    /** A condition that stops the library doing what it was asked. */
    ERROR("error");

    private final String spelling;

    Severity(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling the observability inventory carries. */
    public String spelling() {
        return spelling;
    }

    /** The severity that spelling names. */
    public static Severity of(String spelling) {
        for (Severity severity : values()) {
            if (severity.spelling.equals(spelling)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("no severity named " + spelling);
    }
}
