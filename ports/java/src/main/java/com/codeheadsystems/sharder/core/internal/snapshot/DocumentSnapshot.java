package com.codeheadsystems.sharder.core.internal.snapshot;

import com.codeheadsystems.sharder.Digest;
import com.codeheadsystems.sharder.NodeSet;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.route.PlacementEngine;
import com.codeheadsystems.sharder.placement.PreparedPlacement;
import com.codeheadsystems.sharder.placement.StrategyKind;
import com.codeheadsystems.sharder.topology.FencingToken;
import com.codeheadsystems.sharder.topology.Node;
import com.codeheadsystems.sharder.topology.TopologySnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One validated document, prepared for routing, as the public surface reads it.
 *
 * <p>The snapshot holds the parsed document, the engine prepared over it, and the digest of
 * {@code TOPO-050}. It is immutable, so a decision reads one snapshot from first candidate to last
 * under {@code CORE-041} however many installations happen meanwhile.
 */
public final class DocumentSnapshot implements TopologySnapshot {

    private final PlacementEngine engine;
    private final Digest digest;
    private final OptionalLong installedAt;
    private final List<Node> nodes;
    private final NodeSet placementSet;
    private final PreparedPlacement placement;

    /** The snapshot over an engine, its digest, and when it was installed. */
    public DocumentSnapshot(PlacementEngine engine, Digest digest, OptionalLong installedAt) {
        this.engine = engine;
        this.digest = digest;
        this.installedAt = installedAt;
        TopologyDocument document = engine.document();
        List<Node> reading = new ArrayList<>(document.nodes().size());
        document.nodes().forEach(node -> reading.add(new NodeFacade(node)));
        this.nodes = List.copyOf(reading);
        this.placementSet = NodeSet.of(document.placementSet().stream()
                .map(TopologyDocument.Node::id).toList());
        this.placement = new PlacementFacade(engine.placement());
    }

    /** The engine the snapshot was prepared over, which no exported package names. */
    public PlacementEngine engine() {
        return engine;
    }

    /** The parsed document, which no exported package names. */
    public TopologyDocument document() {
        return engine.document();
    }

    @Override
    public String topologyId() {
        return document().topologyId();
    }

    @Override
    public long epoch() {
        return document().epoch();
    }

    @Override
    public Digest digest() {
        return digest;
    }

    @Override
    public FencingToken token() {
        // FENCE-011 permits the digest as a diagnostic third component, and forbids a recipient
        // from reading it in any decision.
        return new FencingToken(topologyId(), epoch(), Optional.of(digest));
    }

    @Override
    public List<Node> nodes() {
        return nodes;
    }

    @Override
    public NodeSet placementSet() {
        return placementSet;
    }

    @Override
    public PreparedPlacement placement() {
        return placement;
    }

    @Override
    public StrategyKind strategy() {
        return StrategyKind.of(document().strategy().kind());
    }

    @Override
    public List<String> domainLevels() {
        return document().domainLevels();
    }

    @Override
    public int factor() {
        return document().replication().factor();
    }

    @Override
    public boolean seedIsDefault() {
        return document().seedIsDefault();
    }

    @Override
    public OptionalLong installedAt() {
        return installedAt;
    }

    @Override
    public String toString() {
        return topologyId() + "@" + epoch();
    }
}
