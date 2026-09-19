package com.codeheadsystems.sharder.core;

import com.codeheadsystems.sharder.topology.TopologySnapshot;

/**
 * Validation of a document without installation, under {@code TOPO-191}.
 *
 * <p>An operator checks a document before publishing it, and a router that installed it to check
 * it would route against a document nobody approved. The snapshot a loader answers carries no
 * installation instant, because it was never in force.
 */
@FunctionalInterface
public interface TopologyLoader {

    /**
     * The snapshot the document would become.
     *
     * <p>Every topology condition is raised out of this call, because a caller is waiting for the
     * answer: {@code invalidTopology} carrying every validation error, and the conflict and
     * staleness conditions where the loader is one a router handed out.
     */
    TopologySnapshot validate(byte[] document);
}
