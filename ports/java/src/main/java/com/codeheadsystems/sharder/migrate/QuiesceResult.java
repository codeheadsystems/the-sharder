package com.codeheadsystems.sharder.migrate;

/**
 * What one {@code quiesce} call granted, under {@code MOVE-111}.
 *
 * <p>{@code leaseMillis} is how long the source will hold its writes. The coordinator subtracts the
 * margin of {@code MOVE-332} from it before comparing, because the source enforces the lease on its
 * own clock and the coordinator evaluates it on the integrator's.
 */
public record QuiesceResult(HookResult result, int leaseMillis) {

    /** A quiesce granting a lease of {@code leaseMillis}. */
    public static QuiesceResult of(int leaseMillis) {
        return new QuiesceResult(HookResult.success(), leaseMillis);
    }
}
