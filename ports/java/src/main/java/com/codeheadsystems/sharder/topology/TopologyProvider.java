package com.codeheadsystems.sharder.topology;

import java.util.Optional;

/**
 * Where a topology document comes from, under {@code CORE-080}.
 *
 * <p>{@code CORE-081} requires at least one of the two capabilities to be true. A provider that
 * throws any {@code Throwable} has it caught, counted, reported as {@code providerError} with the
 * original attached as the Java cause, and followed by the backoff of {@code CORE-100}, under
 * {@code ERR-063}: that is one of the two places this library catches {@code Throwable}.
 */
public interface TopologyProvider extends AutoCloseable {

    /** What the provider supports, of which at least one is true. */
    Capabilities capabilities();

    /**
     * The document the source holds, or {@link Loaded.Unchanged} where {@code known} still names
     * it, under {@code CORE-084} to {@code CORE-087}.
     */
    Loaded load(Optional<SourceVersion> known);

    /** Delivery of every document the source comes to hold, where the provider pushes. */
    Subscription watch(TopologySink sink);

    @Override
    void close();

    /** Which of the two shapes a provider supports. */
    record Capabilities(boolean pull, boolean push) {

        /** At least one capability is true, under {@code CORE-081}. */
        public Capabilities {
            if (!pull && !push) {
                throw new IllegalArgumentException("a provider supports pull, push, or both");
            }
        }

        /** A provider a router polls. */
        public static Capabilities pullOnly() {
            return new Capabilities(true, false);
        }

        /** A provider that delivers on its own. */
        public static Capabilities pushOnly() {
            return new Capabilities(false, true);
        }
    }
}
