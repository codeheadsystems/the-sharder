package com.codeheadsystems.sharder.core.internal.health;

/**
 * The parameters of the built-in health state machine, with the defaults of {@code HEALTH-055}.
 *
 * <p>Every one of them is caller-local: health state is derived from signals a caller observes,
 * never agreed between callers, and never serialised into a document, so a setting here changes
 * nothing two callers have to agree on.
 */
public record HealthSettings(
        long windowMillis,
        int bucketCount,
        int minimumSamples,
        int failureRatePercent,
        int consecutiveFailureThreshold,
        long baseEjectionMillis,
        long maxEjectionMillis,
        long probationMillis,
        int probationDivisor,
        int outlierMarginPercent,
        int outlierMinimumNodes,
        int maxEjectionPercent,
        long ejectionResetMillis,
        boolean resetOnPlacementReentry) {

    /** The defaults of {@code HEALTH-055}. */
    public static HealthSettings defaults() {
        return new HealthSettings(30000L, 10, 20, 50, 5, 30000L, 300000L, 30000L, 16, 30, 5, 50,
                600000L, false);
    }

    /** The same settings with one window length. */
    public HealthSettings withWindowMillis(long millis) {
        return new HealthSettings(millis, bucketCount, minimumSamples, failureRatePercent,
                consecutiveFailureThreshold, baseEjectionMillis, maxEjectionMillis, probationMillis,
                probationDivisor, outlierMarginPercent, outlierMinimumNodes, maxEjectionPercent,
                ejectionResetMillis, resetOnPlacementReentry);
    }

    /**
     * The ejection interval of {@code HEALTH-050}, evaluated at entry into {@code unavailable}.
     *
     * <p>The shift is clamped at ten before it is applied, so it never overflows, and repeated
     * ejection lengthens the interval: a node that flaps is attempted less often rather than more.
     */
    public long ejectionMillis(int ejectionCount) {
        int shift = Math.min(Math.max(ejectionCount - 1, 0), 10);
        return Math.min(baseEjectionMillis << shift, maxEjectionMillis);
    }
}
