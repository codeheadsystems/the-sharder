package com.codeheadsystems.sharder.error;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.PreferenceEntry;
import java.util.List;

/**
 * {@code ERR-022}: the attempt sequence has no further node to offer.
 *
 * <p>The cause states which of the three bounds ended the sequence, and the entries and the
 * attempts state what it offered before it did.
 */
public final class ExhaustedException extends RoutingException {

    private static final long serialVersionUID = 1L;

    /** None */
    public enum Cause {
        /** Every entry of the preference list was attempted. */
        PREFERENCE_LIST("preferenceList"),
        /** The attempt limit of {@code CFG-020} was reached. */
        ATTEMPT_LIMIT("attemptLimit"),
        /** The retry budget of {@code FAIL-030} refused a further try. */
        RETRY_BUDGET("retryBudget");

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

    private final transient List<PreferenceEntry> entries;
    private final transient List<NodeId> attempted;
    private final transient List<String> outcomes;

    /** The condition, with the cause and what the sequence offered and the caller reported. */
    public ExhaustedException(Cause reason, List<PreferenceEntry> entries,
                              List<NodeId> attempted, List<String> outcomes) {
        super(ErrorCode.EXHAUSTED, reason.spelling(),
                "the attempt sequence ended at " + reason.spelling());
        this.entries = List.copyOf(entries);
        this.attempted = List.copyOf(attempted);
        this.outcomes = List.copyOf(outcomes);
    }

    /** The bound that ended the sequence. */
    public Cause reason() {
        return Cause.of(cause().orElseThrow());
    }

    /** The materialised prefix of {@code CORE-046} the decision carried. */
    public List<PreferenceEntry> entries() {
        return entries;
    }

    /** The nodes the sequence offered, in the order it offered them. */
    public List<NodeId> attempted() {
        return attempted;
    }

    /** The outcomes the caller reported, in the order it reported them. */
    public List<String> outcomes() {
        return outcomes;
    }
}
