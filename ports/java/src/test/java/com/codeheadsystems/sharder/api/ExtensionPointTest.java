package com.codeheadsystems.sharder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.sharder.MonotonicClock;
import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.NodeSet;
import com.codeheadsystems.sharder.Router;
import com.codeheadsystems.sharder.RoutingKey;
import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.config.RouterConfig;
import com.codeheadsystems.sharder.core.InMemoryTopologyProvider;
import com.codeheadsystems.sharder.core.Sharder;
import com.codeheadsystems.sharder.error.PlanRefusedException;
import com.codeheadsystems.sharder.migrate.MigrationPolicy;
import com.codeheadsystems.sharder.placement.CandidateCursor;
import com.codeheadsystems.sharder.placement.PlacementStrategy;
import com.codeheadsystems.sharder.placement.PreparedPlacement;
import com.codeheadsystems.sharder.placement.StrategyConfig;
import com.codeheadsystems.sharder.topology.Node;
import com.codeheadsystems.sharder.topology.TopologySnapshot;
import com.codeheadsystems.sharder.topology.ValidationError;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A strategy an integrator registered, under {@code CORE-010}.
 *
 * <p>A registered strategy replaces the built-in of its own name, and nothing is discovered through
 * {@code ServiceLoader}, so a strategy reaches the routing path only by being named here.
 */
class ExtensionPointTest {

    /** A ring replacement that orders candidates by node identity and names one shard. */
    private static final class AlphabeticalStrategy implements PlacementStrategy {

        private final AtomicInteger prepared = new AtomicInteger();

        @Override
        public String name() {
            return "ring";
        }

        @Override
        public List<ValidationError> validate(StrategyConfig config, List<Node> nodes,
                                              List<String> domainLevels) {
            return nodes.isEmpty()
                    ? List.of(new ValidationError("nodes", "emptyPlacementSet", "no node"))
                    : List.of();
        }

        @Override
        public PreparedPlacement prepare(TopologySnapshot snapshot) {
            prepared.incrementAndGet();
            List<NodeId> ordering = new ArrayList<>(snapshot.placementSet().asList());
            return new PreparedPlacement() {
                @Override
                public Optional<ShardId> shardOf(RoutingKey routingKey) {
                    return Optional.of(ShardId.of("one"));
                }

                @Override
                public CandidateCursor candidates(RoutingKey routingKey, NodeSet eligible) {
                    return cursor(ordering.stream().filter(eligible::contains).toList());
                }

                @Override
                public Iterator<ShardId> shards() {
                    return List.of(ShardId.of("one")).iterator();
                }

                @Override
                public CandidateCursor candidatesForShard(ShardId shard, NodeSet eligible) {
                    return candidates(RoutingKey.of(""), eligible);
                }
            };
        }

        @Override
        public boolean supportsOrchestratedMigration() {
            return false;
        }

        private static CandidateCursor cursor(List<NodeId> ordering) {
            return new CandidateCursor() {
                private int index = -1;

                @Override
                public boolean advance() {
                    return ++index < ordering.size();
                }

                @Override
                public NodeId node() {
                    return ordering.get(index);
                }
            };
        }
    }

    @Test
    void aRegisteredStrategyReplacesTheBuiltInOfItsName() {
        AlphabeticalStrategy strategy = new AlphabeticalStrategy();
        try (Router router = Sharder.router(RouterConfig.builder()
                .provider(new InMemoryTopologyProvider(Topologies.ring("ext", 1, 6, 64, 3)))
                .clock(MonotonicClock.fixed(0))
                .strategy(strategy)
                .build())) {
            var decision = router.route(
                    "tenant-42".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    com.codeheadsystems.sharder.RouteOptions.DEFAULTS);
            // The registered ordering is node identity, which the ring never produces.
            assertThat(decision.entries().stream().map(entry -> entry.node().asText()).toList())
                    .containsExactly("n0", "n1", "n2", "n3", "n4");
            assertThat(decision.shard()).contains(ShardId.of("one"));
            // CORE-011: prepared once for the snapshot, however many calls route against it.
            router.route("second".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    com.codeheadsystems.sharder.RouteOptions.DEFAULTS);
            assertThat(strategy.prepared.get()).isEqualTo(1);
            assertThat(router.configuration().setting("strategies")).contains("ring");
        }
    }

    @Test
    void aRegisteredStrategyDeclaresItsOwnMigrationSupport() {
        AlphabeticalStrategy strategy = new AlphabeticalStrategy();
        RouterConfig.Builder config = RouterConfig.builder().clock(MonotonicClock.fixed(0))
                .strategy(strategy);
        TopologySnapshot from;
        TopologySnapshot to;
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ring("ext", 1, 6, 64, 3));
        try (Router router = Sharder.router(config.provider(provider).build())) {
            from = router.snapshot().orElseThrow();
            provider.publish(Topologies.ring("ext", 2, 7, 64, 3));
            to = router.snapshot().orElseThrow();
        }
        // MOVE-261: the strategy declares no support, and the name `ring` does not overrule it.
        assertThatThrownBy(() -> Sharder.coordinator()
                .plan(from, to, new RecordingHooks(), MigrationPolicy.defaults()))
                .isInstanceOf(PlanRefusedException.class)
                .satisfies(failure -> assertThat(((PlanRefusedException) failure).reason())
                        .isEqualTo(PlanRefusedException.Cause.STRATEGY_UNSUPPORTED));
    }
}
