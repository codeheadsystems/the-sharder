package com.codeheadsystems.sharder.config;

import com.codeheadsystems.sharder.error.InvalidArgumentException;
import com.codeheadsystems.sharder.topology.TopologyProvider;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Where documents come from and how long one stays in force, under {@code CFG-010}.
 *
 * <p>Every value is validated here rather than clamped, under {@code CFG-003}: a setting outside
 * its range refuses construction with {@code invalidArgument}.
 */
public record ProviderSettings(
        TopologyProvider provider,
        Optional<String> expectedTopologyId,
        OptionalLong minEpoch,
        int pollIntervalMillis,
        int reconcileIntervalMillis,
        int initialTimeoutMillis,
        int providerRetryBaseMillis,
        int providerRetryCapMillis,
        boolean providerRetryJitter,
        int staleAfterMillis,
        StalePolicy stalePolicy,
        int retentionDepth,
        int maxKeyBytes,
        int directoryWarnEntries,
        int rendezvousWarnVirtualNodes,
        int ringWarnTokens) {

    /** The ranges of {@code CFG-010} and {@code CFG-011}. */
    public ProviderSettings {
        if (provider == null) {
            throw new InvalidArgumentException("a provider is required, under CFG-010");
        }
        Settings.atLeast("pollIntervalMillis", pollIntervalMillis, 1);
        Settings.atLeast("initialTimeoutMillis", initialTimeoutMillis, 0);
        Settings.atLeast("providerRetryBaseMillis", providerRetryBaseMillis, 1);
        Settings.atLeast("providerRetryCapMillis", providerRetryCapMillis, providerRetryBaseMillis);
        Settings.atLeast("staleAfterMillis", staleAfterMillis, 0);
        Settings.atLeast("retentionDepth", retentionDepth, 0);
        Settings.atLeast("maxKeyBytes", maxKeyBytes, 1);
        Settings.atLeast("directoryWarnEntries", directoryWarnEntries, 0);
        Settings.atLeast("rendezvousWarnVirtualNodes", rendezvousWarnVirtualNodes, 0);
        Settings.atLeast("ringWarnTokens", ringWarnTokens, 0);
        // CFG-011: a provider that both pushes and pulls is reconciled rarely, because the push
        // path carries the change.
        Settings.atLeast("reconcileIntervalMillis", reconcileIntervalMillis, pollIntervalMillis);
    }

    /** The defaults of {@code CFG-010} over one provider. */
    public static ProviderSettings defaults(TopologyProvider provider) {
        return new ProviderSettings(provider, Optional.empty(), OptionalLong.empty(), 30000, 300000,
                10000, 1000, 60000, true, 0, StalePolicy.SERVE, 3, 65536, 10000, 4096, 1000000);
    }
}
