package com.codeheadsystems.sharder.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.sharder.MonotonicClock;
import com.codeheadsystems.sharder.Router;
import com.codeheadsystems.sharder.config.RouterConfig;
import com.codeheadsystems.sharder.core.InMemoryTopologyProvider;
import com.codeheadsystems.sharder.core.Sharder;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The allocation gate of the Java binding, which runs in {@code check}.
 *
 * <p>The two ring sizes take one ceiling between them, because {@code CORE-046} makes the decision
 * five entries wide at either size: a candidate cursor quietly turned into a materialised list, or
 * a decision that carried the whole preference list, shows up as the gap between them. The
 * rendezvous shape is here because it is where a per-evaluation allocation inside the hash is
 * expensive: at 8000 evaluations per routing call, a {@code SipHash24} or a {@code Frame} allocated
 * once per evaluation would cost hundreds of kilobytes on one call, which surfaces as collector
 * pressure rather than as latency and which only an allocation gate sees.
 */
class AllocationGateTest {

    /** The ceiling one ring routing call takes, with headroom over the measured figure. */
    private static final long RING_CEILING = 8192;

    /**
     * The ceiling one rendezvous routing call takes at 8000 virtual nodes.
     *
     * <p>The cost that remains is per node rather than per evaluation: one frame, one score, and
     * the sort of {@code RV-010}. A frame allocated per evaluation instead costs an order of
     * magnitude more, which is the regression this ceiling is set to catch.
     */
    private static final long RENDEZVOUS_CEILING = 393216;

    private static final int WARMUP = 20000;
    private static final int ROUNDS = 2000;

    @Test
    void oneRoutingCallOverAHundredNodeRingStaysUnderTheCeiling() {
        assertThat(perCall("ring-100", Topologies.ring("gate-ring-100", 1, 100, 128, 3)))
                .as("bytes per route over a hundred-node ring")
                .isLessThanOrEqualTo(RING_CEILING);
    }

    @Test
    void oneRoutingCallOverAThousandNodeRingStaysUnderTheSameCeiling() {
        assertThat(perCall("ring-1000", Topologies.ring("gate-ring-1000", 1, 1000, 128, 3)))
                .as("bytes per route over a thousand-node ring")
                .isLessThanOrEqualTo(RING_CEILING);
    }

    @Test
    void oneRoutingCallOverEightThousandVirtualNodesStaysUnderTheCeiling() {
        assertThat(perCall("rendezvous-8000", Topologies.rendezvous("gate-rendezvous", 1, 1000, 8, 3)))
                .as("bytes per route over a summed virtual node count of 8000")
                .isLessThanOrEqualTo(RENDEZVOUS_CEILING);
    }

    /** The allocation one {@code route} call makes, measured over a warmed-up run. */
    private static long perCall(String shape, byte[] document) {
        byte[][] keys = new byte[64][];
        for (int index = 0; index < keys.length; index++) {
            keys[index] = ("key-" + index).getBytes(StandardCharsets.UTF_8);
        }
        try (Router router = Sharder.router(RouterConfig.builder()
                .provider(new InMemoryTopologyProvider(document))
                .clock(MonotonicClock.fixed(0))
                .build())) {
            for (int round = 0; round < WARMUP; round++) {
                router.route(keys[round % keys.length]);
            }
            com.sun.management.ThreadMXBean threads =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long thread = Thread.currentThread().threadId();
            long before = threads.getThreadAllocatedBytes(thread);
            for (int round = 0; round < ROUNDS; round++) {
                router.route(keys[round % keys.length]);
            }
            long after = threads.getThreadAllocatedBytes(thread);
            long perCall = (after - before) / ROUNDS;
            System.out.println("allocation gate " + shape + ": " + perCall + " bytes per route");
            return perCall;
        }
    }
}
