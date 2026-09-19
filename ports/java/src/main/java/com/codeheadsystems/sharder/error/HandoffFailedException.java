package com.codeheadsystems.sharder.error;

import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.topology.FencingToken;

/**
 * {@code ERR-052}: one handoff of a migration failed.
 *
 * <p>The kind states what the coordinator knows about the shard, under {@code MOVE-011}.
 * {@code UNDETERMINED} is the one kind a later call leaves, under {@code MOVE-237}.
 */
public final class HandoffFailedException extends MigrationException {

    private static final long serialVersionUID = 1L;

    /** The failure kinds of {@code MOVE-011}. */
    public enum FailureKind {
        /** The recipient did not verify the shard after cutover. */
        UNVERIFIED("unverified"),
        /** Writes remained at the source after cutover. */
        RESIDUE("residue"),
        /** The coordinator does not know which side holds the shard. */
        UNDETERMINED("undetermined"),
        /** The rollback of a failed cutover failed. */
        ROLLBACK_FAILED("rollbackFailed");

        private final String spelling;

        FailureKind(String spelling) {
            this.spelling = spelling;
        }

        /** The spelling a vector and an event join on. */
        public String spelling() {
            return spelling;
        }

        /** The kind that spelling names. */
        public static FailureKind of(String spelling) {
            for (FailureKind kind : values()) {
                if (kind.spelling.equals(spelling)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("no failure kind named " + spelling);
        }
    }

    /** The condition, with what the coordinator knows about the shard. */
    public HandoffFailedException(FailureKind kind, ShardId shard, FencingToken token) {
        super(ErrorCode.HANDOFF_FAILED, kind.spelling(),
                "the handoff of " + shard.asText() + " failed at " + kind.spelling(), token,
                shard);
    }

    /** What the coordinator knows about the shard. */
    public FailureKind kind() {
        return FailureKind.of(cause().orElseThrow());
    }
}
