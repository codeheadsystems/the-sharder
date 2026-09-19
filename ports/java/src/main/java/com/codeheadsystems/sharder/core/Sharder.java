package com.codeheadsystems.sharder.core;

import com.codeheadsystems.sharder.Router;
import com.codeheadsystems.sharder.config.RouterConfig;
import com.codeheadsystems.sharder.core.internal.route.DefaultRouter;
import com.codeheadsystems.sharder.migrate.HandoffCoordinator;
import com.codeheadsystems.sharder.migrate.internal.DefaultCoordinator;

/**
 * Where a caller starts.
 *
 * <p>The library has one entry point, and every implementation behind it is internal, so what an
 * integrator depends on is the interface set of this module rather than a class the library is free
 * to replace.
 */
public final class Sharder {

    private Sharder() {
    }

    /** The router over one configuration, which begins loading as it is constructed. */
    public static Router router(RouterConfig config) {
        return new DefaultRouter(config);
    }

    /**
     * The coordinator a migration is planned through, under {@code MOVE-061}.
     *
     * <p>It holds nothing between calls and installs nothing: a plan is a pure function of the two
     * snapshots and the policy, which is what lets a restarted coordinator rebuild the same plan
     * under {@code MOVE-221}.
     */
    public static HandoffCoordinator coordinator() {
        return new DefaultCoordinator();
    }

    /**
     * A loader that validates a document without installing it, under {@code TOPO-191}.
     *
     * <p>It holds no snapshot of its own, so it answers on the document alone: an operator checks a
     * document before publishing it without a router, a provider, or a network.
     */
    public static TopologyLoader loader(RouterConfig config) {
        return new DefaultRouter(config).loader();
    }
}
