package com.codeheadsystems.sharder.placement;

/** The four placement strategies of {@code PLACE-010}, by the name a document spells. */
public enum StrategyKind {

    /** Consistent hashing over a token ring. */
    RING("ring"),

    /** Highest random weight over the placement set. */
    RENDEZVOUS("rendezvous"),

    /** A fixed slot space, assigned by the document. */
    SLOT("slot"),

    /** An authored table of matchers to node lists. */
    DIRECTORY("directory");

    private final String spelling;

    StrategyKind(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling the topology document carries. */
    public String spelling() {
        return spelling;
    }

    /** The strategy a spelling names. */
    public static StrategyKind of(String spelling) {
        for (StrategyKind kind : values()) {
            if (kind.spelling.equals(spelling)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("no placement strategy named " + spelling);
    }
}
