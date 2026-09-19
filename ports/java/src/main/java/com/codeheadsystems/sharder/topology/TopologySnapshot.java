package com.codeheadsystems.sharder.topology;

import com.codeheadsystems.sharder.Digest;
import com.codeheadsystems.sharder.NodeSet;
import com.codeheadsystems.sharder.placement.PreparedPlacement;
import com.codeheadsystems.sharder.placement.StrategyKind;
import java.util.List;
import java.util.OptionalLong;

/**
 * One validated topology document, prepared for routing, under {@code CORE-020}.
 *
 * <p>A snapshot is immutable. A routing decision reads one snapshot from first candidate to last,
 * under {@code CORE-041}, so an installation that replaces the snapshot in force changes no
 * decision already taken.
 *
 * <p>The snapshot exposes no accessor for the hash seed, under {@code SEC-010}.
 */
public interface TopologySnapshot {

    /** The topology identifier every document of one topology carries. */
    String topologyId();

    /** The epoch, which never decreases in force, under {@code TOPO-100}. */
    long epoch();

    /** The canonical digest of {@code TOPO-050}. */
    Digest digest();

    /** The token a caller passes to a recipient, under {@code FENCE-010}. */
    FencingToken token();

    /** Every node the document carries, in document order, joining and leaving included. */
    List<Node> nodes();

    /** The nodes placement runs over, under {@code PLACE-001}. */
    NodeSet placementSet();

    /** The prepared strategy, under {@code CORE-011}. */
    PreparedPlacement placement();

    /** The strategy the document names. */
    StrategyKind strategy();

    /** The failure domain levels, coarsest first. */
    List<String> domainLevels();

    /** The replication factor of {@code REPL-002}. */
    int factor();

    /** Whether the hash seed is the default of {@code HASH-011}, under {@code SEC-011}. */
    boolean seedIsDefault();

    /** When the snapshot was installed, absent for a snapshot from {@code TOPO-191}. */
    OptionalLong installedAt();
}
