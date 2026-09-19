package com.codeheadsystems.sharder.migrate;

import java.util.List;

/**
 * What one rebase did to each handoff, under {@code MOVE-094}.
 *
 * <p>A handoff at {@code cutover} or beyond is reported as unchanged, under {@code MOVE-099}: it
 * keeps the target epoch it held at that transition and runs to a terminal state under it.
 */
public record RebaseReport(long fromEpoch, long toEpoch, List<HandoffId> rebased,
                           List<HandoffId> aborted, List<HandoffId> unchanged) {

    /** Every list is copied. */
    public RebaseReport {
        rebased = List.copyOf(rebased);
        aborted = List.copyOf(aborted);
        unchanged = List.copyOf(unchanged);
    }
}
