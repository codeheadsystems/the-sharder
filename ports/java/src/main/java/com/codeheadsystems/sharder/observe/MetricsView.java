package com.codeheadsystems.sharder.observe;

import java.util.Map;
import java.util.Optional;

/**
 * The metrics the library holds where no registry is supplied, under {@code OBS-004}.
 *
 * <p>The view is a reading rather than a subscription: it answers what the counters hold when it is
 * called, and a caller that wants a series calls it again.
 */
public interface MetricsView {

    /** Every counter the library holds, by name and labels. */
    Map<String, Long> counters();

    /** One counter, absent where nothing has recorded it. */
    Optional<Long> counter(String name, Labels labels);

    /** Every event the library counted by name, where no sink is supplied. */
    Map<String, Long> events();
}
