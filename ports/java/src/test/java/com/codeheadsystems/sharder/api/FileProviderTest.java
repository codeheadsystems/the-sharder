package com.codeheadsystems.sharder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.sharder.MonotonicClock;
import com.codeheadsystems.sharder.Router;
import com.codeheadsystems.sharder.config.RouterConfig;
import com.codeheadsystems.sharder.core.Sharder;
import com.codeheadsystems.sharder.error.ProviderException;
import com.codeheadsystems.sharder.provider.file.FileTopologyProvider;
import com.codeheadsystems.sharder.topology.Loaded;
import com.codeheadsystems.sharder.topology.SourceVersion;
import com.codeheadsystems.sharder.topology.TopologyProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A document read from a file, under {@code CORE-080}. */
class FileProviderTest {

    @Test
    void routesAgainstTheFileAndRereadsItOnRefresh(@TempDir Path directory) throws IOException {
        Path document = directory.resolve("objects.topology.json");
        Files.write(document, Topologies.ring("objects", 1, 4, 64, 2));
        try (Router router = Sharder.router(RouterConfig.builder()
                .provider(new FileTopologyProvider(document))
                .clock(MonotonicClock.fixed(0))
                .build())) {
            assertThat(router.snapshot().orElseThrow().epoch()).isEqualTo(1);

            Files.write(document, Topologies.ring("objects", 2, 5, 64, 2));
            // CFG-012: with no executor, `refresh` is the only path a document arrives by.
            router.refresh();
            assertThat(router.snapshot().orElseThrow().epoch()).isEqualTo(2);
        }
    }

    @Test
    void answersUnchangedForAVersionTheFileStillHolds(@TempDir Path directory) throws IOException {
        Path document = directory.resolve("objects.topology.json");
        Files.write(document, Topologies.ring("objects", 1, 4, 64, 2));
        TopologyProvider provider = new FileTopologyProvider(document);
        assertThat(provider.capabilities().pull()).isTrue();
        assertThat(provider.capabilities().push()).isFalse();

        Loaded first = provider.load(Optional.empty());
        assertThat(first).isInstanceOf(Loaded.Document.class);
        Optional<SourceVersion> version = ((Loaded.Document) first).version();
        assertThat(version).isPresent();
        // CORE-086: a load whose known version the source still holds enters no stage.
        assertThat(provider.load(version)).isInstanceOf(Loaded.Unchanged.class);
        provider.close();
    }

    @Test
    void reportsAMissingFileAsAProviderFailure(@TempDir Path directory) {
        TopologyProvider provider = new FileTopologyProvider(directory.resolve("absent.json"));
        assertThatThrownBy(() -> provider.load(Optional.empty()))
                .isInstanceOf(ProviderException.class)
                .satisfies(failure -> {
                    // ERR-063: the original failure survives as the Java cause.
                    assertThat(failure.getCause()).isInstanceOf(IOException.class);
                    assertThat(((ProviderException) failure).code()).isEqualTo(204);
                });
        assertThatThrownBy(() -> provider.watch(new com.codeheadsystems.sharder.topology
                .TopologySink() {
            @Override
            public void onDocument(byte[] document, Optional<SourceVersion> version) {
            }

            @Override
            public void onError(Throwable error) {
            }
        })).isInstanceOf(UnsupportedOperationException.class);
    }
}
