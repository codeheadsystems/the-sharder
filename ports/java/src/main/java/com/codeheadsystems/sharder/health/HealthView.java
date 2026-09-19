package com.codeheadsystems.sharder.health;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.topology.TopologySnapshot;

/**
 * What a router knows about the nodes it routes to, under {@code HEALTH-010}.
 *
 * <p>An integrator supplies an implementation of its own where the signals live somewhere else, and
 * takes the built-in sliding window otherwise. Two of the five members carry defaults, so an
 * implementation written against an earlier revision stays valid: ignoring
 * {@link #onSnapshotInstalled} means running no outlier ejection, under {@code HEALTH-016}, and
 * ignoring {@link #admitProbe} means admitting every probe.
 */
public interface HealthView {

    /** The state of one node, under {@code HEALTH-010}. */
    HealthState stateOf(NodeId node);

    /** Ingests one observation. */
    void report(HealthSignal signal);

    /** Advances time to {@code now}, which is what expires a window and ends an ejection. */
    void advance(long now);

    /**
     * The placement set outlier ejection compares against, under {@code HEALTH-030}.
     *
     * <p>It is also what the ejection ceiling of {@code HEALTH-034} is a percentage of.
     */
    default void onSnapshotInstalled(TopologySnapshot snapshot) {
    }

    /**
     * Whether a probation probe is admitted, under {@code HEALTH-051}.
     *
     * <p>The counter this reads sits apart from {@link #stateOf}, which {@code explain} reads and
     * which {@code OBS-045} forbids from changing state.
     */
    default boolean admitProbe(NodeId node) {
        return true;
    }
}
