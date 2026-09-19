package com.codeheadsystems.sharder.config;

/** What a routing call does against a snapshot older than the freshness bound, under {@code CFG-010}. */
public enum StalePolicy {

    /** The call routes against the stale snapshot. */
    SERVE("serve"),

    /** The call answers {@code staleSnapshot}. */
    REFUSE("refuse");

    private final String spelling;

    StalePolicy(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling the configuration carries. */
    public String spelling() {
        return spelling;
    }

    /** The policy that spelling names. */
    public static StalePolicy of(String spelling) {
        for (StalePolicy policy : values()) {
            if (policy.spelling.equals(spelling)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("no stale policy named " + spelling);
    }
}
