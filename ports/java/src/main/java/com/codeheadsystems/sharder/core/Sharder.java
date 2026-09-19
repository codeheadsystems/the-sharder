package com.codeheadsystems.sharder.core;

import com.codeheadsystems.sharder.Router;
import com.codeheadsystems.sharder.config.RouterConfig;
import com.codeheadsystems.sharder.core.internal.route.DefaultRouter;

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
     * A loader that validates a document without installing it, under {@code TOPO-191}.
     *
     * <p>It holds no snapshot of its own, so it answers on the document alone: an operator checks a
     * document before publishing it without a router, a provider, or a network.
     */
    public static TopologyLoader loader(RouterConfig config) {
        return new DefaultRouter(config).loader();
    }
}
