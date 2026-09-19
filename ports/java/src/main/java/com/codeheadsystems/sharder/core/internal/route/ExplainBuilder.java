package com.codeheadsystems.sharder.core.internal.route;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.PreferenceEntry;
import com.codeheadsystems.sharder.Role;
import com.codeheadsystems.sharder.RoutingKey;
import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.Shortfall;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.DirectoryEntry;
import com.codeheadsystems.sharder.core.internal.hash.U64;
import com.codeheadsystems.sharder.core.internal.placement.EligibleSet;
import com.codeheadsystems.sharder.core.internal.placement.Matchers;
import com.codeheadsystems.sharder.core.internal.placement.PreparedPlacement;
import com.codeheadsystems.sharder.core.internal.placement.RendezvousPlacement;
import com.codeheadsystems.sharder.core.internal.snapshot.DocumentSnapshot;
import com.codeheadsystems.sharder.health.HealthView;
import com.codeheadsystems.sharder.observe.Exclusion;
import com.codeheadsystems.sharder.observe.ExplainRecord;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The explain record of {@code OBS-040}, built off the routing path.
 *
 * <p>The record names every eligible node and every exclusion, so its cost grows with the node set
 * and {@code OBS-046} keeps {@code route} from computing one unasked. Building one changes no
 * state, under {@code OBS-045}: the health states it reads are read through {@code stateOf}, which
 * admits no probe and records no signal.
 */
public final class ExplainBuilder {

    private ExplainBuilder() {
    }

    /** The account of how one snapshot routes one key. */
    public static ExplainRecord of(DocumentSnapshot snapshot, byte[] key, HealthView health,
                                   boolean carryDigest) {
        PlacementEngine engine = snapshot.engine();
        TopologyDocument document = snapshot.document();
        PlacementDecision decision = engine.route(key);
        byte[] routingKey = decision.routingKey();
        List<NodeId> candidates = decision.candidates();
        List<NodeId> eligible = eligible(engine, document);

        List<Exclusion> exclusions = new ArrayList<>();
        Set<NodeId> placeable = new LinkedHashSet<>();
        document.placementSet().forEach(node -> placeable.add(node.id()));
        // OBS-043: every member of the placement set the eligible set omits carries a stage of
        // `constraint` or `administrativeState`, and every other node of the document carries
        // `administrativeState`.
        document.nodes().forEach(node -> {
            if (!placeable.contains(node.id())) {
                exclusions.add(new Exclusion(node.id(), Exclusion.Stage.ADMINISTRATIVE_STATE,
                        "the node is " + node.state().spelling()));
            } else if (!eligible.contains(node.id())) {
                exclusions.add(new Exclusion(node.id(), Exclusion.Stage.CONSTRAINT,
                        "the matched override constrained the eligible set"));
            }
        });
        Exclusion.Stage unreached = switch (document.strategy().kind()) {
            case "ring", "rendezvous" -> Exclusion.Stage.VIRTUAL_NODES;
            default -> Exclusion.Stage.AUTHORED_LIST;
        };
        eligible.forEach(node -> {
            if (!candidates.contains(node)) {
                exclusions.add(new Exclusion(node, unreached,
                        unreached == Exclusion.Stage.VIRTUAL_NODES
                                ? "the node carries no virtual node"
                                : "the authored list the key matched does not name the node"));
            }
        });

        List<PreferenceEntry> preference = new ArrayList<>();
        List<NodeId> whole = decision.preferenceList();
        for (int position = 0; position < whole.size(); position++) {
            NodeId node = whole.get(position);
            preference.add(new PreferenceEntry(node, position,
                    position < decision.replicaCount() ? Role.REPLICA : Role.FALLBACK,
                    health.stateOf(node), health.stateOf(node).attemptable()));
        }

        Optional<ExplainRecord.OverrideNote> override = decision.matchedOverride().map(matched -> {
            TopologyDocument.OverrideEntry entry = document.overrides().stream()
                    .filter(candidate -> candidate.index() == matched.index())
                    .findFirst().orElseThrow();
            return new ExplainRecord.OverrideNote(matched.index(), entry.match().kind(),
                    entry.match().value(), matched.mode());
        });

        Optional<ExplainRecord.ShortfallNote> shortfall = "none".equals(decision.shortfall())
                ? Optional.empty()
                : Optional.of(new ExplainRecord.ShortfallNote(Shortfall.of(decision.shortfall()),
                        decision.factor(), decision.replicaCount()));

        return new ExplainRecord(
                key,
                RoutingKey.ofBytes(routingKey),
                new ExplainRecord.KeyTransform(document.keyTransform().kind(),
                        transformFields(document)),
                carryDigest ? snapshot.token()
                        : com.codeheadsystems.sharder.topology.FencingToken.of(
                                snapshot.topologyId(), snapshot.epoch()),
                snapshot.digest(),
                document.strategy().kind(),
                override,
                decision.shard().map(ShardId::of),
                strategyInputs(engine, document, routingKey, decision),
                eligible,
                candidates,
                preference,
                exclusions,
                decision.relaxedLevels(),
                shortfall);
    }

    /** The eligible node set the decision ran over, which an override constraint narrows. */
    private static List<NodeId> eligible(PlacementEngine engine, TopologyDocument document) {
        EligibleSet set = engine.placementEligible();
        List<NodeId> eligible = new ArrayList<>();
        document.placementSet().forEach(node -> {
            if (set.contains(node.id())) {
                eligible.add(node.id());
            }
        });
        return List.copyOf(eligible);
    }

    /** The members the key transform of the document carries. */
    private static Map<String, String> transformFields(TopologyDocument document) {
        TopologyDocument.KeyTransformSpec transform = document.keyTransform();
        return switch (transform.kind()) {
            case "prefix" -> Map.of("separator", String.valueOf((char) transform.separator()),
                    "count", Integer.toString(transform.count()));
            case "brace" -> Map.of("open", String.valueOf((char) transform.open()),
                    "close", String.valueOf((char) transform.close()));
            default -> Map.of();
        };
    }

    /**
     * The arithmetic the configured strategy performed, under {@code OBS-042}.
     *
     * <p>Every value is rendered as text, and a 64-bit value as sixteen lowercase hexadecimal
     * digits.
     */
    private static List<ExplainRecord.StrategyInput> strategyInputs(
            PlacementEngine engine, TopologyDocument document, byte[] routingKey,
            PlacementDecision decision) {
        List<ExplainRecord.StrategyInput> inputs = new ArrayList<>();
        String keyHash = U64.toHex(engine.keyHash(routingKey));
        switch (document.strategy().kind()) {
            case "ring" -> {
                inputs.add(new ExplainRecord.StrategyInput("keyHash", keyHash));
                decision.shard().ifPresent(token ->
                        inputs.add(new ExplainRecord.StrategyInput("owningToken", token)));
            }
            case "slot" -> {
                inputs.add(new ExplainRecord.StrategyInput("keyHash", keyHash));
                decision.shard().ifPresent(slot ->
                        inputs.add(new ExplainRecord.StrategyInput("slotIndex", slot)));
            }
            case "rendezvous" -> {
                List<NodeId> candidates = decision.candidates();
                if (!candidates.isEmpty()
                        && engine.placement() instanceof RendezvousPlacement rendezvous) {
                    NodeId winner = candidates.get(0);
                    inputs.add(new ExplainRecord.StrategyInput("winningScore",
                            U64.toHex(rendezvous.score(routingKey, winner))));
                    document.node(winner).ifPresent(node -> inputs.add(
                            new ExplainRecord.StrategyInput("virtualNodeCount",
                                    Integer.toString(PreparedPlacement.virtualNodeCount(node,
                                            document.strategy().virtualNodesPerWeightUnit(),
                                            document.strategy().maxVirtualNodesPerNode())))));
                }
            }
            default -> {
                List<DirectoryEntry> entries = document.strategy().entries();
                Matchers.matched(entries, DirectoryEntry::match, routingKey).ifPresent(entry ->
                        inputs.add(new ExplainRecord.StrategyInput("matchedEntryIndex",
                                Integer.toString(entries.indexOf(entry)))));
            }
        }
        return List.copyOf(inputs);
    }
}
