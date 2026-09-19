package com.codeheadsystems.sharder.core.internal.document;

import com.codeheadsystems.sharder.Digest;
import com.codeheadsystems.sharder.core.internal.json.JcsWriter;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import com.codeheadsystems.sharder.core.internal.route.PlacementEngine;
import com.codeheadsystems.sharder.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The load pipeline of {@code TOPO-001} and the acceptance outcomes of {@code TOPO-061}.
 *
 * <p>A candidate document is compared against the snapshot in force by integer comparison of its
 * epoch and octet comparison of its identifier, under {@code TOPO-051}, and by nothing else: no
 * clock, no provider revision, and no document timestamp. The rows of {@code TOPO-061} are
 * evaluated in the order that table writes them, and the first that holds is the outcome.
 *
 * <p>An epoch is never assigned, incremented, or inferred, and a document below the epoch in force
 * is refused whatever its digest, under {@code TOPO-081}. An authority reverting an assignment
 * therefore republishes it at a higher epoch.
 */
public final class TopologyLoader {

    /** What a document's arrival did, under {@code TOPO-061}. */
    public enum Outcome {
        /** The document became the snapshot in force. */
        INSTALLED("installed"),
        /** The document equals the snapshot in force and refreshed its freshness. */
        NOOP("noop"),
        /** The document was refused. */
        REJECTED("rejected");

        private final String spelling;

        Outcome(String spelling) {
            this.spelling = spelling;
        }

        /** The spelling a vector and a metric label join on. */
        public String spelling() {
            return spelling;
        }
    }

    /** One arrival: what it did, the condition where it was refused, and the digest it carried. */
    public record Arrival(Outcome outcome, Optional<ErrorCode> condition, Optional<String> detail,
                          Digest digest, List<ValidationError> errors) {
    }

    private final Map<Long, PlacementEngine> retained = new LinkedHashMap<>();
    private String topologyId;
    private TopologyDocument inForce;
    private Digest digestInForce;
    private PlacementEngine engine;
    private OptionalLong epochInForce = OptionalLong.empty();

    /** The snapshot in force, where one is. */
    public Optional<TopologyDocument> snapshot() {
        return Optional.ofNullable(inForce);
    }

    /** The placement engine over the snapshot in force, where one is. */
    public Optional<PlacementEngine> engine() {
        return Optional.ofNullable(engine);
    }

    /** The epoch in force, where a snapshot is. */
    public OptionalLong epochInForce() {
        return epochInForce;
    }

    /** The identifier every later document is checked against, adopted from the first accepted. */
    public Optional<String> expectedTopologyId() {
        return Optional.ofNullable(topologyId);
    }

    /** The document's arrival, which installs it, treats it as a no-op, or refuses it. */
    public Arrival accept(JsonObject document) {
        Digest digest = Digests.of(JcsWriter.canonicalise(document));
        List<ValidationError> errors = DocumentValidator.validate(document);
        if (!errors.isEmpty()) {
            return new Arrival(Outcome.REJECTED, Optional.of(ErrorCode.INVALID_TOPOLOGY),
                    Optional.of("a document that failed validation"), digest, errors);
        }
        TopologyDocument parsed = TopologyDocument.parse(document);

        // TOPO-061, in the order the table writes the rows.
        if (topologyId != null && !topologyId.equals(parsed.topologyId())) {
            return refused(ErrorCode.TOPOLOGY_CONFLICT, "a differing topologyId", digest);
        }
        if (inForce == null) {
            return install(parsed, digest);
        }
        if (parsed.epoch() < inForce.epoch()) {
            return refused(ErrorCode.STALE_DOCUMENT, "an epoch below the epoch in force", digest);
        }
        if (parsed.epoch() == inForce.epoch()) {
            return digest.equals(digestInForce)
                    ? new Arrival(Outcome.NOOP, Optional.empty(), Optional.empty(), digest,
                            List.of())
                    : refused(ErrorCode.TOPOLOGY_CONFLICT, "an equal epoch whose digest differs",
                            digest);
        }
        return install(parsed, digest);
    }

    /**
     * The snapshots retained under {@code TOPO-161}, by epoch.
     *
     * <p>Each keeps its own prepared placement, because {@code FENCE-091} evaluates a recipient
     * against the preference list at the token's epoch.
     */
    public Map<Long, PlacementEngine> retained() {
        return Map.copyOf(retained);
    }

    /** The previous snapshots retained beside the one in force, under {@code CFG-010}. */
    public static final int RETENTION_DEPTH = 3;

    private Arrival install(TopologyDocument document, Digest digest) {
        if (engine != null) {
            retained.put(inForce.epoch(), engine);
            // TOPO-161 retains the snapshot in force together with `retentionDepth` previous
            // ones, so an epoch older than that is not retained and FENCE-091 reports no stable
            // ownership against it.
            while (retained.size() > RETENTION_DEPTH) {
                retained.remove(retained.keySet().iterator().next());
            }
        }
        topologyId = document.topologyId();
        inForce = document;
        digestInForce = digest;
        engine = new PlacementEngine(document);
        epochInForce = OptionalLong.of(document.epoch());
        return new Arrival(Outcome.INSTALLED, Optional.empty(), Optional.empty(), digest,
                List.of());
    }

    private Arrival refused(ErrorCode condition, String detail, Digest digest) {
        return new Arrival(Outcome.REJECTED, Optional.of(condition), Optional.of(detail), digest,
                List.of());
    }
}
