package com.codeheadsystems.sharder.core;

import com.codeheadsystems.sharder.topology.Loaded;
import com.codeheadsystems.sharder.topology.SourceVersion;
import com.codeheadsystems.sharder.topology.Subscription;
import com.codeheadsystems.sharder.topology.TopologyProvider;
import com.codeheadsystems.sharder.topology.TopologySink;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A provider holding one document in memory, which both pulls and pushes.
 *
 * <p>It is the provider a test and an embedded deployment use, and it is what the library is
 * developed against: an integrator with a document already in hand needs no file, no server, and no
 * adapter to route against it. {@link #publish} replaces the document and delivers it to every
 * subscriber synchronously.
 */
public final class InMemoryTopologyProvider implements TopologyProvider {

    private final AtomicReference<byte[]> document = new AtomicReference<>();
    private final AtomicLong revision = new AtomicLong();
    private final CopyOnWriteArrayList<TopologySink> sinks = new CopyOnWriteArrayList<>();

    /** A provider holding no document, which answers a pull with nothing until one is published. */
    public InMemoryTopologyProvider() {
    }

    /** A provider holding {@code document}. */
    public InMemoryTopologyProvider(byte[] document) {
        publish(document);
    }

    /** Replaces the document and delivers it to every subscriber. */
    public void publish(byte[] octets) {
        document.set(octets.clone());
        long version = revision.incrementAndGet();
        sinks.forEach(sink -> sink.onDocument(octets.clone(), Optional.of(version(version))));
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true);
    }

    @Override
    public Loaded load(Optional<SourceVersion> known) {
        byte[] octets = document.get();
        if (octets == null) {
            throw new IllegalStateException("the provider holds no document");
        }
        SourceVersion current = version(revision.get());
        // CORE-086: a load whose `known` the source still holds enters no stage of the pipeline.
        if (known.isPresent() && known.get().equals(current)) {
            return new Loaded.Unchanged();
        }
        return new Loaded.Document(octets, Optional.of(current));
    }

    @Override
    public Subscription watch(TopologySink sink) {
        sinks.add(sink);
        return () -> sinks.remove(sink);
    }

    @Override
    public void close() {
        sinks.clear();
    }

    private static SourceVersion version(long value) {
        return SourceVersion.of(
                Long.toString(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
