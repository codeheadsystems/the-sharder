package com.codeheadsystems.sharder.observe;

import com.codeheadsystems.sharder.NodeId;

/**
 * One node the routing of a key did not place on, and the stage that dropped it, under
 * {@code OBS-041}.
 *
 * <p>{@code OBS-043} makes the exclusions total: every member of the eligible node set appears
 * exactly once, either in the candidates or here, and every member of the placement set the
 * eligible set omits appears here with a stage of {@code constraint} or
 * {@code administrativeState}.
 */
public record Exclusion(NodeId node, Stage stage, String reason) {

    /** The stage that dropped a node, from the closed set of {@code OBS-041}. */
    public enum Stage {
        /** The node is not placeable in its administrative state. */
        ADMINISTRATIVE_STATE("administrativeState"),
        /** An override constraint excluded the node. */
        CONSTRAINT("constraint"),
        /** The node carries no virtual node, so the strategy never reaches it. */
        VIRTUAL_NODES("virtualNodes"),
        /** The authored list the key matched does not name the node. */
        AUTHORED_LIST("authoredList"),
        /** The candidate ordering already held the node. */
        DUPLICATE("duplicate"),
        /** The spread requirement in force admitted no further placement in its domain. */
        SPREAD("spread"),
        /** The replication factor was already reached. */
        FACTOR_REACHED("factorReached"),
        /** The health filter skipped the node. */
        HEALTH("health");

        private final String spelling;

        Stage(String spelling) {
            this.spelling = spelling;
        }

        /** The spelling {@code OBS-041} gives the stage. */
        public String spelling() {
            return spelling;
        }

        /** The stage that spelling names. */
        public static Stage of(String spelling) {
            for (Stage stage : values()) {
                if (stage.spelling.equals(spelling)) {
                    return stage;
                }
            }
            throw new IllegalArgumentException("no exclusion stage named " + spelling);
        }
    }
}
