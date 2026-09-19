package com.codeheadsystems.sharder.migrate.internal;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.route.OwnershipDelta;
import com.codeheadsystems.sharder.core.internal.snapshot.DocumentSnapshot;
import com.codeheadsystems.sharder.error.InvalidArgumentException;
import com.codeheadsystems.sharder.error.PlanRefusedException;
import com.codeheadsystems.sharder.migrate.HandoffCoordinator;
import com.codeheadsystems.sharder.migrate.MigrationPlan;
import com.codeheadsystems.sharder.migrate.MigrationPolicy;
import com.codeheadsystems.sharder.migrate.MovementHooks;
import com.codeheadsystems.sharder.topology.TopologySnapshot;

/**
 * Where a plan is built, under {@code MOVE-061}.
 *
 * <p>The coordinator holds nothing between calls. A plan is a pure function of the two snapshots
 * and the policy, which is what lets a restarted coordinator rebuild the same plan under
 * {@code MOVE-221}, and what makes the integrator's durable state the authority rather than
 * anything held here.
 */
public final class DefaultCoordinator implements HandoffCoordinator {

    /** The coordinator every plan is built through. */
    public DefaultCoordinator() {
    }

    @Override
    public MigrationPlan plan(TopologySnapshot from, TopologySnapshot to, MovementHooks hooks,
                              MigrationPolicy policy) {
        DocumentSnapshot source = snapshot(from, "from");
        DocumentSnapshot target = snapshot(to, "to");
        // MOVE-081, in the order the requirement writes the refusals.
        if (OwnershipDelta.incomparable(source.document(), target.document()).isPresent()
                || !source.topologyId().equals(target.topologyId())) {
            throw new PlanRefusedException(PlanRefusedException.Cause.INCOMPARABLE_SHARDS);
        }
        if (target.epoch() <= source.epoch()) {
            throw new PlanRefusedException(PlanRefusedException.Cause.EPOCH_NOT_ADVANCING);
        }
        if (!target.engine().supportsOrchestratedMigration()) {
            throw new PlanRefusedException(PlanRefusedException.Cause.STRATEGY_UNSUPPORTED);
        }
        var machine = com.codeheadsystems.sharder.core.internal.migrate.MigrationPlan.of(
                source.engine(), target.engine(), policy.quiesceLeaseMarginMillis());
        machine.policy(policy.maxConcurrentHandoffs(), policy.maxConcurrentPerSourceNode(),
                policy.maxConcurrentPerDestinationNode());
        // MOVE-333 and MOVE-336 both read the deadline, so the policy's value reaches the machine
        // rather than the machine keeping the default of CFG-050 whatever the policy says.
        machine.commitDeadlineMillis(policy.commitDeadlineMillis());
        for (String id : machine.handoffs()) {
            NodeId destination = machine.handoff(id).destination();
            if (!target.placementSet().contains(destination)) {
                throw new PlanRefusedException(
                        PlanRefusedException.Cause.DESTINATION_OUTSIDE_PLACEMENT_SET);
            }
        }
        return new DefaultMigrationPlan(machine, source, target, hooks, policy);
    }

    private static DocumentSnapshot snapshot(TopologySnapshot snapshot, String name) {
        if (snapshot instanceof DocumentSnapshot document) {
            return document;
        }
        throw new InvalidArgumentException(
                "the " + name + " snapshot was not produced by this library");
    }
}
