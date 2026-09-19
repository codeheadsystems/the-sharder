package com.codeheadsystems.sharder.migrate;

import com.codeheadsystems.sharder.MonotonicClock;
import com.codeheadsystems.sharder.topology.OwnershipDelta;
import com.codeheadsystems.sharder.topology.TopologySnapshot;
import java.util.Iterator;
import java.util.Map;

/**
 * One migration, from the snapshot it was planned against to the snapshot it moves towards.
 *
 * <p>A plan is passive: it advances when the integrator calls {@link #step}, and never on a timer,
 * a thread, or a snapshot installation, under {@code MOVE-101} and {@code CORE-060}. The clock is
 * supplied per call and is the source the plan reads for the duration of that call, under
 * {@code MOVE-062}.
 *
 * <p>Concurrent calls to {@code step} are permitted and no two of them advance one handoff, under
 * {@code MOVE-071}.
 */
public interface MigrationPlan {

    /** The shards the plan moves, under {@code TOPO-211}. */
    OwnershipDelta delta();

    /** Every handoff of the plan, in a stable order. */
    Iterator<HandoffId> handoffs();

    /** The state one handoff is in. */
    HandoffState state(HandoffId id);

    /** Advances at most one handoff by at most one hook call, under {@code MOVE-071}. */
    StepOutcome step(MonotonicClock clock);

    /** Reads the durable state for every non-terminal handoff, under {@code MOVE-211}. */
    RecoveryReport recover(MonotonicClock clock);

    /** Reads the durable state for one terminal handoff, under {@code MOVE-233}. */
    ReobserveOutcome reobserve(HandoffId id, MonotonicClock clock);

    /**
     * Rebuilds the plan against a newer snapshot, under {@code MOVE-094}.
     *
     * <p>A rebase that cannot be performed raises {@code PlanRefusedException}, which
     * {@code MOVE-095} maps to {@code planRefused}.
     */
    RebaseReport rebase(TopologySnapshot to);

    /** Aborts one handoff, which runs compensation where the handoff has begun. */
    void abort(HandoffId id, String reason);

    /** Aborts every handoff that has not reached a terminal state. */
    void abortAll(String reason);

    /** Compares an installed snapshot against the plan, under {@code MOVE-091}. */
    void onSnapshotInstalled(TopologySnapshot snapshot);

    /** How many handoffs are in each state. */
    Map<HandoffState, Integer> summary();
}
