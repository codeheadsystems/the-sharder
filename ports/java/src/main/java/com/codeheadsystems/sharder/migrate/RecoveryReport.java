package com.codeheadsystems.sharder.migrate;

import java.util.List;

/**
 * What one recovery pass established, under {@code MOVE-211} and {@code MOVE-232}.
 *
 * <p>Recovery answers a value rather than raising, because an unresolved handoff is an outcome the
 * integrator acts on rather than a failure of the call. {@code retryAfterMillis} is what the
 * integrator waits before calling again where anything is unresolved.
 */
public record RecoveryReport(List<HandoffId> resolved, List<HandoffId> unresolved,
                             List<HandoffId> failed, int retryAfterMillis) {

    /** Every list is copied. */
    public RecoveryReport {
        resolved = List.copyOf(resolved);
        unresolved = List.copyOf(unresolved);
        failed = List.copyOf(failed);
    }
}
