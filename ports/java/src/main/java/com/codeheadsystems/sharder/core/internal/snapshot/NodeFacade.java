package com.codeheadsystems.sharder.core.internal.snapshot;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.topology.AdministrativeState;
import com.codeheadsystems.sharder.topology.Node;
import java.util.Map;
import java.util.Optional;

/**
 * The public reading of one parsed node.
 *
 * <p>The tokens stay in the parsed document and are read by index, so a ring of four thousand
 * tokens per node is neither boxed nor copied by a caller that wants one of them.
 */
public final class NodeFacade implements Node {

    private final TopologyDocument.Node node;

    /** The public node over a parsed one. */
    public NodeFacade(TopologyDocument.Node node) {
        this.node = node;
    }

    @Override
    public NodeId id() {
        return node.id();
    }

    @Override
    public AdministrativeState state() {
        return AdministrativeState.of(node.state().spelling());
    }

    @Override
    public int weight() {
        return node.weight();
    }

    @Override
    public Map<String, String> domains() {
        return node.domains();
    }

    @Override
    public Map<String, String> tags() {
        return node.tags();
    }

    @Override
    public Optional<String> address() {
        // TOPO-020 carries an address the library never reads, and the parsed document keeps none.
        return Optional.empty();
    }

    @Override
    public int tokenCount() {
        return node.tokens().size();
    }

    @Override
    public long token(int index) {
        return node.tokens().get(index);
    }
}
