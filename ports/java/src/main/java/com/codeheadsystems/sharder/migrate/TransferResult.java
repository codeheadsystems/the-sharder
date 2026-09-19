package com.codeheadsystems.sharder.migrate;

/**
 * What one {@code transfer} call moved, under {@code MOVE-111}.
 *
 * <p>It composes a {@link HookResult} rather than extending one, because that type is sealed over
 * records and a record is final. {@code unitsMoved} and {@code bulkRemaining} are in the hook's own
 * {@code budgetUnit} and are summed and compared and nothing else, under {@code MOVE-141}.
 */
public record TransferResult(HookResult result, int unitsMoved, long bulkRemaining) {

    /** A transfer that moved {@code unitsMoved} and left {@code bulkRemaining} behind. */
    public static TransferResult of(int unitsMoved, long bulkRemaining) {
        return new TransferResult(HookResult.success(), unitsMoved, bulkRemaining);
    }
}
