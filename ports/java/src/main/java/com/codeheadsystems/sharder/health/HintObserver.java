package com.codeheadsystems.sharder.health;

import com.codeheadsystems.sharder.NodeId;

/**
 * Where a health transition is reported to, beside the events of {@code HEALTH-048}.
 *
 * <p>An integrator that routes with one library and reports health from another process uses this
 * to carry a transition across: the library states what changed and never acts on the answer.
 */
@FunctionalInterface
public interface HintObserver {

    /** One node's state changed, under the trigger named. */
    void onTransition(NodeId node, HealthState from, HealthState to, String trigger);
}
