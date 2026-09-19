package com.codeheadsystems.sharder.health;

import com.codeheadsystems.sharder.NodeId;

/**
 * One observation a caller reports about a node, under {@code HEALTH-020}.
 *
 * <p>The library observes nothing itself: every signal it holds is one a caller reported, which is
 * what {@code HEALTH-002} means by a passive health surface.
 */
public record HealthSignal(NodeId node, Outcome outcome, long observed) {
}
