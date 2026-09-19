package com.codeheadsystems.sharder.migrate;

/**
 * What a hook implementation states about itself, under {@code MOVE-111}.
 *
 * <p>{@code budgetUnit} is opaque to the library: it is reported and never interpreted, under
 * {@code MOVE-141}. {@code supportsVerify} of false is what {@code MOVE-181} reads when it decides
 * whether {@code cleanup} may follow a committed cutover directly.
 *
 * <p>{@code supportsLineage} states whether the integrator's storage can divide a copy in place and
 * fold two adjacent copies back together. It is a property of that storage rather than of this
 * library, which is why it is declared here rather than derived from the strategy: {@code LIN-053}
 * refuses a plan that needs a local step where it is false, in preference to calling a hook that
 * was never implemented.
 */
public record HookDeclaration(String budgetUnit, boolean supportsRollback,
                              boolean supportsVerify, boolean supportsLineage) {

    /** A declaration in units of {@code budgetUnit} supporting rollback and verification. */
    public static HookDeclaration of(String budgetUnit) {
        return new HookDeclaration(budgetUnit, true, true, false);
    }

    /** The same, and a storage that can divide a copy in place under {@code LIN-053}. */
    public static HookDeclaration withLineage(String budgetUnit) {
        return new HookDeclaration(budgetUnit, true, true, true);
    }
}
