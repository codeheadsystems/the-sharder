package com.codeheadsystems.sharder.core.internal.route;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The retry budget of {@code FAIL-030} through {@code FAIL-035}.
 *
 * <p>The budget is held per router instance and spans every key, shard, and node that instance
 * routes, under {@code FAIL-033}: a per-key budget would permit a storm assembled from many keys.
 * A first attempt is always permitted under {@code FAIL-032}, so the budget cannot make a key
 * unroutable.
 *
 * <p>One budget serves every unit of execution a router is called from, under {@code CORE-055}, so
 * the window is held under this object's lock. The Java binding describes the window as an array of
 * {@code LongAdder} counters, which costs an uncontended write per first attempt; this window holds
 * the instant of each attempt instead, because {@code FAIL-031} counts over the window to the
 * millisecond and a bucketed counter answers differently at a bucket boundary. The lock is what
 * that exactness costs, and it is taken once per attempt rather than on the placement path.
 */
public final class RetryBudget {

    private record Accounted(long at, boolean retry) {
    }

    private final long windowMillis;
    private final long percent;
    private final long minimum;
    private final Deque<Accounted> window = new ArrayDeque<>();

    /** The budget under the defaults of {@code FAIL-035}. */
    public static RetryBudget defaults() {
        return new RetryBudget(10000L, 20L, 3L);
    }

    /** The budget under the given parameters. */
    public RetryBudget(long windowMillis, long percent, long minimum) {
        this.windowMillis = windowMillis;
        this.percent = percent;
        this.minimum = minimum;
    }

    /**
     * Whether a retry is permitted over the window, under {@code FAIL-031}.
     *
     * <p>{@code retries * 100 <= retryBudgetPercent * firstAttempts + 100 * retryBudgetMinimum},
     * evaluated exactly under {@code CORE-005} whatever the window totals reach.
     */
    public synchronized boolean permitted(long now) {
        return permitted(count(now, true), count(now, false), percent, minimum);
    }

    /** The comparison over explicit operands, which the formula vectors assert directly. */
    public static boolean permitted(long retries, long firstAttempts, long percent, long minimum) {
        return retries * 100 <= percent * firstAttempts + 100 * minimum;
    }

    /** Accounts one attempt, which is a first attempt or a retry. */
    public synchronized void account(long at, boolean retry) {
        window.addLast(new Accounted(at, retry));
        evict(at);
    }

    private long count(long now, boolean retry) {
        evict(now);
        return window.stream().filter(entry -> entry.retry() == retry).count();
    }

    private void evict(long now) {
        while (!window.isEmpty() && now - window.peekFirst().at() >= windowMillis) {
            window.removeFirst();
        }
    }
}
