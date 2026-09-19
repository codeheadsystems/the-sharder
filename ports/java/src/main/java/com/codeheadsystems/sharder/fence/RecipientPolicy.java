package com.codeheadsystems.sharder.fence;

/**
 * What a recipient does with a token from a sender behind, under {@code CFG-030}.
 *
 * <p>The two policies differ in one row of {@code FENCE-131}: a sender behind, or an unfenced
 * request, whose ownership is stable. Every other row of {@code FENCE-111} to {@code FENCE-151} is
 * the same under both. {@code strict} is the default, because the deployment that fences is the
 * deployment whose worst failure is two nodes believing they own one shard.
 */
public enum RecipientPolicy {

    /** A sender behind is refused whatever its ownership. */
    STRICT("strict"),

    /** A sender behind whose ownership is stable is served. */
    STABLE("stable");

    private final String spelling;

    RecipientPolicy(String spelling) {
        this.spelling = spelling;
    }

    /** The spelling the configuration surface carries. */
    public String spelling() {
        return spelling;
    }

    /** The policy that spelling names. */
    public static RecipientPolicy of(String spelling) {
        for (RecipientPolicy policy : values()) {
            if (policy.spelling.equals(spelling)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("no recipient policy named " + spelling);
    }
}
