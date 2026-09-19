package com.codeheadsystems.sharder.migrate;

/**
 * The eleven handoff states of {@code MOVE-001}.
 *
 * <p>{@code complete}, {@code aborted}, and {@code failed} are terminal, and nothing transitions
 * out of one except the re-observation of {@code MOVE-233}, which the integrator calls for one
 * named handoff. No transition out of a terminal state is automatic.
 */
public enum HandoffState {
    /** Admitted to the plan, no hook called. */
    PLANNED("planned"),
    /** The destination is being made ready to receive. */
    PREPARING("preparing"),
    /** The bulk contents are being copied. */
    TRANSFERRING("transferring"),
    /** The residue accumulated during the copy is being closed. */
    CATCHING_UP("catchingUp"),
    /** The source is quiescing and the cutover record is being committed. */
    CUTOVER("cutover"),
    /** The destination copy is being checked against the source. */
    VERIFYING("verifying"),
    /** The source copy is being released. */
    CLEANUP("cleanup"),
    /** Terminal: ownership moved and the source was released. */
    COMPLETE("complete"),
    /** Compensation is running after an abort. */
    ABORTING("aborting"),
    /** Terminal: ownership did not move and no residue remains. */
    ABORTED("aborted"),
    /** Terminal: operator action is required. */
    FAILED("failed");

    private final String spelling;

    HandoffState(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling a vector, a scenario, and an event join on. */
    public String spelling() {
        return spelling;
    }

    /** Whether the state is terminal under {@code MOVE-031}. */
    public boolean terminal() {
        return this == COMPLETE || this == ABORTED || this == FAILED;
    }

    /** The state that spelling names. */
    public static HandoffState of(String spelling) {
        for (HandoffState state : values()) {
            if (state.spelling.equals(spelling)) {
                return state;
            }
        }
        throw new IllegalArgumentException("no handoff state named " + spelling);
    }
}
