package com.codeheadsystems.sharder.config;

import java.util.Map;
import java.util.Optional;

/**
 * The values in force, including the defaults the integrator did not set, under {@code CFG-007}.
 *
 * <p>An operator reading a support case needs the values the library is running with rather than
 * the values a configuration file carries, and the two differ by every default.
 */
public interface ConfigurationView {

    /** Every setting, by name, in a stable order, rendered as text. */
    Map<String, String> settings();

    /** One setting, absent where no setting carries the name. */
    default Optional<String> setting(String name) {
        return Optional.ofNullable(settings().get(name));
    }
}
