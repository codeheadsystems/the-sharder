package com.codeheadsystems.sharder.config;

import com.codeheadsystems.sharder.error.InvalidArgumentException;
import com.codeheadsystems.sharder.observe.EventSink;
import com.codeheadsystems.sharder.observe.MetricsRegistry;
import java.util.Optional;

/**
 * Where metrics and events go, and what a label may carry, under {@code CFG-060}.
 *
 * <p>{@code includeKeysInDiagnostics} defaults to false, so a key that is a tenant identifier, an
 * account number, or a user identifier does not reach a log by default. It relaxes nothing in
 * {@code OBS-022}, which forbids keys in events under every setting.
 */
public record ObservabilitySettings(
        Optional<MetricsRegistry> metricsRegistry,
        Optional<EventSink> eventSink,
        int shardLabelLimit,
        int nodeLabelLimit,
        int hotShardFactorPercent,
        int keySkewPercent,
        boolean includeKeysInDiagnostics) {

    /** The ranges of {@code CFG-060}. */
    public ObservabilitySettings {
        if (metricsRegistry == null || eventSink == null) {
            throw new InvalidArgumentException("an absent setting is Optional.empty()");
        }
        Settings.atLeast("shardLabelLimit", shardLabelLimit, 0);
        Settings.atLeast("nodeLabelLimit", nodeLabelLimit, 0);
        Settings.atLeast("hotShardFactorPercent", hotShardFactorPercent, 100);
        Settings.between("keySkewPercent", keySkewPercent, 0, 100);
    }

    /** The defaults of {@code CFG-060}. */
    public static ObservabilitySettings defaults() {
        return new ObservabilitySettings(Optional.empty(), Optional.empty(), 1024, 1024, 400, 50,
                false);
    }
}
