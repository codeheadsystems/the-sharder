package com.codeheadsystems.sharder.config;

import com.codeheadsystems.sharder.error.InvalidArgumentException;
import java.util.OptionalInt;

/**
 * What one routing call inherits where it supplies nothing, under {@code CFG-020}.
 *
 * <p>An unset {@code attemptLimit} resolves to the replication factor plus two, under
 * {@code CORE-048}, and an unset {@code readAffinityWindow} to the replica count, under
 * {@code READ-012}. Both are resolved against the snapshot in force rather than stored resolved,
 * because the factor is a property of the document.
 */
public record RoutingSettings(
        OptionalInt attemptLimit,
        int retryBudgetWindowMillis,
        int retryBudgetPercent,
        int retryBudgetMinimum,
        OptionalInt readAffinityWindow) {

    /** The ranges of {@code CFG-020} and {@code CFG-021}. */
    public RoutingSettings {
        if (attemptLimit == null || readAffinityWindow == null) {
            throw new InvalidArgumentException("an absent setting is OptionalInt.empty()");
        }
        // CFG-021: a routing call that permits no attempt is a configuration defect rather than a
        // load shedding policy.
        attemptLimit.ifPresent(limit -> Settings.atLeast("attemptLimit", limit, 1));
        readAffinityWindow.ifPresent(window -> Settings.atLeast("readAffinityWindow", window, 0));
        Settings.atLeast("retryBudgetWindowMillis", retryBudgetWindowMillis, 1);
        Settings.between("retryBudgetPercent", retryBudgetPercent, 0, 100);
        Settings.atLeast("retryBudgetMinimum", retryBudgetMinimum, 0);
    }

    /** The defaults of {@code CFG-020}. */
    public static RoutingSettings defaults() {
        return new RoutingSettings(OptionalInt.empty(), 10000, 20, 3, OptionalInt.empty());
    }
}
