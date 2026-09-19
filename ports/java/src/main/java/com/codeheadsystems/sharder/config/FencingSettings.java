package com.codeheadsystems.sharder.config;

import com.codeheadsystems.sharder.error.InvalidArgumentException;
import com.codeheadsystems.sharder.fence.RecipientPolicy;

/**
 * What a recipient does with a token, under {@code CFG-040}.
 *
 * <p>{@code recipientPolicy} defaults to {@code strict}, because the deployment that fences is the
 * deployment whose worst failure is two nodes believing they own one shard.
 */
public record FencingSettings(
        RecipientPolicy recipientPolicy,
        int maxRedirects,
        int refreshWaitMillis,
        boolean tokenDigest) {

    /** The ranges of {@code CFG-040}. */
    public FencingSettings {
        if (recipientPolicy == null) {
            throw new InvalidArgumentException("a recipient policy is never null");
        }
        Settings.atLeast("maxRedirects", maxRedirects, 0);
        Settings.atLeast("refreshWaitMillis", refreshWaitMillis, 0);
    }

    /** The defaults of {@code CFG-040}. */
    public static FencingSettings defaults() {
        return new FencingSettings(RecipientPolicy.STRICT, 2, 0, false);
    }
}
