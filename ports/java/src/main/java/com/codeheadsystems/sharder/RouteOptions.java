package com.codeheadsystems.sharder;

import com.codeheadsystems.sharder.error.InvalidArgumentException;
import java.util.OptionalInt;

/**
 * What one routing call asks for beyond the key, under {@code CORE-030}.
 *
 * <p>An attempt limit absent here takes the configured one, under {@code CFG-020}. An explain
 * record is computed only where the caller asked for one, under {@code OBS-046}, because the record
 * names every eligible node and every exclusion and its cost grows with the node set.
 */
public record RouteOptions(OptionalInt attemptLimit, boolean explain) {

    /** The configured attempt limit and no explain record. */
    public static final RouteOptions DEFAULTS = new RouteOptions(OptionalInt.empty(), false);

    /** Neither member is null. */
    public RouteOptions {
        if (attemptLimit == null) {
            throw new InvalidArgumentException("an absent attempt limit is OptionalInt.empty()");
        }
        if (attemptLimit.isPresent() && attemptLimit.getAsInt() < 1) {
            throw new InvalidArgumentException(
                    "an attempt limit is at least 1, not " + attemptLimit.getAsInt());
        }
    }

    /** These options with the attempt limit given. */
    public RouteOptions withAttemptLimit(int limit) {
        return new RouteOptions(OptionalInt.of(limit), explain);
    }

    /** These options with the explain record asked for or not. */
    public RouteOptions withExplain(boolean wanted) {
        return new RouteOptions(attemptLimit, wanted);
    }
}
