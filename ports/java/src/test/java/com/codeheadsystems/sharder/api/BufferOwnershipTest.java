package com.codeheadsystems.sharder.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.sharder.MonotonicClock;
import com.codeheadsystems.sharder.Router;
import com.codeheadsystems.sharder.RoutingDecision;
import com.codeheadsystems.sharder.config.RouterConfig;
import com.codeheadsystems.sharder.core.InMemoryTopologyProvider;
import com.codeheadsystems.sharder.core.Sharder;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.json.JsonReader;
import com.codeheadsystems.sharder.core.internal.route.PlacementEngine;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the library holds of a caller's octets, and what a caller holds of the library's.
 *
 * <p>{@code CORE-070} forbids retaining a caller-supplied key buffer after a call returns, and
 * {@code HASH-012} makes the hash seed constant for the lifetime of a snapshot. Neither holds by
 * itself in a language where an array is a reference, so each is a copy the library makes.
 */
class BufferOwnershipTest {

    private static final byte[] DOCUMENT = Topologies.ring("buffers", 1, 6, 64, 3);

    @Test
    void aDecisionKeepsItsRoutingKeyWhenTheCallerReusesTheBuffer() {
        try (Router router = Sharder.router(RouterConfig.builder()
                .provider(new InMemoryTopologyProvider(DOCUMENT))
                .clock(MonotonicClock.fixed(0))
                .build())) {
            byte[] key = "tenant-42".getBytes(StandardCharsets.UTF_8);
            RoutingDecision decision = router.route(key);
            byte[] routingKey = decision.routingKey().toBytes();
            List<com.codeheadsystems.sharder.NodeId> entries =
                    decision.entries().stream().map(entry -> entry.node()).toList();

            // CORE-070: the caller reuses its own buffer for the next key.
            key[0] ^= 0x5a;

            assertThat(decision.routingKey().toBytes()).isEqualTo(routingKey);
            // The engine's own decision holds the routing key the call derived rather than the
            // array the caller passed, which is where the retention the requirement forbids would
            // sit: the public decision copies into a `RoutingKey` and would hide it.
            assertThat(engineRoutingKeySurvivesMutation()).isTrue();
            assertThat(decision.entries().stream().map(entry -> entry.node()).toList())
                    .isEqualTo(entries);
            assertThat(decision.preferenceList().stream().map(entry -> entry.node()).toList())
                    .startsWith(entries.toArray(com.codeheadsystems.sharder.NodeId[]::new));
        }
    }

    /** Whether an engine decision keeps its routing key when the caller writes to the key. */
    private static boolean engineRoutingKeySurvivesMutation() {
        TopologyDocument document = TopologyDocument.parse(JsonReader.read(new String(
                DOCUMENT, StandardCharsets.UTF_8)).asObject());
        byte[] key = "tenant-42".getBytes(StandardCharsets.UTF_8);
        var decision = new PlacementEngine(document).route(key);
        byte[] derived = decision.routingKey().clone();
        key[0] ^= 0x5a;
        return java.util.Arrays.equals(decision.routingKey(), derived);
    }

    @Test
    void aDocumentKeepsItsSeedWhenACallerWritesToWhatItHandedOut() {
        TopologyDocument document = TopologyDocument.parse(JsonReader.read(new String(
                Topologies.ring("buffers", 1, 4, 64, 2), StandardCharsets.UTF_8)).asObject());
        byte[] key = "tenant-42".getBytes(StandardCharsets.UTF_8);
        List<com.codeheadsystems.sharder.NodeId> before =
                new PlacementEngine(document).route(key).entries();

        // HASH-012: a caller scrubbing the seed it was handed changes no later placement.
        byte[] seed = document.hashSeed();
        java.util.Arrays.fill(seed, (byte) 0x7f);

        assertThat(document.hashSeed()).containsOnly((byte) 0);
        assertThat(new PlacementEngine(document).route(key).entries()).isEqualTo(before);
    }

    @Test
    void twoDocumentsCarryingOneSeedCompareEqual() {
        TopologyDocument first = TopologyDocument.parse(JsonReader.read(new String(
                Topologies.ring("buffers", 1, 4, 64, 2), StandardCharsets.UTF_8)).asObject());
        TopologyDocument second = TopologyDocument.parse(JsonReader.read(new String(
                Topologies.ring("buffers", 1, 4, 64, 2), StandardCharsets.UTF_8)).asObject());
        assertThat(first.seedEquals(second)).isTrue();
        assertThat(first.overrides()).isEqualTo(second.overrides());
        assertThat(new TopologyDocument.Matcher("exact", "ab".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(new TopologyDocument.Matcher("exact",
                        "ab".getBytes(StandardCharsets.UTF_8)));
    }
}
