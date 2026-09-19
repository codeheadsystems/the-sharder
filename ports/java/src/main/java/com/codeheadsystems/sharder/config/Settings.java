package com.codeheadsystems.sharder.config;

import com.codeheadsystems.sharder.error.InvalidArgumentException;

/**
 * The range checks every settings record shares, under {@code CFG-003}.
 *
 * <p>A value outside its range refuses construction with {@code invalidArgument}, under
 * {@code ERR-025}, rather than being clamped: a clamped setting runs a deployment on a value
 * nobody chose.
 */
final class Settings {

    private Settings() {
    }

    /** Refuses a value below {@code floor}, naming the setting. */
    static void atLeast(String name, long value, long floor) {
        if (value < floor) {
            throw new InvalidArgumentException(
                    name + " is at least " + floor + ", not " + value);
        }
    }

    /** Refuses a value outside {@code floor} through {@code ceiling}, naming the setting. */
    static void between(String name, long value, long floor, long ceiling) {
        if (value < floor || value > ceiling) {
            throw new InvalidArgumentException(
                    name + " is from " + floor + " through " + ceiling + ", not " + value);
        }
    }
}
