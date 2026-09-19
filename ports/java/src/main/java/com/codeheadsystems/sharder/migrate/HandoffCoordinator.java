package com.codeheadsystems.sharder.migrate;

import com.codeheadsystems.sharder.topology.TopologySnapshot;

/**
 * Where a migration is planned, under {@code MOVE-061}.
 *
 * <p>The coordinator holds no snapshot of its own and installs nothing: it reads the two snapshots
 * it is given, computes the ownership delta between them, and answers a plan the integrator drives.
 * A plan is never created as a side effect of installing a snapshot, under {@code MOVE-101}.
 */
@FunctionalInterface
public interface HandoffCoordinator {

    /**
     * The plan that moves ownership from one snapshot to the other.
     *
     * <p>It refuses with {@code PlanRefusedException} where the two snapshots do not join on shard
     * identity, where the target epoch does not advance, where the strategy supports no
     * orchestrated migration, or where a destination sits outside the target placement set, under
     * {@code MOVE-081}.
     */
    MigrationPlan plan(TopologySnapshot from, TopologySnapshot to, MovementHooks hooks,
                       MigrationPolicy policy);
}
