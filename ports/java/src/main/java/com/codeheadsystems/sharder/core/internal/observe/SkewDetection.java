package com.codeheadsystems.sharder.core.internal.observe;

import com.codeheadsystems.sharder.core.internal.hash.U64;

/**
 * Hot-shard and key-skew detection, under {@code OBS-031} through {@code OBS-033}.
 *
 * <p>Both comparisons are evaluated exactly, under {@code CORE-005}. The left side of the hot-shard
 * comparison is a product of three operands that reaches 91 bits within the declared ranges, and
 * the left side of the key-skew comparison reaches 71 bits, so neither is a 64-bit multiplication
 * and neither may be evaluated in floating point, which {@code OBS-033} states whatever the metric
 * surface reports.
 */
public final class SkewDetection {

    private SkewDetection() {
    }

    /**
     * {@code OBS-031}: whether
     * {@code shardRequests * shardCount * 100 >= totalRequests * hotShardFactorPercent}.
     *
     * <p>A strategy that enumerates no shard has no hot shard, so a shard count of zero answers
     * false whatever the counters hold.
     */
    public static boolean shardIsHot(long shardRequests, long shardCount, long totalRequests,
                                     long hotShardFactorPercent) {
        if (shardCount == 0) {
            return false;
        }
        // The left side names three operands, and the two small ones fold into one before the
        // comparison: `shardCount` is bounded by `slotCount` under SLOT-001, so `shardCount * 100`
        // is exact in a `long` and the comparison stays a product against a product.
        return U64.compareProducts(shardRequests, shardCount * 100L,
                totalRequests, hotShardFactorPercent) >= 0;
    }

    /** {@code OBS-032}: whether {@code hottestKeyRequests * 100 >= requests * keySkewPercent}. */
    public static boolean keySkew(long hottestKeyRequests, long requests, long keySkewPercent) {
        return U64.compareProducts(hottestKeyRequests, 100L, requests, keySkewPercent) >= 0;
    }
}
