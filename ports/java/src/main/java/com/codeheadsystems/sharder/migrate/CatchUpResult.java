package com.codeheadsystems.sharder.migrate;

/**
 * What one {@code catchUp} call closed, under {@code MOVE-111}.
 *
 * <p>{@code residue} is what the cutover thresholds of {@code CFG-050} are compared against: at or
 * below {@code catchUpResidualThreshold} the handoff reaches {@code cutover}, and above
 * {@code reTransferResidualThreshold} it returns to {@code transferring}.
 */
public record CatchUpResult(HookResult result, int unitsMoved, long residue) {

    /** A catch-up that moved {@code unitsMoved} and left {@code residue} behind. */
    public static CatchUpResult of(int unitsMoved, long residue) {
        return new CatchUpResult(HookResult.success(), unitsMoved, residue);
    }
}
