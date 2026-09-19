package com.codeheadsystems.sharder.observe;

/**
 * Where metrics go, under {@code OBS-003}.
 *
 * <p>Where no registry is supplied the values are held internally and read whole through
 * {@code Router.metrics()}, under {@code OBS-004}.
 */
public interface MetricsRegistry {

    /** Adds {@code delta} to a counter. */
    void counter(String name, Labels labels, long delta);

    /** Sets a gauge. */
    void gauge(String name, Labels labels, double value);

    /** Records one observation in a histogram. */
    void histogram(String name, Labels labels, double value);
}
