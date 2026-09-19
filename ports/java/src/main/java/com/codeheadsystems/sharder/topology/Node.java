package com.codeheadsystems.sharder.topology;

import com.codeheadsystems.sharder.NodeId;
import java.util.Map;
import java.util.Optional;

/**
 * One node of the topology in force, with every default of {@code TOPO-020} applied.
 *
 * <p>The token accessors are a count and an index rather than a list, so that preparing a ring of
 * four thousand tokens per node boxes nothing and copies nothing.
 */
public interface Node {

    /** The node identity, which is what every other surface names it by. */
    NodeId id();

    /** The administrative state the document carries. */
    AdministrativeState state();

    /** The placement weight of {@code PLACE-040}. */
    int weight();

    /** The failure domains the node declares, by level. */
    Map<String, String> domains();

    /** The tags an override matches against. */
    Map<String, String> tags();

    /** The address a caller reaches the node at, which the library never reads. */
    Optional<String> address();

    /** The number of tokens an explicit ring assignment gave the node, and zero otherwise. */
    int tokenCount();

    /** One token of an explicit ring assignment, unsigned, under {@code RING-003}. */
    long token(int index);
}
