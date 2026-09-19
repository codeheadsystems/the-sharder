package com.codeheadsystems.sharder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.sharder.AffinityRequest;
import com.codeheadsystems.sharder.AttemptSequence;
import com.codeheadsystems.sharder.MonotonicClock;
import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.RouteOptions;
import com.codeheadsystems.sharder.Router;
import com.codeheadsystems.sharder.RoutingDecision;
import com.codeheadsystems.sharder.config.RouterConfig;
import com.codeheadsystems.sharder.core.InMemoryTopologyProvider;
import com.codeheadsystems.sharder.core.Sharder;
import com.codeheadsystems.sharder.error.InvalidArgumentException;
import com.codeheadsystems.sharder.error.InvalidTopologyException;
import com.codeheadsystems.sharder.error.UnreadyException;
import com.codeheadsystems.sharder.fence.Ownership;
import com.codeheadsystems.sharder.fence.Recipient;
import com.codeheadsystems.sharder.fence.Relation;
import com.codeheadsystems.sharder.fence.Verdict;
import com.codeheadsystems.sharder.health.HealthSignal;
import com.codeheadsystems.sharder.health.HealthState;
import com.codeheadsystems.sharder.health.Outcome;
import com.codeheadsystems.sharder.observe.Event;
import com.codeheadsystems.sharder.observe.ExplainRecord;
import com.codeheadsystems.sharder.topology.FencingToken;
import com.codeheadsystems.sharder.topology.OwnershipDelta;
import com.codeheadsystems.sharder.topology.TopologySnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The public surface, exercised the way an integrator reaches it. */
class RouterTest {

    private static final byte[] KEY = "tenant-42".getBytes(StandardCharsets.UTF_8);

    private static RouterConfig.Builder config(InMemoryTopologyProvider provider) {
        return RouterConfig.builder().provider(provider).clock(MonotonicClock.fixed(1000));
    }

    @Test
    void routesAgainstTheDocumentTheProviderHeld() {
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ring("objects", 1, 6, 64, 3));
        try (Router router = Sharder.router(config(provider).build())) {
            RoutingDecision decision = router.route(KEY);
            assertThat(decision.factor()).isEqualTo(3);
            assertThat(decision.replicaCount()).isEqualTo(3);
            assertThat(decision.entries()).hasSize(5);
            assertThat(decision.primary()).isEqualTo(decision.entries().get(0));
            assertThat(decision.token()).isEqualTo(FencingToken.of("objects", 1));
            assertThat(decision.shard()).isPresent();
            assertThat(decision.preferenceList()).hasSize(6);
            assertThat(decision.preferenceList().subList(0, 5).stream().map(entry -> entry.node()))
                    .containsExactlyElementsOf(decision.entries().stream()
                            .map(entry -> entry.node()).toList());
        }
    }

    @Test
    void routesATextualKeyAsItsUtf8Octets() {
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ring("objects", 1, 6, 64, 3));
        try (Router router = Sharder.router(config(provider).build())) {
            assertThat(router.route("tenant-42", RouteOptions.DEFAULTS).entries())
                    .isEqualTo(router.route(KEY).entries());
        }
    }

    @Test
    void answersUnreadyUntilADocumentIsInstalled() {
        InMemoryTopologyProvider provider = new InMemoryTopologyProvider();
        try (Router router = Sharder.router(config(provider).build())) {
            assertThat(router.snapshot()).isEmpty();
            assertThatThrownBy(() -> router.route(KEY))
                    .isInstanceOf(UnreadyException.class)
                    .satisfies(failure -> assertThat(((UnreadyException) failure).code())
                            .isEqualTo(103));
            provider.publish(Topologies.ring("objects", 1, 4, 64, 2));
            assertThat(router.snapshot()).isPresent();
            assertThat(router.route(KEY).entries()).isNotEmpty();
        }
    }

    @Test
    void refusesAKeyAboveTheConfiguredCeiling() {
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ring("objects", 1, 4, 64, 2));
        try (Router router = Sharder.router(config(provider).maxKeyBytes(8).build())) {
            assertThatThrownBy(() -> router.route(KEY))
                    .isInstanceOf(InvalidArgumentException.class)
                    .hasMessageContaining("at most 8 octets");
        }
    }

    @Test
    void walksTheAttemptSequenceAndReportsOutcomes() {
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ring("objects", 1, 6, 64, 3));
        try (Router router = Sharder.router(config(provider).build())) {
            RoutingDecision decision = router.route(KEY);
            AttemptSequence attempts = router.attempts(decision);
            List<NodeId> walked = new ArrayList<>();
            Optional<NodeId> next = attempts.next();
            while (next.isPresent()) {
                walked.add(next.get());
                attempts.recordOutcome(next.get(), Outcome.FAILURE, 1000);
                next = attempts.next();
            }
            assertThat(walked).hasSize(decision.attemptLimit());
            assertThat(attempts.remaining()).isZero();
        }
    }

    @Test
    void reportsHealthThroughTheView() {
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ring("objects", 1, 6, 64, 3));
        try (Router router = Sharder.router(config(provider).build())) {
            NodeId node = router.route(KEY).primary().node();
            assertThat(router.health().stateOf(node)).isEqualTo(HealthState.UNKNOWN);
            for (int attempt = 0; attempt < 30; attempt++) {
                router.health().report(new HealthSignal(node, Outcome.FAILURE, 1000));
            }
            assertThat(router.health().stateOf(node)).isNotEqualTo(HealthState.UNKNOWN);
        }
    }

    @Test
    void explainsWithoutChangingTheDecision() {
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ring("objects", 1, 6, 64, 3));
        try (Router router = Sharder.router(config(provider).build())) {
            ExplainRecord record = router.explain(KEY);
            RoutingDecision decision = router.route(KEY);
            // OBS-044: the same routing key, shard, candidate ordering, and preference list.
            assertThat(record.routingKey()).isEqualTo(decision.routingKey());
            assertThat(record.shard()).isEqualTo(decision.shard());
            assertThat(record.preferenceList().stream().map(entry -> entry.node()).toList())
                    .isEqualTo(decision.preferenceList().stream().map(entry -> entry.node())
                            .toList());
            // OBS-043: every eligible node appears once, in the candidates or in the exclusions.
            assertThat(record.eligible()).hasSize(6);
            assertThat(record.candidates()).hasSize(6);
            assertThat(record.exclusions()).isEmpty();
            assertThat(record.strategyInputs().stream().map(input -> input.name()).toList())
                    .containsExactly("keyHash", "owningToken");
            assertThat(record.toJson()).contains("\"strategy\":\"ring\"")
                    .contains("\"preferenceList\"");
        }
    }

    @Test
    void reordersAReadUnderAffinity() {
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ringWithDomains("objects", 1, 6, 3));
        try (Router router = Sharder.router(config(provider).build())) {
            RoutingDecision plain = router.route(KEY);
            RoutingDecision read = router.routeForRead(KEY,
                    AffinityRequest.of("region", List.of("r0")), RouteOptions.DEFAULTS);
            assertThat(read.entries()).isEqualTo(plain.entries());
            assertThat(read.ordered().stream().map(entry -> entry.node()).toList())
                    .containsExactlyInAnyOrderElementsOf(plain.ordered().stream()
                            .map(entry -> entry.node()).toList());
        }
    }

    @Test
    void validatesADocumentWithoutInstallingIt() {
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ring("objects", 1, 4, 64, 2));
        try (Router router = Sharder.router(config(provider).build())) {
            TopologySnapshot candidate =
                    router.loader().validate(Topologies.ring("objects", 9, 5, 64, 2));
            assertThat(candidate.epoch()).isEqualTo(9);
            assertThat(candidate.installedAt()).isEmpty();
            // TOPO-191: validating installs nothing, so the snapshot in force is untouched.
            assertThat(router.snapshot().orElseThrow().epoch()).isEqualTo(1);
            assertThatThrownBy(() -> router.loader().validate(
                    "{\"formatVersion\":\"1.0\"}".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(InvalidTopologyException.class)
                    .satisfies(failure -> assertThat(
                            ((InvalidTopologyException) failure).errors()).isNotEmpty());
        }
    }

    @Test
    void computesTheOwnershipDeltaBetweenTwoSnapshots() {
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ring("objects", 1, 4, 8, 2));
        try (Router router = Sharder.router(config(provider).build())) {
            TopologySnapshot before = router.snapshot().orElseThrow();
            provider.publish(Topologies.ring("objects", 2, 5, 8, 2));
            TopologySnapshot after = router.snapshot().orElseThrow();
            assertThat(after.epoch()).isEqualTo(2);
            OwnershipDelta delta = OwnershipDelta.between(before, after);
            assertThat(delta.changes()).isNotEmpty();
            assertThat(delta.changes()).allSatisfy(change ->
                    assertThat(change.gained().size() + change.lost().size()).isPositive());
        }
    }

    @Test
    void checksAndAdmitsAtARecipient() {
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ring("objects", 1, 6, 64, 3));
        try (Router router = Sharder.router(config(provider).build())) {
            RoutingDecision decision = router.route(KEY);
            NodeId owner = decision.primary().node();
            Recipient recipient = router.recipient(owner);
            Verdict verdict = recipient.check(decision.token(), decision.routingKey().toBytes());
            assertThat(verdict.relation()).isEqualTo(Relation.SAME);
            assertThat(verdict.ownership()).isEqualTo(Ownership.OWNER);
            recipient.admit(decision.token(), decision.routingKey().toBytes());

            NodeId other = decision.preferenceList().get(5).node();
            assertThatThrownBy(() -> router.recipient(other)
                    .admit(decision.token(), decision.routingKey().toBytes()))
                    .isInstanceOf(com.codeheadsystems.sharder.error.NotOwnerException.class);
        }
    }

    @Test
    void reportsTheValuesInForceAndTheMetricsItHolds() {
        InMemoryTopologyProvider provider =
                new InMemoryTopologyProvider(Topologies.ring("objects", 1, 4, 64, 2));
        List<Event> events = new ArrayList<>();
        try (Router router = Sharder.router(config(provider).eventSink(events::add).build())) {
            router.route(KEY);
            assertThat(router.configuration().setting("recipientPolicy")).contains("strict");
            assertThat(router.configuration().setting("attemptLimit"))
                    .contains("the factor plus 2");
            assertThat(router.metrics().counters())
                    .containsKey("sharder.routing.decisions{strategy=ring}");
            assertThat(events).extracting(Event::name).contains("sharder.topology.installed");
        }
    }
}
