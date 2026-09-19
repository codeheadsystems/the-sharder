package com.codeheadsystems.sharder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.sharder.MonotonicClock;
import com.codeheadsystems.sharder.Router;
import com.codeheadsystems.sharder.config.RouterConfig;
import com.codeheadsystems.sharder.core.InMemoryTopologyProvider;
import com.codeheadsystems.sharder.core.Sharder;
import com.codeheadsystems.sharder.error.PlanRefusedException;
import com.codeheadsystems.sharder.migrate.HandoffCoordinator;
import com.codeheadsystems.sharder.migrate.HandoffId;
import com.codeheadsystems.sharder.migrate.HandoffState;
import com.codeheadsystems.sharder.migrate.HookDeclaration;
import com.codeheadsystems.sharder.migrate.HookResult;
import com.codeheadsystems.sharder.migrate.MigrationPlan;
import com.codeheadsystems.sharder.migrate.MigrationPolicy;
import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.error.InvalidArgumentException;
import com.codeheadsystems.sharder.migrate.CutoverRecord;
import com.codeheadsystems.sharder.migrate.CutoverResult;
import com.codeheadsystems.sharder.migrate.Observation;
import com.codeheadsystems.sharder.migrate.ObserveResult;
import com.codeheadsystems.sharder.migrate.ReobserveOutcome;
import com.codeheadsystems.sharder.migrate.PressureGauge;
import com.codeheadsystems.sharder.migrate.PressureLevel;
import com.codeheadsystems.sharder.migrate.PressureScope;
import com.codeheadsystems.sharder.migrate.StepOutcome;
import com.codeheadsystems.sharder.migrate.VerifyResult;
import com.codeheadsystems.sharder.topology.TopologySnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The migration surface an integrator drives, over a plan between two slot topologies. */
class MigrationTest {

    private static final MonotonicClock CLOCK = MonotonicClock.fixed(1000);

    /**
     * The two snapshots a plan moves between.
     *
     * <p>A snapshot outlives the router that installed it: it is immutable and holds its own
     * prepared placement, so the router is closed here and the plan reads what it produced.
     */
    private record Pair(TopologySnapshot from, TopologySnapshot to) {
    }

    private static Pair snapshots(byte[] before, byte[] after) {
        InMemoryTopologyProvider provider = new InMemoryTopologyProvider(before);
        try (Router router = Sharder.router(RouterConfig.builder().provider(provider).clock(CLOCK)
                .build())) {
            TopologySnapshot from = router.snapshot().orElseThrow();
            provider.publish(after);
            return new Pair(from, router.snapshot().orElseThrow());
        }
    }

    private static TopologySnapshot snapshot(byte[] document) {
        try (Router router = Sharder.router(RouterConfig.builder()
                .provider(new InMemoryTopologyProvider(document)).clock(CLOCK).build())) {
            return router.snapshot().orElseThrow();
        }
    }

    private static byte[] slots(long epoch, String... owners) {
        StringBuilder json = new StringBuilder();
        json.append("{\"formatVersion\":\"1.0\",\"topologyId\":\"slots\",\"epoch\":").append(epoch)
                .append(",\"replication\":{\"factor\":1},\"strategy\":{\"kind\":\"slot\"")
                .append(",\"slotCount\":").append(owners.length)
                .append(",\"assignment\":\"explicit\",\"assignments\":[");
        for (int slot = 0; slot < owners.length; slot++) {
            // A slot range is text, and a single index spells its own low and high.
            json.append(slot == 0 ? "" : ",").append("{\"slots\":[\"").append(slot)
                    .append("\"],\"nodes\":[\"").append(owners[slot]).append("\"]}");
        }
        json.append("]},\"nodes\":[{\"id\":\"a\",\"weight\":1},{\"id\":\"b\",\"weight\":1},")
                .append("{\"id\":\"c\",\"weight\":1}]}");
        return json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void oneHandoffRunsFromPlannedToComplete() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        RecordingHooks hooks = new RecordingHooks();
        MigrationPlan plan = Sharder.coordinator()
                .plan(pair.from(), pair.to(), hooks, MigrationPolicy.defaults());
        List<HandoffId> ids = new ArrayList<>();
        plan.handoffs().forEachRemaining(ids::add);
        assertThat(ids).hasSize(1);
        HandoffId id = ids.get(0);
        assertThat(plan.state(id)).isEqualTo(HandoffState.PLANNED);
        assertThat(plan.delta().changes()).hasSize(1);

        List<StepOutcome> outcomes = new ArrayList<>();
        for (int step = 0; step < 12; step++) {
            StepOutcome outcome = plan.step(CLOCK);
            outcomes.add(outcome);
            if (outcome instanceof StepOutcome.Settled) {
                break;
            }
        }
        assertThat(plan.state(id)).isEqualTo(HandoffState.COMPLETE);
        assertThat(outcomes).last().isInstanceOf(StepOutcome.Settled.class);
        // MOVE-071: one hook per step, in the order the state machine reaches them.
        assertThat(hooks.calls()).containsExactly("prepare:1", "transfer:1:1", "catchUp:1:1",
                "quiesce:1", "commitCutover:1", "verify:1", "cleanup:1");
        assertThat(plan.summary()).containsEntry(HandoffState.COMPLETE, 1);
        assertThat(hooks.committed()).containsKey("1");
        // RATE-111: a plan with nothing left to do answers idle rather than spinning.
        assertThat(plan.step(CLOCK)).isInstanceOf(StepOutcome.Idle.class);
    }

    @Test
    void aDeferralHoldsOneHandoffAndReleasesItOnTheClock() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        RecordingHooks hooks = new RecordingHooks().preparing(new HookResult.Deferred(5000));
        MigrationPlan plan = Sharder.coordinator()
                .plan(pair.from(), pair.to(), hooks, MigrationPolicy.defaults());
        AtomicLong now = new AtomicLong(1000);
        MonotonicClock moving = now::get;
        assertThat(plan.step(moving)).isInstanceOf(StepOutcome.Advanced.class);
        assertThat(plan.step(moving)).isEqualTo(new StepOutcome.Deferred(handoff(plan), 5000));
        // MOVE-171: the deferral is not retried before its interval has elapsed.
        assertThat(plan.step(moving)).isInstanceOf(StepOutcome.Idle.class);
        now.set(6001);
        hooks.preparing(HookResult.success());
        assertThat(plan.step(moving)).isInstanceOf(StepOutcome.Advanced.class);
    }

    @Test
    void pressureWithholdsAdmissionAndTheHooksItNames() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        RecordingHooks hooks = new RecordingHooks().bulkRemaining(500);
        List<PressureLevel> levels = new ArrayList<>(List.of(PressureLevel.SOFT));
        PressureGauge gauge = scope -> scope instanceof PressureScope.Cluster
                ? levels.get(0) : PressureLevel.NONE;
        MigrationPlan plan = Sharder.coordinator().plan(pair.from(), pair.to(), hooks,
                MigrationPolicy.defaults().withPressureGauge(gauge));
        // RATE-081: soft admits no handoff out of planned.
        assertThat(plan.step(CLOCK)).isInstanceOf(StepOutcome.Idle.class);
        levels.set(0, PressureLevel.NONE);
        assertThat(plan.step(CLOCK)).isInstanceOf(StepOutcome.Advanced.class);
        assertThat(plan.step(CLOCK)).isInstanceOf(StepOutcome.Advanced.class);
        levels.set(0, PressureLevel.HARD);
        // RATE-091: hard withholds transfer, and the handoff is transferring.
        assertThat(plan.step(CLOCK)).isInstanceOf(StepOutcome.Idle.class);
        assertThat(hooks.calls()).doesNotContain("transfer:1:1");
    }

    @Test
    void aMismatchedVerificationFailsTheHandoff() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        RecordingHooks hooks = new RecordingHooks()
                .verifying(new VerifyResult.Mismatched("row count differs"));
        MigrationPlan plan = Sharder.coordinator()
                .plan(pair.from(), pair.to(), hooks, MigrationPolicy.defaults());
        StepOutcome outcome = null;
        for (int step = 0; step < 12 && !(outcome instanceof StepOutcome.Settled); step++) {
            outcome = plan.step(CLOCK);
        }
        assertThat(plan.state(handoff(plan))).isEqualTo(HandoffState.FAILED);
        assertThat(hooks.calls()).doesNotContain("cleanup:1");
    }

    @Test
    void hooksThatDeclareNoVerificationReachCleanupFromTheRecord() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        RecordingHooks hooks = new RecordingHooks()
                .declaring(new HookDeclaration("rows", true, false, false));
        MigrationPlan plan = Sharder.coordinator()
                .plan(pair.from(), pair.to(), hooks, MigrationPolicy.defaults());
        for (int step = 0; step < 12; step++) {
            if (plan.step(CLOCK) instanceof StepOutcome.Settled) {
                break;
            }
        }
        assertThat(plan.state(handoff(plan))).isEqualTo(HandoffState.COMPLETE);
        // MOVE-181: cleanup follows the committed record where no verification is declared.
        assertThat(hooks.calls()).doesNotContain("verify:1").contains("cleanup:1");
    }

    @Test
    void recoveryReadsTheDurableStateAndResumesFromIt() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        RecordingHooks hooks = new RecordingHooks();
        MigrationPlan plan = Sharder.coordinator()
                .plan(pair.from(), pair.to(), hooks, MigrationPolicy.defaults());
        plan.step(CLOCK);
        var report = plan.recover(CLOCK);
        // MOVE-211: a prepared destination with no record resumes at transferring.
        assertThat(report.resolved()).hasSize(1);
        assertThat(report.unresolved()).isEmpty();
        assertThat(plan.state(handoff(plan))).isEqualTo(HandoffState.TRANSFERRING);

        // MOVE-112: unavailable establishes nothing, and the handoff is untouched.
        hooks.observing(new ObserveResult.Unavailable("the store is unreachable"));
        var second = plan.recover(CLOCK);
        assertThat(second.unresolved()).hasSize(1);
        assertThat(second.retryAfterMillis()).isPositive();
        assertThat(plan.state(handoff(plan))).isEqualTo(HandoffState.TRANSFERRING);
    }

    @Test
    void aRebaseKeepsWhatTheNewerSnapshotStillHolds() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        RecordingHooks hooks = new RecordingHooks();
        TopologySnapshot newer = snapshot(slots(3, "a", "c"));
        MigrationPlan plan = Sharder.coordinator()
                .plan(pair.from(), pair.to(), hooks, MigrationPolicy.defaults());
        plan.step(CLOCK);
        var report = plan.rebase(newer);
        assertThat(report.fromEpoch()).isEqualTo(2);
        assertThat(report.toEpoch()).isEqualTo(3);
        assertThat(report.rebased()).hasSize(1);
        assertThat(report.aborted()).isEmpty();
        // MOVE-095: a rebase onto an epoch the plan already targets is refused.
        assertThatThrownBy(() -> plan.rebase(pair.to()))
                .isInstanceOf(PlanRefusedException.class)
                .satisfies(failure -> assertThat(((PlanRefusedException) failure).reason())
                        .isEqualTo(PlanRefusedException.Cause.EPOCH_NOT_ADVANCING));
    }

    @Test
    void aRebaseReportsAHandoffPastTheCutoverAsUnchanged() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        RecordingHooks hooks = new RecordingHooks();
        TopologySnapshot newer = snapshot(slots(3, "a", "c"));
        MigrationPlan plan = Sharder.coordinator()
                .plan(pair.from(), pair.to(), hooks, MigrationPolicy.defaults());
        // Four steps reach the cutover, a fifth quiesces the source, and a sixth commits.
        for (int step = 0; step < 6; step++) {
            plan.step(CLOCK);
        }
        HandoffId id = handoff(plan);
        assertThat(plan.state(id)).isEqualTo(HandoffState.VERIFYING);
        var report = plan.rebase(newer);
        // MOVE-099: a handoff that entered the cutover keeps its target epoch and is unchanged.
        assertThat(report.unchanged()).containsExactly(id);
        assertThat(report.rebased()).isEmpty();
        assertThat(report.aborted()).isEmpty();
        assertThat(plan.state(id)).isEqualTo(HandoffState.VERIFYING);
    }

    @Test
    void anAbortInTheCutoverIsRefusedWhereTheRecordExists() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        RecordingHooks hooks = new RecordingHooks();
        MigrationPlan plan = Sharder.coordinator()
                .plan(pair.from(), pair.to(), hooks, MigrationPolicy.defaults());
        for (int step = 0; step < 4; step++) {
            plan.step(CLOCK);
        }
        HandoffId id = handoff(plan);
        assertThat(plan.state(id)).isEqualTo(HandoffState.CUTOVER);
        // MOVE-431: the abort is admitted while no record belonging to the handoff exists.
        plan.abort(id, "the operator changed their mind");
        assertThat(plan.state(id)).isEqualTo(HandoffState.ABORTING);
        assertThat(hooks.calls()).contains("observe:1");
    }

    @Test
    void aRetryableHookExhaustsItsBudgetAndCompensates() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        RecordingHooks hooks = new RecordingHooks()
                .preparing(new HookResult.Retryable("the destination is busy"));
        MigrationPlan plan = Sharder.coordinator()
                .plan(pair.from(), pair.to(), hooks, MigrationPolicy.defaults());
        AtomicLong now = new AtomicLong(1000);
        MonotonicClock moving = now::get;
        plan.step(moving);
        List<StepOutcome> outcomes = new ArrayList<>();
        for (int attempt = 0; attempt < 8; attempt++) {
            outcomes.add(plan.step(moving));
            // MOVE-171: each retry waits the backoff of RATE-051 before the next attempt.
            now.addAndGet(120000);
        }
        // MOVE-021: attempts exhausted short of the cutover is an abort rather than a failure,
        // and MOVE-421 makes compensation the duty of `rollback` before the handoff settles.
        assertThat(plan.state(handoff(plan))).isEqualTo(HandoffState.ABORTED);
        assertThat(hooks.calls()).contains("rollback:1");
        assertThat(outcomes).hasAtLeastOneElementOfType(StepOutcome.Deferred.class)
                .hasAtLeastOneElementOfType(StepOutcome.Settled.class);
    }

    @Test
    void aCommitNobodyEstablishedLeavesTheHandoffUndetermined() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        RecordingHooks hooks = new RecordingHooks()
                .committing(new CutoverResult.Undetermined());
        MigrationPlan plan = Sharder.coordinator()
                .plan(pair.from(), pair.to(), hooks, MigrationPolicy.defaults());
        StepOutcome outcome = null;
        for (int step = 0; step < 8 && !(outcome instanceof StepOutcome.Settled); step++) {
            outcome = plan.step(CLOCK);
        }
        // MOVE-231: what the commit did is not established, and no later step establishes it.
        assertThat(plan.state(handoff(plan))).isEqualTo(HandoffState.FAILED);
        assertThat(outcome).isEqualTo(new StepOutcome.Settled(handoff(plan),
                HandoffState.FAILED));

        // MOVE-233: a re-observation is the one call that moves a terminal handoff.
        hooks.committing(null);
        var reobserved = plan.reobserve(handoff(plan), CLOCK);
        assertThat(reobserved).isInstanceOf(ReobserveOutcome.Resumed.class);
    }

    @Test
    void anAbortIsRefusedPastTheCutover() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        RecordingHooks hooks = new RecordingHooks();
        MigrationPlan plan = Sharder.coordinator()
                .plan(pair.from(), pair.to(), hooks, MigrationPolicy.defaults());
        for (int step = 0; step < 6; step++) {
            plan.step(CLOCK);
        }
        HandoffId id = handoff(plan);
        assertThat(plan.state(id)).isEqualTo(HandoffState.VERIFYING);

        // MOVE-441: ownership has moved, so an abort is refused rather than compensated, and
        // MOVE-191 is what makes compensating it destroy the only surviving copy.
        assertThatThrownBy(() -> plan.abort(id, "the operator changed their mind"))
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("MOVE-441");
        plan.abortAll("shutting down");
        assertThat(plan.state(id)).isEqualTo(HandoffState.VERIFYING);
        assertThat(hooks.calls()).doesNotContain("rollback:1");
    }

    @Test
    void anAbortInTheCutoverIsRefusedWhileTheRecordExists() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        // The durable state already holds a record for this handoff, which the hooks observe.
        RecordingHooks hooks = new RecordingHooks();
        MigrationPlan plan = Sharder.coordinator()
                .plan(pair.from(), pair.to(), hooks, MigrationPolicy.defaults());
        for (int step = 0; step < 4; step++) {
            plan.step(CLOCK);
        }
        HandoffId id = handoff(plan);
        assertThat(plan.state(id)).isEqualTo(HandoffState.CUTOVER);
        hooks.observing(new ObserveResult.Observed(new Observation(
                Optional.of(CutoverRecord.of(ShardId.of("1"), "slots", 2, NodeId.of("c"))),
                true, true, false)));
        // MOVE-431: the abort is admitted only while no record belonging to the handoff exists.
        assertThatThrownBy(() -> plan.abort(id, "too late"))
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("MOVE-191");

        // MOVE-431 again: an observation that establishes nothing refuses the abort as well.
        hooks.observing(new ObserveResult.Undetermined());
        assertThatThrownBy(() -> plan.abort(id, "too late"))
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("durable state");
    }

    @Test
    void theCommitDeadlineOfThePolicyReachesTheQuiesce() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        // A lease of five seconds carries a commit window under a deadline of one second, and
        // carries none under the default of thirty.
        RecordingHooks hooks = new RecordingHooks().lease(5000);
        MigrationPolicy tight = new MigrationPolicy(4, 1, 1, 1, 30000, 5, 1000, 60000, 1000, 1000,
                0L, -1L, Optional.empty());
        MigrationPlan plan = Sharder.coordinator().plan(pair.from(), pair.to(), hooks, tight);
        for (int step = 0; step < 6; step++) {
            plan.step(CLOCK);
        }
        assertThat(plan.state(handoff(plan))).isEqualTo(HandoffState.VERIFYING);

        RecordingHooks spent = new RecordingHooks().lease(5000);
        MigrationPlan slow = Sharder.coordinator()
                .plan(pair.from(), pair.to(), spent, MigrationPolicy.defaults());
        for (int step = 0; step < 6; step++) {
            slow.step(CLOCK);
        }
        // MOVE-336: under the default deadline the same lease carries no commit window at all,
        // so the handoff compensates and settles instead of committing.
        assertThat(slow.state(handoff(slow))).isEqualTo(HandoffState.ABORTED);
        assertThat(spent.calls()).contains("rollback:1").doesNotContain("commitCutover:1");
    }

    @Test
    void aRebasePendingPlanStillFinishesWorkAlreadyBegun() {
        Pair pair = snapshots(slots(1, "a", "b"), slots(2, "a", "c"));
        RecordingHooks hooks = new RecordingHooks();
        TopologySnapshot newer = snapshot(slots(3, "a", "c"));
        MigrationPlan plan = Sharder.coordinator()
                .plan(pair.from(), pair.to(), hooks, MigrationPolicy.defaults());
        plan.step(CLOCK);
        plan.step(CLOCK);
        assertThat(plan.state(handoff(plan))).isEqualTo(HandoffState.TRANSFERRING);
        plan.onSnapshotInstalled(newer);
        // MOVE-093: no handoff leaves `planned` and none reaches `cutover`, and every other
        // transition stays available, so the transfer already begun finishes.
        assertThat(plan.step(CLOCK)).isInstanceOf(StepOutcome.Advanced.class);
        assertThat(plan.state(handoff(plan))).isEqualTo(HandoffState.CATCHING_UP);
        assertThat(plan.step(CLOCK)).isInstanceOf(StepOutcome.Idle.class);
        assertThat(plan.state(handoff(plan))).isEqualTo(HandoffState.CATCHING_UP);
    }

    @Test
    void aPlanIsRefusedWhereTheStrategySupportsNoHandoff() {
        Pair pair = snapshots(Topologies.rendezvous("rv", 1, 4, 4, 2),
                Topologies.rendezvous("rv", 2, 5, 4, 2));
        HandoffCoordinator coordinator = Sharder.coordinator();
        RecordingHooks hooks = new RecordingHooks();
        assertThatThrownBy(() -> coordinator.plan(pair.from(), pair.to(), hooks,
                MigrationPolicy.defaults()))
                .isInstanceOf(PlanRefusedException.class)
                .satisfies(failure -> assertThat(((PlanRefusedException) failure).reason())
                        .isEqualTo(PlanRefusedException.Cause.STRATEGY_UNSUPPORTED));
    }

    @Test
    void aPolicyRefusesAThresholdPairThatNeverSettles() {
        assertThatThrownBy(() -> new MigrationPolicy(4, 1, 1, 1, 30000, 5, 1000, 60000, 30000,
                1000, 100L, 100L, java.util.Optional.empty()))
                .isInstanceOf(com.codeheadsystems.sharder.error.InvalidArgumentException.class)
                .hasMessageContaining("reTransferResidualThreshold");
        assertThatThrownBy(() -> MigrationPolicy.defaults().withInitialStepBudget(0))
                .isInstanceOf(com.codeheadsystems.sharder.error.InvalidArgumentException.class)
                .hasMessageContaining("initialStepBudget");
        // RATE-051: the backoff doubles per attempt and stops at the cap.
        assertThat(MigrationPolicy.defaults().retryBackoffMillis(1)).isEqualTo(1000);
        assertThat(MigrationPolicy.defaults().retryBackoffMillis(4)).isEqualTo(8000);
        assertThat(MigrationPolicy.defaults().retryBackoffMillis(40)).isEqualTo(60000);
    }

    private static HandoffId handoff(MigrationPlan plan) {
        return plan.handoffs().next();
    }
}
