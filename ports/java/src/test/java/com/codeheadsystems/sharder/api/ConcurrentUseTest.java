package com.codeheadsystems.sharder.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.sharder.AttemptSequence;
import com.codeheadsystems.sharder.MonotonicClock;
import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.Router;
import com.codeheadsystems.sharder.RoutingDecision;
import com.codeheadsystems.sharder.config.RouterConfig;
import com.codeheadsystems.sharder.core.InMemoryTopologyProvider;
import com.codeheadsystems.sharder.core.Sharder;
import com.codeheadsystems.sharder.health.HealthSignal;
import com.codeheadsystems.sharder.health.Outcome;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * One router, many units of execution, under {@code CORE-055}.
 *
 * <p>A router holds mutable state in three places: the snapshot reference, the health view, and the
 * retry budget. This drives all three at once against a router installing documents underneath
 * them, because the failure it looks for is a torn read or a concurrent modification rather than a
 * wrong answer, and neither shows up in a single-threaded run.
 */
class ConcurrentUseTest {

    private static final int THREADS = 8;
    private static final int ROUNDS = 2000;

    @Test
    void routingReportingAndInstallingRunTogether() throws Exception {
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ring("concurrent", 1, 12, 64, 3));
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AtomicLong decisions = new AtomicLong();
        try (Router router = Sharder.router(RouterConfig.builder()
                .provider(provider)
                .clock(MonotonicClock.systemNanoTime())
                .build())) {
            ExecutorService pool = Executors.newFixedThreadPool(THREADS + 1);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(THREADS + 1);
            for (int worker = 0; worker < THREADS; worker++) {
                final int seed = worker;
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int round = 0; round < ROUNDS; round++) {
                            byte[] key = ("key-" + seed + "-" + round)
                                    .getBytes(StandardCharsets.UTF_8);
                            RoutingDecision decision = router.route(key);
                            assertThat(decision.entries()).isNotEmpty();
                            decisions.incrementAndGet();
                            AttemptSequence attempts = router.attempts(decision);
                            Optional<NodeId> next = attempts.next();
                            if (next.isPresent()) {
                                attempts.recordOutcome(next.get(),
                                        round % 3 == 0 ? Outcome.FAILURE : Outcome.SUCCESS);
                            }
                            router.health().report(new HealthSignal(
                                    decision.primary().node(),
                                    round % 5 == 0 ? Outcome.TIMEOUT : Outcome.SUCCESS,
                                    round));
                            router.health().advance(round);
                            router.health().stateOf(decision.primary().node());
                        }
                    } catch (Throwable failure) {
                        failures.add(failure);
                    } finally {
                        done.countDown();
                    }
                });
            }
            pool.execute(() -> {
                try {
                    start.await();
                    for (int epoch = 2; epoch < 40; epoch++) {
                        provider.publish(Topologies.ring("concurrent", epoch, 12, 64, 3));
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally {
                    done.countDown();
                }
            });
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("every worker finished").isTrue();
            pool.shutdownNow();
        }
        assertThat(failures).isEmpty();
        assertThat(decisions.get()).isEqualTo((long) THREADS * ROUNDS);
    }
}
