package com.codeheadsystems.sharder.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.sharder.MonotonicClock;
import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.Router;
import com.codeheadsystems.sharder.RoutingDecision;
import com.codeheadsystems.sharder.config.RouterConfig;
import com.codeheadsystems.sharder.core.InMemoryTopologyProvider;
import com.codeheadsystems.sharder.core.Sharder;
import com.codeheadsystems.sharder.health.HealthSignal;
import com.codeheadsystems.sharder.health.HealthState;
import com.codeheadsystems.sharder.health.Outcome;
import com.codeheadsystems.sharder.observe.Event;
import com.codeheadsystems.sharder.observe.ShardReport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The two settings that report rather than route: the shard metrics source and the observer. */
class ObservabilityWiringTest {

    private static final byte[] KEY = "tenant-42".getBytes(StandardCharsets.UTF_8);

    @Test
    void aHotShardIsReportedOncePerEpoch() {
        List<Event> events = new ArrayList<>();
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ring("hot", 1, 4, 8, 2));
        try (Router router = Sharder.router(RouterConfig.builder()
                .provider(provider)
                .clock(MonotonicClock.fixed(0))
                .eventSink(events::add)
                // OBS-036: the counters are the integrator's, and the library reads no node.
                .shardMetricsSource(shard -> Optional.of(new ShardReport(1000, 900, 60000)))
                .build())) {
            router.route(KEY);
            router.route(KEY);
            router.route(KEY);
            // OBS-031 and OBS-032: one shard drawing a thousand of three requests is hot, and one
            // key drawing nine tenths of the shard is skew.
            assertThat(events).extracting(Event::name)
                    .containsOnlyOnce("sharder.shard.hot")
                    .containsOnlyOnce("sharder.shard.key_skew");
            assertThat(events).filteredOn(event -> event.name().equals("sharder.shard.hot"))
                    .singleElement()
                    .satisfies(event -> assertThat(event.payload()).containsKey("observedShare"));
        }
    }

    @Test
    void noSourceMeansNoHotShardReport() {
        List<Event> events = new ArrayList<>();
        try (Router router = Sharder.router(RouterConfig.builder()
                .provider(new InMemoryTopologyProvider(Topologies.ring("hot", 1, 4, 8, 2)))
                .clock(MonotonicClock.fixed(0))
                .eventSink(events::add)
                .build())) {
            router.route(KEY);
            // OBS-035: with no source supplied, no hot shard is reported.
            assertThat(events).extracting(Event::name).doesNotContain("sharder.shard.hot");
        }
    }

    @Test
    void everyHealthTransitionReachesTheObserver() {
        List<String> observed = new ArrayList<>();
        try (Router router = Sharder.router(RouterConfig.builder()
                .provider(new InMemoryTopologyProvider(Topologies.ring("hints", 1, 6, 64, 3)))
                .clock(MonotonicClock.fixed(0))
                .hintObserver((node, from, to, trigger) ->
                        observed.add(node.asText() + ":" + from.spelling() + "->" + to.spelling()
                                + ":" + trigger))
                .build())) {
            RoutingDecision decision = router.route(KEY);
            NodeId node = decision.primary().node();
            for (int signal = 0; signal < 30; signal++) {
                router.health().report(new HealthSignal(node, Outcome.FAILURE, signal));
            }
            assertThat(router.health().stateOf(node)).isNotEqualTo(HealthState.UNKNOWN);
            assertThat(observed).isNotEmpty();
            assertThat(observed.get(0)).startsWith(node.asText() + ":unknown->");
        }
    }
}
