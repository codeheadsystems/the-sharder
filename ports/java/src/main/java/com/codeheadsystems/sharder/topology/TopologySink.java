package com.codeheadsystems.sharder.topology;

import java.util.Optional;

/** Where a push provider delivers a document, under {@code CORE-080}. */
public interface TopologySink {

    /** A document the source now holds, with the version it holds it at. */
    void onDocument(byte[] document, Optional<SourceVersion> version);

    /**
     * A failure the provider met.
     *
     * <p>The library counts it, reports it as {@code providerError} with the original attached as
     * the Java cause, and applies the backoff of {@code CORE-100}, under {@code ERR-063}.
     */
    void onError(Throwable error);
}
