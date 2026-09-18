package com.codeheadsystems.sharder.core.internal.route;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.Constraint;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.Node;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.OverrideEntry;
import com.codeheadsystems.sharder.core.internal.hash.DomainHash;
import com.codeheadsystems.sharder.core.internal.placement.EligibleSet;
import com.codeheadsystems.sharder.core.internal.placement.KeyTransforms;
import com.codeheadsystems.sharder.core.internal.placement.Matchers;
import com.codeheadsystems.sharder.core.internal.placement.PreparedPlacement;
import com.codeheadsystems.sharder.error.NoCandidateException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * One routing call over a prepared placement: key transform, override, eligible set, candidate
 * ordering, preference list.
 *
 * <p>This is the placement engine of the {@code place} conformance level, which is stage 6 of
 * {@code TOPO-001} and no part of stages 1 through 5: it reads a document that is already valid and
 * performs no schema check, no canonical form, no digest, and no snapshot lifecycle. The public
 * router arrives with the document pipeline.
 */
public final class PlacementEngine {

    private final TopologyDocument document;
    private final DomainHash hash;
    private final PreparedPlacement placement;
    private final SpreadLadder ladder;
    private final List<Node> placementSet;

    /** The engine over one document, prepared once. */
    public PlacementEngine(TopologyDocument document) {
        this.document = document;
        this.hash = DomainHash.ofKey(document.hashSeed());
        this.placement = PreparedPlacement.of(document);
        this.ladder = new SpreadLadder(document);
        this.placementSet = document.placementSet();
    }

    /** {@code keyHash(rk)} under this document's seed, which the collision vectors assert. */
    public long keyHash(byte[] routingKey) {
        return hash.keyHash(routingKey);
    }

    /** The prepared placement, for the calls that read it directly. */
    public PreparedPlacement placement() {
        return placement;
    }

    /** The spread ladder, for the vectors that assert every stage. */
    public SpreadLadder ladder() {
        return ladder;
    }

    /** The routing key of {@code KEY-010}. */
    public byte[] routingKey(byte[] key) {
        return KeyTransforms.apply(document.keyTransform(), key);
    }

    /** The eligible node set of the placement set, which is what an unmatched key routes over. */
    public EligibleSet placementEligible() {
        return EligibleSet.of(placementSet);
    }

    /** The decision one key produces. */
    public PlacementDecision route(byte[] key) {
        byte[] routingKey = routingKey(key);
        Optional<OverrideEntry> override = Matchers.matched(
                document.overrides(), OverrideEntry::match, routingKey);

        EligibleSet eligible = placementEligible();
        Constraint constraint = override.map(OverrideEntry::constrain).orElse(null);
        if (constraint != null) {
            eligible = EligibleSet.of(constrained(constraint));
        }

        List<NodeId> candidates;
        List<NodeId> pin = override.map(OverrideEntry::pin).orElse(null);
        if (pin != null) {
            // OVR-010 to OVR-013: the pin is the candidate ordering, filtered and deduplicated,
            // never reordered and never extended by the strategy.
            candidates = pinned(pin, eligible);
        } else {
            candidates = placement.candidates(routingKey, eligible);
        }

        int factor = override.map(OverrideEntry::factor).orElseGet(OptionalInt::empty)
                .orElseGet(() -> document.replication().factor());

        if (candidates.isEmpty()) {
            throw new NoCandidateException(cause(constraint, pin, eligible, routingKey));
        }

        SpreadLadder.Stage stage = ladder.chosen(candidates, factor);
        List<NodeId> replicas = stage.selected();
        List<NodeId> preference = new ArrayList<>(replicas);
        // REPL-013: the tail is the ordering with the prefix removed, in ordering order, so an
        // entry skipped for spread sits at its original relative position.
        candidates.stream().filter(id -> !replicas.contains(id)).forEach(preference::add);

        String shortfall = "none";
        if (replicas.size() < factor) {
            // REPL-021: classified over the candidate ordering rather than the eligible set.
            shortfall = candidates.size() < factor ? "nodes" : "domains";
        }

        int attemptLimit = factor + 2;
        int materialised = Math.min(preference.size(), Math.max(replicas.size(), attemptLimit));

        return new PlacementDecision(
                routingKey,
                placement.shardOf(routingKey),
                document.topologyId(),
                document.epoch(),
                factor,
                replicas.size(),
                candidates,
                List.copyOf(preference),
                materialised,
                stage.relaxes(),
                stage.index(),
                shortfall,
                override.map(entry -> new PlacementDecision.MatchedOverride(
                        entry.index(), mode(entry))),
                attemptLimit);
    }

    /** The pinned ordering: filtered to the placement set and to the constraint, deduplicated. */
    private List<NodeId> pinned(List<NodeId> pin, EligibleSet eligible) {
        Set<NodeId> ordering = new LinkedHashSet<>();
        for (NodeId id : pin) {
            // OVR-011: an identity whose node is joining or leaving is omitted, and OVR-030 lets
            // the constraint filter the pinned ordering.
            if (eligible.contains(id) && placementSet.stream().anyMatch(n -> n.id().equals(id))) {
                ordering.add(id);
            }
        }
        return List.copyOf(ordering);
    }

    /** The placement set filtered by a constraint, whose members intersect under
     * {@code OVR-021}. */
    private List<Node> constrained(Constraint constraint) {
        List<Node> admitted = new ArrayList<>();
        for (Node node : placementSet) {
            if (admits(constraint, node)) {
                admitted.add(node);
            }
        }
        return admitted;
    }

    private static boolean admits(Constraint constraint, Node node) {
        for (var level : constraint.domains().entrySet()) {
            String identifier = node.domains().get(level.getKey());
            // OVR-022: a node carrying no identifier at a named level is excluded.
            if (identifier == null || !level.getValue().contains(identifier)) {
                return false;
            }
        }
        for (var tag : constraint.tags().entrySet()) {
            String value = node.tags().get(tag.getKey());
            if (value == null || !tag.getValue().contains(value)) {
                return false;
            }
        }
        return constraint.nodes() == null || constraint.nodes().contains(node.id());
    }

    private static String mode(OverrideEntry entry) {
        if (entry.pin() != null && entry.constrain() != null) {
            return "both";
        }
        return entry.pin() != null ? "pin" : "constrain";
    }

    /**
     * The cause of {@code ERR-021}, whose rows are evaluated in the order the table writes them.
     *
     * <p>More than one row holds for a key an override constrains over a strategy that would have
     * produced nothing of its own, which is why the order is part of the requirement.
     */
    private String cause(Constraint constraint, List<NodeId> pin, EligibleSet eligible,
                         byte[] routingKey) {
        if (placementSet.isEmpty()) {
            return "emptyPlacementSet";
        }
        if (constraint != null && eligible.isEmpty()) {
            return "constraintExcludedAll";
        }
        if (pin != null) {
            return "pinExcludedAll";
        }
        return placement.emptyCause(routingKey, eligible);
    }
}
