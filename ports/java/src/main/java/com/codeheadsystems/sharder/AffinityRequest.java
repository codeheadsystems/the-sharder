package com.codeheadsystems.sharder;

import java.util.List;
import java.util.OptionalInt;

/**
 * Where a read is coming from, under {@code READ-010}.
 *
 * <p>The level names a failure domain level of the topology, and the path names the caller's own
 * domain values from the coarsest level through that one. The window bounds how far down the
 * preference list the reordering may reach, and an absent window takes the configured one.
 */
public record AffinityRequest(String level, List<String> path, OptionalInt window) {

    /** The path is copied, so a caller reusing its own list changes no request it made. */
    public AffinityRequest {
        path = List.copyOf(path);
        if (window == null) {
            throw new IllegalArgumentException("an absent window is OptionalInt.empty()");
        }
    }

    /** A request at {@code level} along {@code path}, taking the configured window. */
    public static AffinityRequest of(String level, List<String> path) {
        return new AffinityRequest(level, path, OptionalInt.empty());
    }
}
