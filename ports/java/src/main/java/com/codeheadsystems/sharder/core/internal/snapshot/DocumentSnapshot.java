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
import java.util.Iterator;
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
    private volatile long shardCount = -1;

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
        // A registered strategy prepares against this snapshot, which cannot exist before the
        // engine it reads, so the binding is completed here and read no earlier. The preparation
        // itself happens now rather than on the first routing call, under CORE-011: a snapshot
        // reaches nobody until its placement is ready, and no routing call waits on a strategy.
        engine.bind(this);
        engine.placement().prepare();
    }

    /**
     * The number of shards the strategy enumerates, counted once.
     *
     * <p>{@code OBS-031} compares against it, and a ring of a million tokens names a million
     * shards, so the count is taken on the first call that needs one and held. The enumeration is
     * a pure function of the snapshot, so a second count would answer what the first did.
     */
    public long shardCount() {
        long counted = shardCount;
        if (counted < 0) {
            counted = 0;
            Iterator<String> shards = engine.placement().shardCursor();
            while (shards.hasNext()) {
                shards.next();
                counted++;
            }
            shardCount = counted;
        }
        return counted;
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
