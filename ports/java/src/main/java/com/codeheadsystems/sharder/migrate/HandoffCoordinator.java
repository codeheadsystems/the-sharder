package com.codeheadsystems.sharder.migrate;

import com.codeheadsystems.sharder.topology.TopologySnapshot;

/**
 * Where a migration is planned, under {@code MOVE-061}.
 *
 * <p>The coordinator holds no snapshot of its own and installs nothing: it reads the two snapshots
 * it is given, computes the ownership delta between them, and answers a plan the integrator drives.
 * A plan is never created as a side effect of installing a snapshot, under {@code MOVE-101}.
 */
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

    /**
     * Where each shard's contents come from across the two snapshots, under {@code LIN-031}.
     *
     * <p>An integrator sizes a migration before deciding to run it, which is the reason
     * {@code TOPO-212} makes the ownership delta a call rather than a product of installation, and
     * the reason this is one too. Computing a lineage installs nothing and plans nothing.
     *
     * <p>It refuses with {@code PlanRefusedException} where the two snapshots carry differing
     * {@code topologyId}s or do not join on shard identity, both under the cause
     * {@code incomparableShards} of {@code TOPO-231}; where the strategy enumerates no shard,
     * under {@code strategyUnsupported}; and where a boundary moved without either dividing or
     * folding an extent whole, under {@code unalignedLineage} of {@code LIN-022}. A snapshot this
     * library did not produce is an {@code InvalidArgumentException}, as it is for {@code plan}.
     */
    ShardLineage lineage(TopologySnapshot from, TopologySnapshot to);
}
