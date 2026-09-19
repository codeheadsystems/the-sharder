package com.codeheadsystems.sharder.topology;

/**
 * The administrative state of a node, under {@code TOPO-020}.
 *
 * <p>Two of the four states are in the placement set of {@code PLACE-001}. A joining node is
 * announced before it is placed on, and a leaving node is placed off before it is removed, so a
 * topology change is two documents rather than one.
 */
public enum AdministrativeState {

    /** Placeable and serving. */
    ACTIVE("active", true),

    /** Placeable and serving, with its shards moving away at a later epoch. */
    DRAINING("draining", true),

    /** Announced and not placeable. */
    JOINING("joining", false),

    /** Placed off and not placeable. */
    LEAVING("leaving", false);

    private final String spelling;
    private final boolean placeable;

    AdministrativeState(String spelling, boolean placeable) {
        this.spelling = spelling;
        this.placeable = placeable;
    }

    /** The spelling the topology document carries. */
    public String spelling() {
        return spelling;
    }

    /** Whether a node in this state is in the placement set of {@code PLACE-001}. */
    public boolean placeable() {
        return placeable;
    }

    /** The state a spelling names. */
    public static AdministrativeState of(String spelling) {
        for (AdministrativeState state : values()) {
            if (state.spelling.equals(spelling)) {
                return state;
            }
        }
        throw new IllegalArgumentException("no administrative state named " + spelling);
    }
}
