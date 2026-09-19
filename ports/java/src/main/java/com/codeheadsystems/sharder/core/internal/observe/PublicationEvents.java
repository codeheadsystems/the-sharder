package com.codeheadsystems.sharder.core.internal.observe;

import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.Node;
import com.codeheadsystems.sharder.core.internal.placement.PreparedPlacement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The events one publication of a document emits, before and during stage 6 of {@code TOPO-001}.
 *
 * <p>The cost totals of {@code PLACE-073} are computed before preparation begins and their events
 * are emitted before it, under {@code PLACE-077}, so an operator reads a preparation cost from the
 * event rather than from a pipeline that has already stalled. Crossing a threshold changes no
 * ordering and clamps no count.
 */
public final class PublicationEvents {

    /** One emitted event: its name, its severity, and the payload members it carries. */
    public record Emitted(String name, String severity, Map<String, Object> payload) {
    }

    /** The default warning thresholds of {@code CFG-010}. */
    public static final int DIRECTORY_WARN_ENTRIES = 10000;
    /** The summed virtual node count above which the rendezvous event is emitted. */
    public static final int RENDEZVOUS_WARN_VIRTUAL_NODES = 4096;
    /** The ring token total above which the ring event is emitted. */
    public static final long RING_WARN_TOKENS = 1000000L;

    private PublicationEvents() {
    }

    /** Every event the publication of this document emits, in the order it emits them. */
    public static List<Emitted> of(TopologyDocument document) {
        List<Emitted> events = new ArrayList<>();
        cost(document, events);
        clamping(document, events);
        spread(document, events);
        seed(document, events);
        return List.copyOf(events);
    }

    /** {@code PLACE-073}: the totals measured over the placement set, against their thresholds. */
    private static void cost(TopologyDocument document, List<Emitted> events) {
        List<Node> placementSet = document.placementSet();
        switch (document.strategy().kind()) {
            case "ring" -> {
                long total = 0;
                for (Node node : placementSet) {
                    total += tokenCount(document, node);
                }
                if (total > RING_WARN_TOKENS) {
                    events.add(new Emitted("sharder.topology.ring_large", "warning",
                            payload("nodeCount", placementSet.size(), "total", total,
                                    "threshold", RING_WARN_TOKENS)));
                }
            }
            case "rendezvous" -> {
                long total = 0;
                for (Node node : placementSet) {
                    total += virtualNodeCount(document, node);
                }
                if (total > RENDEZVOUS_WARN_VIRTUAL_NODES) {
                    events.add(new Emitted("sharder.topology.rendezvous_large", "warning",
                            payload("nodeCount", placementSet.size(), "total", total,
                                    "threshold", (long) RENDEZVOUS_WARN_VIRTUAL_NODES)));
                }
            }
            case "directory" -> {
                int entries = document.strategy().entries().size();
                if (entries > DIRECTORY_WARN_ENTRIES) {
                    events.add(new Emitted("sharder.topology.directory_large", "warning",
                            payload("entryCount", entries,
                                    "threshold", DIRECTORY_WARN_ENTRIES)));
                }
            }
            default -> {
                // `slot` measures what the document spells, so it crosses no threshold of its own.
            }
        }
    }

    /** {@code PLACE-052}: a count clamped to the cap names the node, the request, and the grant. */
    private static void clamping(TopologyDocument document, List<Emitted> events) {
        boolean ring = "ring".equals(document.strategy().kind());
        boolean derived = "derived".equals(document.strategy().tokenAssignment());
        if (ring && !derived) {
            return;
        }
        if (!ring && !"rendezvous".equals(document.strategy().kind())) {
            return;
        }
        int perWeightUnit = ring
                ? document.strategy().tokensPerWeightUnit()
                : document.strategy().virtualNodesPerWeightUnit();
        int cap = ring
                ? document.strategy().maxTokensPerNode()
                : document.strategy().maxVirtualNodesPerNode();
        for (Node node : document.placementSet()) {
            long requested = (long) node.weight() * (long) perWeightUnit;
            if (requested > cap) {
                events.add(new Emitted("sharder.topology.weight_clamped", "warning",
                        payload("node", node.id().asText(), "requestedCount", requested,
                                "grantedCount", (long) cap)));
            }
        }
    }

    /**
     * {@code SPREAD-024}: a level whose domain count cannot reach the replication factor.
     *
     * <p>The event names the coarsest such level, its domain count, the factor, and the lowest
     * stage {@code SPREAD-023} does not rule out. The occupancy cap is 1 at every level, so the
     * product it bounds a stage by is the domain count itself.
     */
    private static void spread(TopologyDocument document, List<Emitted> events) {
        List<String> spread = document.replication().spread();
        int factor = document.replication().factor();
        if (spread.isEmpty()) {
            return;
        }
        List<Integer> counts = new ArrayList<>();
        for (String level : spread) {
            counts.add(domainCount(document, level));
        }
        int coarsest = -1;
        for (int index = 0; index < spread.size(); index++) {
            if (counts.get(index) < factor) {
                coarsest = index;
                break;
            }
        }
        if (coarsest < 0) {
            return;
        }
        int stage = spread.size();
        for (int index = 0; index < spread.size(); index++) {
            if (counts.get(index) >= factor) {
                stage = index;
                break;
            }
        }
        events.add(new Emitted("sharder.topology.spread_infeasible", "warning",
                payload("level", spread.get(coarsest), "domainCount", counts.get(coarsest),
                        "factor", factor, "stage", stage)));
    }

    /** {@code SEC-011}: a zero seed meeting the evidence of multi-tenancy. */
    private static void seed(TopologyDocument document, List<Emitted> events) {
        for (byte octet : document.hashSeed()) {
            if (octet != 0) {
                return;
            }
        }
        List<String> evidence = new ArrayList<>();
        if (!document.overrides().isEmpty()) {
            evidence.add("overrides");
        }
        if ("directory".equals(document.strategy().kind())) {
            evidence.add("directory");
        }
        if (evidence.isEmpty()) {
            return;
        }
        events.add(new Emitted("sharder.topology.default_seed", "warning",
                payload("evidence", List.copyOf(evidence))));
    }

    /** The distinct domain paths at one level over the placement set, under {@code SPREAD-022}. */
    private static int domainCount(TopologyDocument document, String level) {
        int through = document.domainLevels().indexOf(level);
        Set<List<String>> paths = new LinkedHashSet<>();
        for (Node node : document.placementSet()) {
            List<String> path = new ArrayList<>();
            for (int index = 0; index <= through; index++) {
                path.add(node.domains().get(document.domainLevels().get(index)));
            }
            paths.add(List.copyOf(path));
        }
        return paths.size();
    }

    private static long tokenCount(TopologyDocument document, Node node) {
        return "derived".equals(document.strategy().tokenAssignment())
                ? virtualNodeCountOf(node, document.strategy().tokensPerWeightUnit(),
                        document.strategy().maxTokensPerNode())
                : node.tokens().size();
    }

    private static long virtualNodeCount(TopologyDocument document, Node node) {
        return virtualNodeCountOf(node, document.strategy().virtualNodesPerWeightUnit(),
                document.strategy().maxVirtualNodesPerNode());
    }

    private static long virtualNodeCountOf(Node node, int perWeightUnit, int cap) {
        return PreparedPlacement.virtualNodeCount(node, perWeightUnit, cap);
    }

    private static Map<String, Object> payload(Object... pairs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            payload.put((String) pairs[index], pairs[index + 1]);
        }
        return payload;
    }
}
