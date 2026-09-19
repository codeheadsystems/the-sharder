package com.codeheadsystems.sharder.provider.file;

import com.codeheadsystems.sharder.error.ProviderException;
import com.codeheadsystems.sharder.topology.Loaded;
import com.codeheadsystems.sharder.topology.SourceVersion;
import com.codeheadsystems.sharder.topology.Subscription;
import com.codeheadsystems.sharder.topology.TopologyProvider;
import com.codeheadsystems.sharder.topology.TopologySink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A topology document read from a file, under {@code CORE-080}.
 *
 * <p>The provider pulls and does not push: a file changes without telling anyone, so the router
 * polls it on the executor the integrator supplied, under {@code CFG-012}. A deployment that wants
 * the change carried promptly watches the file itself and calls {@code refresh}.
 *
 * <p>The source version is the modification time and the size, which is what turns a poll of an
 * unchanged file into {@code Unchanged} rather than a re-read and a re-validation, under
 * {@code CORE-086}. Two writes inside one file system timestamp granularity that leave the size
 * unchanged are the case it misses, and a digest would cost a full read to find out, which is what
 * the conditional fetch exists to avoid.
 */
public final class FileTopologyProvider implements TopologyProvider {

    private final Path path;

    /** The provider over one file, which is read on each load rather than held. */
    public FileTopologyProvider(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.pullOnly();
    }

    @Override
    public Loaded load(Optional<SourceVersion> known) {
        try {
            SourceVersion current = version();
            if (known.filter(current::equals).isPresent()) {
                return new Loaded.Unchanged();
            }
            byte[] document = Files.readAllBytes(path);
            // The version answered is the one read before the octets. A write that lands between
            // the two leaves this answer carrying the older version, so the next load sees a
            // version it does not hold and reads the file again. Answering the newer version
            // would pair it with the older octets and every later load would answer `Unchanged`.
            return new Loaded.Document(document, Optional.of(current));
        } catch (IOException failure) {
            throw new ProviderException("the document at " + path + " could not be read", failure);
        }
    }

    @Override
    public Subscription watch(TopologySink sink) {
        throw new UnsupportedOperationException("a file provider pulls and does not push");
    }

    @Override
    public void close() {
        // The provider holds no handle between calls, so there is nothing to release.
    }

    /** The modification time and the size, which is what {@code CORE-084} leaves to a provider. */
    private SourceVersion version() throws IOException {
        String stamp = Files.getLastModifiedTime(path).toMillis() + ":" + Files.size(path);
        return SourceVersion.of(stamp.getBytes(StandardCharsets.UTF_8));
    }
}
