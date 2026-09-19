package com.codeheadsystems.sharder.placement;

import com.codeheadsystems.sharder.topology.Node;
import com.codeheadsystems.sharder.topology.TopologySnapshot;
import com.codeheadsystems.sharder.topology.ValidationError;
import java.util.List;

/**
 * How a key is placed, under {@code CORE-010}.
 *
 * <p>The four strategies of {@code PLACE-010} are implementations of this interface, and an
 * integrator registers one of their own on the configuration. Nothing is discovered through
 * {@code ServiceLoader}, so what is on a classpath never changes how a key routes.
 *
 * <p>An implementation is a pure function of its inputs, under {@code PLACE-010} and
 * {@code PLACE-011}: it reads no health state, no clock, no randomness, and no identity of the
 * caller, the thread, or the process.
 */
public interface PlacementStrategy {

    /** The name a topology document's {@code strategy.kind} carries for this strategy. */
    String name();

    /** Every rule the strategy's own members break, which a document is refused for. */
    List<ValidationError> validate(StrategyConfig config, List<Node> nodes,
                                   List<String> domainLevels);

    /** The placement this snapshot's strategy object describes, under {@code CORE-011}. */
    PreparedPlacement prepare(TopologySnapshot snapshot);

    /**
     * Whether shards may be handed off one at a time, under {@code MOVE-241}.
     *
     * <p>A strategy supports orchestrated migration exactly when its prepared placement enumerates
     * shards. {@code MOVE-261} forbids inferring the answer from the strategy's name, so an
     * implementation states it here.
     */
    boolean supportsOrchestratedMigration();
}
