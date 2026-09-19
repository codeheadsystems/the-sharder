package com.codeheadsystems.sharder.migrate;

/**
 * What one hook call did, under {@code MOVE-111}.
 *
 * <p>{@code Retryable} is retried up to {@code maxAttemptsPerStep} with the backoff of
 * {@code RATE-051}; {@code Permanent} is never retried; and {@code Deferred} counts against no
 * attempt budget and is not retried before its interval has elapsed on the supplied clock, under
 * {@code MOVE-171}. A deferral is backpressure for its own handoff alone, under {@code RATE-101}.
 */
public sealed interface HookResult {

    /** The hook did what it was asked. */
    record Success() implements HookResult {
    }

    /** The hook asks to be called again no sooner than the interval. */
    record Deferred(int retryAfterMillis) implements HookResult {

        /** The interval is a duration rather than an instant, so it is never negative. */
        public Deferred {
            if (retryAfterMillis < 0) {
                throw new IllegalArgumentException(
                        "retryAfterMillis is not negative: " + retryAfterMillis);
            }
        }
    }

    /** The hook failed in a way a further attempt may resolve. */
    record Retryable(String reason) implements HookResult {
    }

    /** The hook failed in a way no further attempt resolves. */
    record Permanent(String reason) implements HookResult {
    }

    /** The success every hook that did what it was asked answers. */
    static HookResult success() {
        return new Success();
    }
}
