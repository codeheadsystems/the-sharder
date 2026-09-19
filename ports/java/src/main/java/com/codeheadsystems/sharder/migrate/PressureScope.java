package com.codeheadsystems.sharder.migrate;

import com.codeheadsystems.sharder.NodeId;

/** What a pressure reading is about, under {@code RATE-061}. */
public sealed interface PressureScope {

    /** The cluster as a whole. */
    record Cluster() implements PressureScope {
    }

    /** One node, which is a handoff's source or its destination. */
    record Node(NodeId node) implements PressureScope {
    }

    /** The cluster scope, which every step consults. */
    static PressureScope cluster() {
        return new Cluster();
    }

    /** The scope of one node. */
    static PressureScope node(NodeId node) {
        return new Node(node);
    }
}
