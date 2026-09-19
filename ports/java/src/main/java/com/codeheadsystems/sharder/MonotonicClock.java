package com.codeheadsystems.sharder;

/**
 * The instant source every elapsed-interval rule of the specification reads.
 *
 * <p>An instant is a count of milliseconds from a monotonic source. {@code System.nanoTime} has an
 * arbitrary origin that is frequently negative, and a negative instant would make the
 * elapsed-interval arithmetic of {@code HEALTH-044}, {@code HEALTH-045}, and {@code MOVE-331} read
 * as though a deadline had already passed, so {@link #systemNanoTime()} records a base reading at
 * construction and answers the elapsed milliseconds since it.
 */
@FunctionalInterface
public interface MonotonicClock {

    /** The instant now, in milliseconds, which never decreases and is never negative. */
    long millis();

    /** A clock over {@code System.nanoTime} whose first reading is zero. */
    static MonotonicClock systemNanoTime() {
        long base = System.nanoTime();
        return () -> (System.nanoTime() - base) / 1_000_000L;
    }

    /** A clock a test advances by hand, starting at {@code start}. */
    static MonotonicClock fixed(long start) {
        return () -> start;
    }
}
