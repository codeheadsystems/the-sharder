package com.codeheadsystems.sharder.observe;

import com.codeheadsystems.sharder.ShardId;
import java.util.Optional;

/**
 * Where the shard counters of {@code OBS-036} come from.
 *
 * <p>An absent report is a shard the integrator has no counters for, which is an answer rather than
 * a failure: a shard nothing has asked for yet is neither hot nor skewed.
 */
@FunctionalInterface
public interface ShardMetricsSource {

    /** The report for one shard, absent where the source holds none. */
    Optional<ShardReport> report(ShardId shard);
}
