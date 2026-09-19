package com.codeheadsystems.sharder.core.internal.health;

import com.codeheadsystems.sharder.config.HealthSettings;

/**
 * The sliding observation window of {@code HEALTH-020}.
 *
 * <p>Observations are aggregated over {@code windowMillis}, divided into {@code bucketCount}
 * buckets of equal length, each holding a success count and a failure count. A bucket whose end
 * precedes the window is discarded rather than decayed, so an old failure stops counting at once
 * rather than fading.
 */
final class SlidingWindow {

    private final long bucketMillis;
    private final long[] successes;
    private final long[] failures;
    private final long[] endsAt;

    SlidingWindow(HealthSettings settings) {
        int buckets = settings.bucketCount();
        this.bucketMillis = Math.max(1L, settings.windowMillis() / buckets);
        this.successes = new long[buckets];
        this.failures = new long[buckets];
        this.endsAt = new long[buckets];
    }

    /** Records one observation at an instant, discarding whatever the window has outrun. */
    void record(long at, boolean success) {
        int bucket = bucketOf(at);
        long end = endOf(at);
        if (endsAt[bucket] != end) {
            endsAt[bucket] = end;
            successes[bucket] = 0;
            failures[bucket] = 0;
        }
        if (success) {
            successes[bucket]++;
        } else {
            failures[bucket]++;
        }
    }

    /** The success count over the window as it stands at {@code now}. */
    long successes(long now) {
        return total(now, successes);
    }

    /** The failure count over the window as it stands at {@code now}. */
    long failures(long now) {
        return total(now, failures);
    }

    /** The success and failure counts summed, which the rate tests compare against samples. */
    long total(long now) {
        return successes(now) + failures(now);
    }

    /**
     * The failure percentage of {@code HEALTH-023}, truncating towards zero, and zero where the
     * window holds nothing.
     */
    int failurePercent(long now) {
        long total = total(now);
        return total == 0 ? 0 : (int) (failures(now) * 100 / total);
    }

    private long total(long now, long[] counts) {
        long earliest = now - bucketMillis * successes.length;
        long sum = 0;
        for (int index = 0; index < counts.length; index++) {
            if (endsAt[index] > earliest) {
                sum += counts[index];
            }
        }
        return sum;
    }

    private int bucketOf(long at) {
        return (int) Math.floorMod(at / bucketMillis, (long) successes.length);
    }

    private long endOf(long at) {
        return (at / bucketMillis + 1) * bucketMillis;
    }
}
