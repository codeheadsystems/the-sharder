package com.codeheadsystems.sharder.topology;

import java.util.Objects;
import java.util.Optional;

/**
 * What a pull answers, under {@code CORE-085} and {@code CORE-086}.
 *
 * <p>A provider delivers octets, which is the first of the two forms {@code CORE-083} permits.
 * Validation, canonicalisation, digesting, monotonicity, and preparation are the library's, and a
 * provider never constructs a snapshot.
 */
public sealed interface Loaded {

    /** A document, with the version the source holds it at. */
    record Document(byte[] document, Optional<SourceVersion> version) implements Loaded {

        /** The octets are copied on the way in and on the way out. */
        public Document {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(version, "version");
            document = document.clone();
        }

        @Override
        public byte[] document() {
            return document.clone();
        }
    }

    /**
     * The answer to a load whose {@code known} the source still holds, under {@code CORE-086}.
     *
     * <p>It refreshes freshness, emits {@code sharder.topology.unchanged}, and enters no stage of
     * the load pipeline. Answering it for an empty {@code known} is {@code providerError}.
     */
    record Unchanged() implements Loaded {
    }
}
