package com.codeheadsystems.sharder.error;

import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.topology.FencingToken;

/**
 * {@code ERR-041}: the token's epoch and the recipient's differ.
 *
 * <p>The cause states which side is behind, because the caller's response differs: a sender behind
 * refreshes and retries, and a sender ahead waits for the recipient to catch up.
 */
public final class EpochMismatchException extends RecipientException {

    private static final long serialVersionUID = 1L;

    /** None */
    public enum Cause {
        /** The token names an epoch below the recipient's. */
        SENDER_BEHIND("senderBehind"),
        /** The token names an epoch above the recipient's. */
        SENDER_AHEAD("senderAhead"),
        /** The recipient holds no epoch of its own. */
        UNFENCED("unfenced");

        private final String spelling;

        Cause(String spelling) {
            this.spelling = spelling;
        }

        /** The spelling a vector and an event join on. */
        public String spelling() {
            return spelling;
        }

        /** The cause that spelling names. */
        public static Cause of(String spelling) {
            for (Cause cause : values()) {
                if (cause.spelling.equals(spelling)) {
                    return cause;
                }
            }
            throw new IllegalArgumentException("no cause named " + spelling);
        }
    }

    /** The condition, with the direction of the difference and the token that carried it. */
    public EpochMismatchException(Cause reason, FencingToken token, ShardId shard) {
        super(ErrorCode.EPOCH_MISMATCH, reason.spelling(),
                "the token and the recipient disagree: " + reason.spelling(), token, shard);
    }

    /** Which side is behind. */
    public Cause reason() {
        return Cause.of(cause().orElseThrow());
    }
}
