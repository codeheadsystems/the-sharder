package com.codeheadsystems.sharder.migrate;

/**
 * Where backpressure comes from, under {@code RATE-061}.
 *
 * <p>It comes from here and nowhere else: the library infers no rate from a wall clock, probes no
 * node, and derives no level from its own measurements, under {@code RATE-131}. An absent gauge is
 * one that always answers {@code none}.
 */
@FunctionalInterface
public interface PressureGauge {

    /** The level in force for one scope. */
    PressureLevel level(PressureScope scope);

    /** The gauge an unset setting stands for. */
    static PressureGauge none() {
        return scope -> PressureLevel.NONE;
    }
}
