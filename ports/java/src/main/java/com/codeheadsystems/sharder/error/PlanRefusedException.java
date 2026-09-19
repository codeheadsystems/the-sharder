package com.codeheadsystems.sharder.error;

/**
 * {@code ERR-050}: a migration plan was refused before any handoff began.
 *
 * <p>Every cause is a property of the two topologies or of the policy, so a refused plan is
 * refused whole rather than part way through.
 */
public final class PlanRefusedException extends MigrationException {

    private static final long serialVersionUID = 1L;

    /** The closed cause set of {@code MOVE-021}. */
    public enum Cause {
        /** The two topologies do not join on shard identity, under {@code TOPO-231}. */
        INCOMPARABLE_SHARDS("incomparableShards"),
        /** The target epoch does not exceed the source epoch. */
        EPOCH_NOT_ADVANCING("epochNotAdvancing"),
        /** The strategy supports no orchestrated migration, under {@code MOVE-261}. */
        STRATEGY_UNSUPPORTED("strategyUnsupported"),
        /** A destination is outside the placement set of the target topology. */
        DESTINATION_OUTSIDE_PLACEMENT_SET("destinationOutsidePlacementSet"),
        /** The policy carries a value outside its range. */
        POLICY_INVALID("policyInvalid"),
        /** The plan names a topology the router does not hold. */
        TOPOLOGY_MISMATCH("topologyMismatch");

        private final String spelling;

        Cause(String spelling) {
            this.spelling = spelling;
        }

        /** The spelling a vector and an event join on. */
        public String spelling() {
            return spelling;
        }

        /** The cause that spelling names. */
        public static Cause of(String spelling) {
            for (Cause cause : values()) {
                if (cause.spelling.equals(spelling)) {
                    return cause;
                }
            }
            throw new IllegalArgumentException("no cause named " + spelling);
        }
    }

    /** The condition, with the property of the plan that refused it. */
    public PlanRefusedException(Cause reason) {
        super(ErrorCode.PLAN_REFUSED, reason.spelling(),
                "the plan was refused at " + reason.spelling(), null, null);
    }

    /** The property of the plan that refused it. */
    public Cause reason() {
        return Cause.of(cause().orElseThrow());
    }
}
