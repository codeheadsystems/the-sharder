package com.codeheadsystems.sharder.migrate;

import java.util.Objects;

/**
 * The name one handoff of a plan carries, under {@code MOVE-061}.
 *
 * <p>An identifier is opaque to the integrator and stable for the life of the plan: it names the
 * same shard moving from the same source to the same destination however many times the handoff is
 * stepped, retried, or re-observed.
 */
public record HandoffId(String value) {

    /** The identifier is a name rather than a number, and is never empty. */
    public HandoffId {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("a handoff identifier is not empty");
        }
    }

    /** The identifier {@code value} names. */
    public static HandoffId of(String value) {
        return new HandoffId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
