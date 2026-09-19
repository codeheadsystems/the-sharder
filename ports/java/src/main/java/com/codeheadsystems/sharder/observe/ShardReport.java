package com.codeheadsystems.sharder.observe;

/**
 * What an integrator's own counters say about one shard over one interval, under {@code OBS-036}.
 *
 * <p>The library counts the decisions it was asked for and knows nothing about what a node then
 * did, so hot shard detection under {@code OBS-031} and key skew under {@code OBS-032} read these
 * values beside the library's own counters and nothing else: no node is probed, no traffic is
 * sampled, and no load is inferred from a wall clock, under {@code OBS-030}.
 *
 * <p>Every member is an integer count, under {@code OBS-036}, because {@code OBS-033} keeps the
 * comparisons in unsigned integer arithmetic whatever a metric surface reports.
 */
public record ShardReport(long requests, long hottestKeyRequests, int intervalMillis) {

    /** The interval is a duration rather than an instant, so it is never negative. */
    public ShardReport {
        if (intervalMillis < 0) {
            throw new IllegalArgumentException("intervalMillis is not negative: " + intervalMillis);
        }
    }
}
