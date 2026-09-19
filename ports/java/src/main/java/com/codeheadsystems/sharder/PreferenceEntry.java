package com.codeheadsystems.sharder;

import com.codeheadsystems.sharder.health.HealthState;

/**
 * One entry of a preference list, under {@code REPL-017}.
 *
 * <p>{@code position} is the zero-based place in the ordering, {@code role} is whether the
 * effective replication factor covers the entry, and {@code attemptable} is what the health filter
 * of {@code HEALTH-005} left of it.
 */
public record PreferenceEntry(NodeId node, int position, Role role, HealthState health,
                              boolean attemptable) {
}
