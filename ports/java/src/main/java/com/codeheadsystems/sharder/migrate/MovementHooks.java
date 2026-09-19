package com.codeheadsystems.sharder.migrate;

/**
 * What an integrator implements to move data, under {@code MOVE-111}.
 *
 * <p>The library moves no data and reads no durable state. It sequences these calls, records what
 * they answer, and enforces the ordering the specification states: {@code cleanup} never runs
 * before the copy is established under {@code MOVE-181}, and {@code rollback} never runs after a
 * cutover record belonging to the handoff exists under {@code MOVE-191}.
 *
 * <p>Every hook is called from whichever unit of execution called {@code step}, at most one hook
 * per call, under {@code MOVE-071}. A hook that raises rather than answering has its condition
 * surfaced unchanged, under {@code ERR-063}.
 */
public interface MovementHooks {

    /** What this implementation states about itself, read once per plan. */
    HookDeclaration declare();

    /** Makes the destination ready to receive, under {@code MOVE-001}. */
    HookResult prepare(HandoffContext context);

    /** Copies bulk contents, up to {@code budget} units of the declared unit. */
    TransferResult transfer(HandoffContext context, int budget);

    /** Closes the residue accumulated during the copy, up to {@code budget} units. */
    CatchUpResult catchUp(HandoffContext context, int budget);

    /** Holds the source's writes for a lease, under {@code MOVE-331}. */
    QuiesceResult quiesce(HandoffContext context);

    /** Writes the durable record that moves authority, under {@code MOVE-041}. */
    CutoverResult commitCutover(HandoffContext context);

    /** Compares the destination copy against the source. */
    VerifyResult verify(HandoffContext context);

    /** Releases the source copy. */
    HookResult cleanup(HandoffContext context);

    /** Compensates an abort, under {@code MOVE-191}. */
    HookResult rollback(HandoffContext context);

    /**
     * Divides the copy this node holds so that it matches the extent of {@code shardId}.
     *
     * <p>A local step under {@code LIN-051}: the node holds {@code sourceShardId} under the earlier
     * snapshot and {@code shardId} under the later one, and nothing moves between nodes. The
     * default refuses, because a storage that has not implemented it declares
     * {@code supportsLineage} of false and {@code LIN-053} refuses the plan before a call is made.
     */
    default HookResult divide(HandoffContext context) {
        return new HookResult.Permanent("divide is not implemented");
    }

    /**
     * Folds the copies this node holds into one that matches the extent of {@code shardId}.
     *
     * <p>The inverse of {@link #divide}, and what {@code LIN-056} calls to undo a division that was
     * aborted, which is why a storage declares support for the pair rather than for either alone.
     */
    default HookResult combine(HandoffContext context) {
        return new HookResult.Permanent("combine is not implemented");
    }

    /** Reads the durable state, under {@code MOVE-201}. */
    ObserveResult observe(HandoffContext context);
}
