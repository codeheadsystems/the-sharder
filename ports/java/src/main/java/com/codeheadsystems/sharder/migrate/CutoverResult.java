package com.codeheadsystems.sharder.migrate;

/**
 * What one {@code commitCutover} call established, under {@code MOVE-111}.
 *
 * <p>The six answers are distinct on purpose. {@code AlreadyCommitted} is what makes a retried
 * cutover idempotent; {@code Lost} states that the record names another owner, which aborts the
 * handoff under {@code MOVE-351}; and {@code Undetermined} states that the hook does not know,
 * which is the one kind a later call leaves, under {@code MOVE-237}.
 */
public sealed interface CutoverResult {

    /** The record was written by this call. */
    record Committed(CutoverRecord record) implements CutoverResult {
    }

    /** The record was already there, naming this handoff's destination. */
    record AlreadyCommitted(CutoverRecord record) implements CutoverResult {
    }

    /** A record is there and names another owner. */
    record Lost(CutoverRecord record) implements CutoverResult {
    }

    /** Whether a record exists is not established. */
    record Undetermined() implements CutoverResult {
    }

    /** The commit failed in a way a further attempt may resolve. */
    record Retryable(String reason) implements CutoverResult {
    }

    /** The commit failed in a way no further attempt resolves. */
    record Permanent(String reason) implements CutoverResult {
    }
}
