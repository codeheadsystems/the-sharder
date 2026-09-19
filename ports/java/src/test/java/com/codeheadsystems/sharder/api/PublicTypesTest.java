package com.codeheadsystems.sharder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.sharder.MonotonicClock;
import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.NodeSet;
import com.codeheadsystems.sharder.OverrideMode;
import com.codeheadsystems.sharder.Role;
import com.codeheadsystems.sharder.RouteOptions;
import com.codeheadsystems.sharder.Shortfall;
import com.codeheadsystems.sharder.config.HealthSettings;
import com.codeheadsystems.sharder.config.RouterConfig;
import com.codeheadsystems.sharder.config.StalePolicy;
import com.codeheadsystems.sharder.core.InMemoryTopologyProvider;
import com.codeheadsystems.sharder.error.ErrorCode;
import com.codeheadsystems.sharder.error.InvalidArgumentException;
import com.codeheadsystems.sharder.fence.Ownership;
import com.codeheadsystems.sharder.fence.RecipientPolicy;
import com.codeheadsystems.sharder.fence.Relation;
import com.codeheadsystems.sharder.health.HealthState;
import com.codeheadsystems.sharder.health.Outcome;
import com.codeheadsystems.sharder.observe.Labels;
import com.codeheadsystems.sharder.observe.Severity;
import com.codeheadsystems.sharder.placement.StrategyKind;
import com.codeheadsystems.sharder.topology.AdministrativeState;
import com.codeheadsystems.sharder.topology.FencingToken;
import com.codeheadsystems.sharder.topology.SourceVersion;
import com.codeheadsystems.sharder.topology.TopologyProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The public value types, each read the way the specification spells it. */
class PublicTypesTest {

    @Test
    void aNodeSetIteratesInAscendingNodeIdentity() {
        NodeSet set = NodeSet.of(List.of(NodeId.of("n3"), NodeId.of("n1"), NodeId.of("n2"),
                NodeId.of("n1")));
        assertThat(set.size()).isEqualTo(3);
        assertThat(set.asList()).containsExactly(NodeId.of("n1"), NodeId.of("n2"),
                NodeId.of("n3"));
        assertThat(set.contains(NodeId.of("n2"))).isTrue();
        assertThat(NodeSet.empty().isEmpty()).isTrue();
    }

    @Test
    void aFencingTokenEncodesAsFence021Spells() {
        FencingToken token = FencingToken.of("objects", 7);
        // u32be(len(topologyId)) || topologyId || u64be(epoch)
        assertThat(HexFormat.of().formatHex(token.toByteArray()))
                .isEqualTo("000000076f626a656374730000000000000007");
        assertThat(token.digest()).isEmpty();
        // FENCE-011: the digest is diagnostic, so two tokens are equal without it.
        assertThat(token).isEqualTo(new FencingToken("objects", 7,
                java.util.Optional.of(com.codeheadsystems.sharder.Digest.ofBytes(new byte[32]))));
        assertThatThrownBy(() -> FencingToken.of("objects", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSourceVersionCopiesItsOctets() {
        byte[] octets = "revision-4".getBytes(StandardCharsets.UTF_8);
        SourceVersion version = SourceVersion.of(octets);
        octets[0] = 'X';
        assertThat(version.toByteArray()).isEqualTo("revision-4".getBytes(StandardCharsets.UTF_8));
        assertThat(version.length()).isEqualTo(10);
        assertThat(version).isEqualTo(SourceVersion.of(
                "revision-4".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> SourceVersion.of(new byte[SourceVersion.MAX_LENGTH + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void labelsCarryTheirNamesInOrder() {
        Labels labels = Labels.of("strategy", "ring", "outcome", "installed");
        assertThat(labels.size()).isEqualTo(2);
        assertThat(labels.name(0)).isEqualTo("strategy");
        assertThat(labels.value(1)).isEqualTo("installed");
        assertThat(labels.asMap()).containsEntry("strategy", "ring");
        assertThat(Labels.none().size()).isZero();
        assertThat(labels).isEqualTo(Labels.of("strategy", "ring", "outcome", "installed"));
    }

    @Test
    void aMonotonicClockStartsAtZeroAndNeverGoesBackwards() {
        MonotonicClock clock = MonotonicClock.systemNanoTime();
        long first = clock.millis();
        assertThat(first).isGreaterThanOrEqualTo(0);
        assertThat(clock.millis()).isGreaterThanOrEqualTo(first);
    }

    @Test
    void routeOptionsRefuseALimitBelowOne() {
        assertThat(RouteOptions.DEFAULTS.withAttemptLimit(4).attemptLimit()).hasValue(4);
        assertThat(RouteOptions.DEFAULTS.withExplain(true).explain()).isTrue();
        assertThatThrownBy(() -> RouteOptions.DEFAULTS.withAttemptLimit(0))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void aConfigurationRefusesAValueOutsideItsRange() {
        TopologyProvider provider = new InMemoryTopologyProvider();
        assertThatThrownBy(() -> RouterConfig.builder().provider(provider).attemptLimit(0).build())
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("attemptLimit");
        // CFG-011: a provider that both pushes and pulls is reconciled rarely.
        assertThatThrownBy(() -> RouterConfig.builder().provider(provider)
                .pollIntervalMillis(30000).reconcileIntervalMillis(1000).build())
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("reconcileIntervalMillis");
        assertThatThrownBy(() -> RouterConfig.builder().build())
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("provider");
        assertThatThrownBy(() -> RouterConfig.builder().provider(provider)
                .health(HealthSettings.defaults().withWindowMillis(0)).build())
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("windowMillis");
    }

    @Test
    void aDurationSetterRefusesSubMillisecondPrecision() {
        TopologyProvider provider = new InMemoryTopologyProvider();
        assertThat(RouterConfig.builder().provider(provider)
                .pollInterval(Duration.ofSeconds(5)).build().provider().pollIntervalMillis())
                .isEqualTo(5000);
        assertThatThrownBy(() -> RouterConfig.builder().provider(provider)
                .pollInterval(Duration.ofNanos(1500)).build())
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("sub-millisecond");
        assertThatThrownBy(() -> RouterConfig.builder().provider(provider)
                .initialTimeout(Duration.ofSeconds(-1)).build())
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("not negative");
    }

    @Test
    void theConfigurationViewReportsTheDefaultsTheIntegratorDidNotSet() {
        RouterConfig config = RouterConfig.builder()
                .provider(new InMemoryTopologyProvider())
                .stalePolicy(StalePolicy.REFUSE)
                .recipientPolicy(RecipientPolicy.STABLE)
                .build();
        assertThat(config.settings())
                .containsEntry("stalePolicy", "refuse")
                .containsEntry("recipientPolicy", "stable")
                .containsEntry("pollIntervalMillis", "30000")
                .containsEntry("metricsRegistry", "held internally");
        assertThat(config.executor()).isEmpty();
        assertThat(config.healthView()).isEmpty();
    }

    @Test
    void aProviderDeclaresAtLeastOneCapability() {
        assertThat(TopologyProvider.Capabilities.pullOnly().pull()).isTrue();
        assertThat(TopologyProvider.Capabilities.pushOnly().push()).isTrue();
        assertThatThrownBy(() -> new TopologyProvider.Capabilities(false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Object[]> vocabularies() {
        return Stream.of(
                new Object[] {Role.REPLICA, "replica"},
                new Object[] {Role.FALLBACK, "fallback"},
                new Object[] {Shortfall.NODES, "nodes"},
                new Object[] {Shortfall.DOMAINS, "domains"},
                new Object[] {OverrideMode.BOTH, "both"},
                new Object[] {HealthState.PROBATION, "probation"},
                new Object[] {Outcome.CANCELLED, "cancelled"},
                new Object[] {Relation.SENDER_BEHIND, "senderBehind"},
                new Object[] {Ownership.NOT_OWNER, "notOwner"},
                new Object[] {RecipientPolicy.STRICT, "strict"},
                new Object[] {AdministrativeState.DRAINING, "draining"},
                new Object[] {StrategyKind.RENDEZVOUS, "rendezvous"},
                new Object[] {StalePolicy.SERVE, "serve"},
                new Object[] {Severity.WARNING, "warning"});
    }

    @ParameterizedTest
    @MethodSource("vocabularies")
    void everyVocabularyRoundTripsThroughItsSpelling(Object value, String spelling) {
        assertThat(value.toString()).isNotEmpty();
        assertThat(switch (value) {
            case Role role -> Role.of(spelling) == role && role.spelling().equals(spelling);
            case Shortfall shortfall -> Shortfall.of(spelling) == shortfall
                    && shortfall.spelling().equals(spelling);
            case OverrideMode mode -> OverrideMode.of(spelling) == mode
                    && mode.spelling().equals(spelling);
            case HealthState state -> HealthState.of(spelling) == state
                    && state.spelling().equals(spelling);
            case Outcome outcome -> Outcome.of(spelling) == outcome
                    && outcome.spelling().equals(spelling);
            case Relation relation -> Relation.of(spelling) == relation
                    && relation.spelling().equals(spelling);
            case Ownership ownership -> Ownership.of(spelling) == ownership
                    && ownership.spelling().equals(spelling);
            case RecipientPolicy policy -> RecipientPolicy.of(spelling) == policy
                    && policy.spelling().equals(spelling);
            case AdministrativeState state -> AdministrativeState.of(spelling) == state
                    && state.spelling().equals(spelling);
            case StrategyKind kind -> StrategyKind.of(spelling) == kind
                    && kind.spelling().equals(spelling);
            case StalePolicy policy -> StalePolicy.of(spelling) == policy
                    && policy.spelling().equals(spelling);
            case Severity severity -> Severity.of(spelling) == severity
                    && severity.spelling().equals(spelling);
            default -> false;
        }).as(spelling).isTrue();
    }

    @Test
    void everyConditionCarriesItsCodeAndName() {
        assertThat(ErrorCode.ofCode(103)).isEqualTo(ErrorCode.UNREADY);
        assertThat(ErrorCode.ofName("notOwner").code()).isEqualTo(301);
        assertThat(ErrorCode.EXHAUSTED.causes())
                .containsExactly("preferenceList", "attemptLimit", "retryBudget");
    }
}
