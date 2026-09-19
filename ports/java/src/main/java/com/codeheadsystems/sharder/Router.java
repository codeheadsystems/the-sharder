package com.codeheadsystems.sharder;

import com.codeheadsystems.sharder.config.ConfigurationView;
import com.codeheadsystems.sharder.core.TopologyLoader;
import com.codeheadsystems.sharder.fence.Recipient;
import com.codeheadsystems.sharder.health.HealthView;
import com.codeheadsystems.sharder.observe.ExplainRecord;
import com.codeheadsystems.sharder.observe.MetricsView;
import com.codeheadsystems.sharder.topology.TopologySnapshot;
import java.util.Optional;

/**
 * The library, from a caller's side: given a key, which nodes handle it and in what order.
 *
 * <p>A router is safe to use from any number of units of execution, under {@code CORE-055}. It
 * holds one snapshot in force, replaced whole at an installation, so a decision taken before an
 * installation reads the snapshot it was taken against from first candidate to last, under
 * {@code CORE-041}.
 *
 * <p>The library observes nothing on its own. Health comes from signals a caller reports, documents
 * come from a provider, and time comes from the configured clock, which is what makes every
 * decision a function of the snapshot, the key, and the reported signals.
 */
public interface Router extends AutoCloseable {

    /** The decision for a key, with the configured attempt limit, under {@code CORE-030}. */
    RoutingDecision route(byte[] key);

    /** The decision for a key, with the options given. */
    RoutingDecision route(byte[] key, RouteOptions options);

    /** The decision for a key whose octets are its UTF-8 encoding, under {@code KEY-003}. */
    RoutingDecision route(String key, RouteOptions options);

    /** The decision for a read, reordered under the affinity request, under {@code READ-011}. */
    RoutingDecision routeForRead(byte[] key, AffinityRequest affinity, RouteOptions options);

    /** The attempt walk over a decision this router took, under {@code FAIL-020}. */
    AttemptSequence attempts(RoutingDecision decision);

    /** The account of how the snapshot in force routes a key, under {@code OBS-040}. */
    ExplainRecord explain(byte[] key);

    /** The snapshot in force, absent until the first document is installed, {@code CORE-032}. */
    Optional<TopologySnapshot> snapshot();

    /** What the router knows about the nodes it routes to. */
    HealthView health();

    /**
     * Loads a document from the provider now, under {@code CORE-033}.
     *
     * <p>This is a call an integrator made, so the topology conditions are raised out of it rather
     * than recorded, which is what the conditions that never raise distinguishes.
     */
    void refresh();

    /** The receiving side of a fenced request, for this node's own identity, {@code FENCE-061}. */
    Recipient recipient(NodeId selfId);

    /** A loader that validates a document without installing it, under {@code TOPO-191}. */
    TopologyLoader loader();

    /** The values in force, including the defaults the integrator did not set, {@code CFG-007}. */
    ConfigurationView configuration();

    /** The metrics the library holds where no registry is supplied, under {@code OBS-004}. */
    MetricsView metrics();

    /** Releases the provider, the subscription, and the polling, under {@code CORE-034}. */
    @Override
    void close();
}
