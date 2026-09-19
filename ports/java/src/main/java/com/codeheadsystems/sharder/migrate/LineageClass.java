package com.codeheadsystems.sharder.migrate;

/**
 * What became of one shard between two snapshots, under {@code LIN-021}.
 *
 * <p>A shard the later snapshot enumerates is classified against the earlier one, and a shard only
 * the earlier snapshot enumerates is classified against the later one, so every shard of a lineage
 * carries exactly one of these. A pair whose extents neither divide nor fold whole has no class at
 * all: it is refused under {@code LIN-022} rather than classified.
 */
public enum LineageClass {
    /** One parent, whose extent is equal, and the replica set is equal. */
    UNCHANGED("unchanged"),
    /** One parent, whose extent is equal, and the replica set differs. */
    MOVED("moved"),
    /** One parent, whose extent strictly contains this one. */
    DIVIDED("divided"),
    /** Two or more parents, whose extents this one contains. */
    MERGED("merged"),
    /** No parent: this extent meets no extent of the earlier snapshot. */
    FRESH("fresh"),
    /** Only the earlier snapshot enumerates it, and two or more children divide its extent. */
    SPLIT("split"),
    /** Only the earlier snapshot enumerates it, and one child's extent contains its own. */
    FOLDED("folded"),
    /** Only the earlier snapshot enumerates it, and its extent meets no later extent. */
    VACATED("vacated");

    private final String spelling;

    LineageClass(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling a vector, a scenario, and an event join on. */
    public String spelling() {
        return spelling;
    }

    /** Whether the shard's extent is the one its parent held. */
    public boolean extentUnchanged() {
        return this == UNCHANGED || this == MOVED;
    }
}
