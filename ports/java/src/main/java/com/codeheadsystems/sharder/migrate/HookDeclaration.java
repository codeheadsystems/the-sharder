package com.codeheadsystems.sharder.migrate;

/**
 * What a hook implementation states about itself, under {@code MOVE-111}.
 *
 * <p>{@code budgetUnit} is opaque to the library: it is reported and never interpreted, under
 * {@code MOVE-141}. {@code supportsVerify} of false is what {@code MOVE-181} reads when it decides
 * whether {@code cleanup} may follow a committed cutover directly.
 */
public record HookDeclaration(String budgetUnit, boolean supportsRollback,
                              boolean supportsVerify) {

    /** A declaration in units of {@code budgetUnit} supporting both rollback and verification. */
    public static HookDeclaration of(String budgetUnit) {
        return new HookDeclaration(budgetUnit, true, true);
    }
}
