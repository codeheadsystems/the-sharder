package com.codeheadsystems.sharder.migrate.internal;

import com.codeheadsystems.sharder.MonotonicClock;
import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.core.internal.migrate.Handoff;
import com.codeheadsystems.sharder.core.internal.route.OwnershipDelta;
import com.codeheadsystems.sharder.core.internal.route.PlacementEngine;
import com.codeheadsystems.sharder.MonotonicClock;
import com.codeheadsystems.sharder.core.internal.observe.MetricsHolder;
import com.codeheadsystems.sharder.observe.Event;
import com.codeheadsystems.sharder.observe.Severity;
import com.codeheadsystems.sharder.core.internal.snapshot.DocumentSnapshot;
import com.codeheadsystems.sharder.error.InvalidArgumentException;
import com.codeheadsystems.sharder.error.PlanRefusedException;
import com.codeheadsystems.sharder.migrate.CatchUpResult;
import com.codeheadsystems.sharder.migrate.CutoverRecord;
import com.codeheadsystems.sharder.migrate.CutoverResult;
import com.codeheadsystems.sharder.migrate.HandoffContext;
import com.codeheadsystems.sharder.migrate.HandoffId;
import com.codeheadsystems.sharder.migrate.HandoffState;
import com.codeheadsystems.sharder.migrate.HookDeclaration;
import com.codeheadsystems.sharder.migrate.HookResult;
import com.codeheadsystems.sharder.migrate.MigrationPlan;
import com.codeheadsystems.sharder.migrate.MigrationPolicy;
import com.codeheadsystems.sharder.migrate.MovementHooks;
import com.codeheadsystems.sharder.migrate.Observation;
import com.codeheadsystems.sharder.migrate.ObserveResult;
import com.codeheadsystems.sharder.migrate.PressureGauge;
import com.codeheadsystems.sharder.migrate.PressureLevel;
import com.codeheadsystems.sharder.migrate.PressureScope;
import com.codeheadsystems.sharder.migrate.QuiesceResult;
import com.codeheadsystems.sharder.migrate.RebaseReport;
import com.codeheadsystems.sharder.migrate.RecoveryReport;
import com.codeheadsystems.sharder.migrate.ReobserveOutcome;
import com.codeheadsystems.sharder.migrate.StepOutcome;
import com.codeheadsystems.sharder.migrate.TransferResult;
import com.codeheadsystems.sharder.migrate.VerifyResult;
import com.codeheadsystems.sharder.topology.TopologySnapshot;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The plan an integrator drives, over the handoff machine of {@code MOVE-021}.
 *
 * <p>This is where a hook answer becomes a transition. The machine states which transitions exist
 * and refuses the rest; this class decides which hook one state calls, what its answer means, and
 * which handoff a step advances. It holds no thread and no timer: everything happens inside a call
 * the integrator made, under {@code CORE-060}.
 *
 * <p>{@code step} claims one handoff with a compare-and-set, calls at most one hook with no monitor
 * held, and releases the claim, which is what {@code MOVE-071} and {@code CORE-063} ask for
 * together.
 */
public final class DefaultMigrationPlan implements MigrationPlan {

    /** What the driver holds about one handoff beside the state the machine holds. */
    private static final class Driving {
        private final AtomicBoolean claimed = new AtomicBoolean();
        private int attempts;
        private long deferredUntil = Long.MIN_VALUE;
    }

    private final com.codeheadsystems.sharder.core.internal.migrate.MigrationPlan machine;
    private final MovementHooks hooks;
    private final HookDeclaration declaration;
    private final MigrationPolicy policy;
    private final PressureGauge gauge;
    private final DocumentSnapshot source;
    private final Map<String, Driving> driving = new ConcurrentHashMap<>();
    /**
     * The lock every call into the machine takes, and no hook call is made under.
     *
     * <p>The machine is a plain state machine with no lock of its own, and {@code MOVE-071}
     * permits concurrent calls to {@code step}, so its transitions are serialised here. It is held
     * for the length of a transition and never across a call to a movement hook, under
     * {@code CORE-063}.
     */
    private final Object machineLock = new Object();

    /** The states {@code MOVE-096} classifies, which are the states a rebase may move. */
    private static final Set<HandoffState> CLASSIFIED = EnumSet.of(
            HandoffState.PLANNED, HandoffState.PREPARING, HandoffState.TRANSFERRING,
            HandoffState.CATCHING_UP);

    /** The states {@code MOVE-441} admits an abort in. */
    private static final Set<HandoffState> ABORTABLE = EnumSet.of(
            HandoffState.PLANNED, HandoffState.PREPARING, HandoffState.TRANSFERRING,
            HandoffState.CATCHING_UP, HandoffState.CUTOVER);

    private volatile DocumentSnapshot target;
    private volatile com.codeheadsystems.sharder.topology.OwnershipDelta delta;
    private final MetricsHolder metrics;
    // OBS-021 stamps every event with an instant. It is not the clock `step` is driven with: a
    // caller advances a plan on its own clock and an event records when it was reported.
    private final MonotonicClock eventClock;

    /** The plan over one machine, its hooks, and the policy that bounds it. */
    DefaultMigrationPlan(com.codeheadsystems.sharder.core.internal.migrate.MigrationPlan machine,
                         DocumentSnapshot source, DocumentSnapshot target, MovementHooks hooks,
                         MigrationPolicy policy, MetricsHolder metrics, MonotonicClock eventClock) {
        this.metrics = metrics;
        this.eventClock = eventClock;
        this.machine = machine;
        this.source = source;
        this.target = target;
        this.hooks = hooks;
        this.declaration = hooks.declare();
        this.policy = policy;
        this.gauge = policy.pressureGauge().orElseGet(PressureGauge::none);
        this.delta = com.codeheadsystems.sharder.topology.OwnershipDelta.between(source, target);
        machine.handoffs().forEach(id -> driving.put(id, new Driving()));
    }

    /** One {@code migration.} event of {@code OBS-020}, stamped as {@code OBS-021} requires. */
    private void report(String name, Severity severity, Map<String, String> payload) {
        metrics.emit(new Event("sharder.migration." + name, eventClock.millis(),
                source.topologyId(), target.epoch(), severity, payload));
    }

    @Override
    public com.codeheadsystems.sharder.topology.OwnershipDelta delta() {
        return delta;
    }

    @Override
    public Iterator<HandoffId> handoffs() {
        List<HandoffId> ids = new ArrayList<>();
        machine.handoffs().forEach(id -> ids.add(HandoffId.of(id)));
        return List.copyOf(ids).iterator();
    }

    @Override
    public HandoffState state(HandoffId id) {
        return machine.handoff(id.value()).state();
    }

    @Override
    public Map<HandoffState, Integer> summary() {
        Map<HandoffState, Integer> counts = new TreeMap<>();
        machine.summary().forEach((spelling, count) ->
                counts.put(HandoffState.of(spelling), count));
        return Map.copyOf(counts);
    }

    @Override
    public StepOutcome step(MonotonicClock clock) {
        // MOVE-062: the supplied source is the one the plan reads for the duration of this call.
        long at = clock.millis();
        PressureLevel cluster = gauge.level(PressureScope.cluster());
        for (String id : machine.handoffs()) {
            Handoff handoff = machine.handoff(id);
            Driving state = driving.computeIfAbsent(id, ignored -> new Driving());
            if (handoff.state().terminal() || at < state.deferredUntil) {
                // RATE-101: a deferral is backpressure for its own handoff and delays no other.
                continue;
            }
            PressureLevel level = cluster
                    .max(gauge.level(PressureScope.node(handoff.source())))
                    .max(gauge.level(PressureScope.node(handoff.destination())));
            if (withheld(handoff.state(), level)) {
                continue;
            }
            if (!state.claimed.compareAndSet(false, true)) {
                // MOVE-071: another call is already advancing this handoff.
                continue;
            }
            try {
                StepOutcome outcome = advance(id, handoff, state, at, level);
                if (outcome != null) {
                    return outcome;
                }
            } finally {
                state.claimed.set(false);
            }
        }
        // RATE-111: nothing admissible, and nothing spun, waited, or slept on.
        return new StepOutcome.Idle();
    }

    /**
     * {@code OBS-020}: the transition, and the failure where one is reached.
     *
     * <p>{@code migration.state_changed} fires on every transition the machine took, and a
     * transition it dropped because the state had moved under a hook is not one, so nothing is
     * reported for it. A handoff reaching {@code failed} carries its kind, which is what an
     * operator acts on.
     */
    private void reportTransition(String id,
            String trigger,
            com.codeheadsystems.sharder.core.internal.migrate.MigrationPlan.StepOutcome outcome) {
        if (!"advanced".equals(outcome.outcome()) && !"settled".equals(outcome.outcome())) {
            return;
        }
        report("state_changed", Severity.INFO, Map.of("handoff", id,
                "shard", machine.handoff(id).shard(),
                "from", outcome.fromState(),
                "to", outcome.toState(),
                "trigger", trigger));
        if (HandoffState.FAILED.spelling().equals(outcome.toState())) {
            Handoff handoff = machine.handoff(id);
            report("failed", Severity.ERROR, Map.of("shard", handoff.shard(),
                    "kind", handoff.failureKind().orElse("unknown"),
                    "source", handoff.source().asText(),
                    "destination", handoff.destination().asText()));
        }
    }

    /**
     * One transition of the machine, taken under its lock.
     *
     * <p>The hook that produced the trigger ran outside every lock, so the state may have moved
     * since: a concurrent {@code abort} may have taken the handoff to {@code aborting} while a
     * hook was in flight. A trigger the state in force names no transition for is dropped rather
     * than raised, because the call that moved the handoff has already decided its fate.
     */
    private com.codeheadsystems.sharder.core.internal.migrate.MigrationPlan.StepOutcome stepMachine(
            String id, String trigger, long at) {
        return stepMachine(id, trigger, at, null);
    }

    private com.codeheadsystems.sharder.core.internal.migrate.MigrationPlan.StepOutcome stepMachine(
            String id, String trigger, long at, String pressure) {
        synchronized (machineLock) {
            HandoffState from = machine.handoff(id).state();
            try {
                var outcome = machine.step(id, trigger, at, pressure);
                reportTransition(id, trigger, outcome);
                return outcome;
            } catch (IllegalStateException moved) {
                // The state named no transition for this trigger, which is what a concurrent
                // abort or recovery leaves behind.
                return new com.codeheadsystems.sharder.core.internal.migrate.MigrationPlan
                        .StepOutcome("idle", id, from.spelling(), from.spelling(), null, null,
                                "stateMoved", null);
            }
        }
    }

    /** The claim of {@code MOVE-071}, taken by a call that is willing to wait for it. */
    private Driving claim(String id) {
        Driving state = driving.computeIfAbsent(id, ignored -> new Driving());
        while (!state.claimed.compareAndSet(false, true)) {
            // A step holding the claim runs to its hook's return, under the concurrent use table
            // of the Java binding, and this waits for it rather than interrupting it.
            Thread.onSpinWait();
        }
        return state;
    }

    /** Whether the pressure level withholds the step this state would take. */
    private static boolean withheld(HandoffState state, PressureLevel level) {
        if (level == PressureLevel.NONE) {
            return false;
        }
        // RATE-081 and RATE-091: neither level admits a handoff out of `planned`, and `hard`
        // withholds `transfer` and `catchUp` and nothing else, because every other hook completes
        // work already begun.
        return state == HandoffState.PLANNED
                || (level == PressureLevel.HARD
                        && (state == HandoffState.TRANSFERRING
                                || state == HandoffState.CATCHING_UP));
    }

    /** One handoff advanced by one hook call, or nothing where this one is not admissible. */
    private StepOutcome advance(String id, Handoff handoff, Driving state, long at,
                                PressureLevel level) {
        HandoffState from = handoff.state();
        return switch (from) {
            case PLANNED -> admit(id, handoff, level, at);
            case DIVIDING -> divide(id, handoff, state, at);
            case PREPARING -> afterHook(id, state, at, from,
                    hooks.prepare(context(handoff, state)), "prepareSuccess",
                    "attemptsExhausted");
            case TRANSFERRING -> transfer(id, handoff, state, at);
            case CATCHING_UP -> catchUp(id, handoff, state, at);
            case CUTOVER -> cutover(id, handoff, state, at);
            case VERIFYING -> verify(id, handoff, state, at);
            case CLEANUP -> afterHook(id, state, at, from,
                    hooks.cleanup(context(handoff, state)), "cleanupSuccess",
                    "attemptsExhausted");
            case ABORTING -> rollback(id, handoff, state, at);
            default -> null;
        };
    }

    /**
     * {@code MOVE-021}: admission out of {@code planned} is the rate policy's decision.
     *
     * <p>A level of {@code none} withholds nothing, and the machine reads a pressure level at all
     * only where one applies, so it is passed a level rather than the absence of one exactly when
     * {@code RATE-081} or {@code RATE-091} has something to say. The step above this one has
     * already refused a handoff either level withholds, so a level reaching here admits it.
     */
    private StepOutcome admit(String id, Handoff handoff, PressureLevel level, long at) {
        var outcome = stepMachine(id, "admittedByRatePolicy", at,
                level == PressureLevel.NONE ? null : level.spelling());
        if (!"advanced".equals(outcome.outcome())) {
            // A concurrency bound refused it, so another handoff may still be admissible.
            return null;
        }
        return new StepOutcome.Advanced(HandoffId.of(id), HandoffState.PLANNED,
                handoff.kind().local() ? HandoffState.DIVIDING : HandoffState.PREPARING);
    }

    /**
     * {@code LIN-052}: the local step, which divides a copy or folds several into one.
     *
     * <p>Nothing moves between nodes, so there is no destination to prepare and no cutover to
     * commit. A permanent answer is the one case that reaches {@code failed} directly, with the
     * kind {@code undivided} of {@code MOVE-011}, because the copy matches neither extent.
     */
    private StepOutcome divide(String id, Handoff handoff, Driving state, long at) {
        boolean folding = handoff.kind() == Handoff.Kind.COMBINE;
        HandoffContext context = context(handoff, state);
        HookResult answer = folding ? hooks.combine(context) : hooks.divide(context);
        if (answer instanceof HookResult.Permanent) {
            return advanced(id, HandoffState.DIVIDING, stepMachine(id, "dividePermanent", at));
        }
        return afterHook(id, state, at, HandoffState.DIVIDING, answer,
                folding ? "combineSuccess" : "divideSuccess", "attemptsExhausted");
    }

    private StepOutcome transfer(String id, Handoff handoff, Driving state, long at) {
        TransferResult answer = hooks.transfer(context(handoff, state), policy.initialStepBudget());
        if (!(answer.result() instanceof HookResult.Success)) {
            return afterHook(id, state, at, handoff.state(), answer.result(), null,
                    "attemptsExhausted");
        }
        state.attempts = 0;
        if (answer.bulkRemaining() == 0) {
            return advanced(id, handoff.state(), stepMachine(id, "noBulkRemaining", at));
        }
        return new StepOutcome.Progressed(HandoffId.of(id), answer.unitsMoved());
    }

    private StepOutcome catchUp(String id, Handoff handoff, Driving state, long at) {
        CatchUpResult answer = hooks.catchUp(context(handoff, state), policy.initialStepBudget());
        if (!(answer.result() instanceof HookResult.Success)) {
            return afterHook(id, state, at, handoff.state(), answer.result(), null,
                    "attemptsExhausted");
        }
        state.attempts = 0;
        // The thresholds are unsigned, under CFG-050, and the residue is the hook's own unit.
        if (Long.compareUnsigned(answer.residue(), policy.catchUpResidualThreshold()) <= 0) {
            return advanced(id, handoff.state(),
                    stepMachine(id, "residueAtOrBelowThreshold", at));
        }
        if (Long.compareUnsigned(answer.residue(), policy.reTransferResidualThreshold()) > 0) {
            return advanced(id, handoff.state(),
                    stepMachine(id, "residueAboveReTransferThreshold", at));
        }
        return new StepOutcome.Progressed(HandoffId.of(id), answer.unitsMoved());
    }

    /**
     * The cutover: a quiesce, then a commit, one hook per step.
     *
     * <p>{@code MOVE-331} takes the quiesce first, and {@code MOVE-333} refuses a commit whose
     * deadline runs past the horizon the lease leaves, which takes a fresh quiesce rather than a
     * commit nobody can bound.
     */
    private StepOutcome cutover(String id, Handoff handoff, Driving state, long at) {
        if (handoff.quiesceInstant().isEmpty()) {
            QuiesceResult answer = hooks.quiesce(context(handoff, state));
            if (!(answer.result() instanceof HookResult.Success)) {
                // MOVE-021: a quiesce that never succeeds compensates, because no cutover record
                // exists to make the failure undetermined.
                return afterHook(id, state, at, handoff.state(), answer.result(), null,
                        "quiesceFailed");
            }
            state.attempts = 0;
            var outcome = quiesceMachine(id, answer.leaseMillis(), at);
            if ("advanced".equals(outcome.outcome())) {
                // MOVE-336: a lease too short to carry the commit window aborts the handoff.
                return new StepOutcome.Advanced(HandoffId.of(id), HandoffState.CUTOVER,
                        handoff.state());
            }
            return new StepOutcome.Progressed(HandoffId.of(id), 0);
        }
        CutoverResult answer = hooks.commitCutover(context(handoff, state));
        return switch (answer) {
            case CutoverResult.Committed committed -> commit(id, handoff, state, at);
            case CutoverResult.AlreadyCommitted committed -> commit(id, handoff, state, at);
            // MOVE-351: a record naming another owner is a lost race, and the handoff compensates.
            case CutoverResult.Lost lost -> advanced(id, handoff.state(),
                    stepMachine(id, "abortRequested", at));
            // MOVE-231: what the commit did is not established, and no later call establishes it.
            case CutoverResult.Undetermined undetermined -> advanced(id, handoff.state(),
                    stepMachine(id, "commitUndetermined", at));
            case CutoverResult.Retryable retryable ->
                    retry(id, state, at, handoff.state(), "commitUndetermined");
            case CutoverResult.Permanent permanent ->
                    exhausted(id, state, at, handoff.state(), "commitUndetermined");
        };
    }

    private StepOutcome commit(String id, Handoff handoff, Driving state, long at) {
        state.attempts = 0;
        var outcome = stepMachine(id, "cutoverCommitted", at);
        if ("idle".equals(outcome.outcome())) {
            // MOVE-333: the commit deadline ran past the horizon, so the lease is spent and
            // MOVE-331 takes a fresh quiesce on the next step.
            // An idle outcome here is the spent lease where the machine says so, and a
            // concurrent abort otherwise, which is not this event.
            if ("quiesceExpired".equals(outcome.reason())) {
                report("quiesce_expired", Severity.ERROR, Map.of("handoff", id,
                        "shard", handoff.shard(),
                        "leaseMillis", Long.toString(handoff.leaseMillis()),
                        "marginMillis", Long.toString(policy.quiesceLeaseMarginMillis())));
            }
            synchronized (machineLock) {
                machine.clearQuiesce(id);
            }
            return new StepOutcome.Progressed(HandoffId.of(id), 0);
        }
        // MOVE-311: the window is the interval the shard was quiesced for, from the reading
        // `MOVE-332` took before the commit to the reading this step was driven with.
        report("cutover_committed", Severity.INFO, Map.of("shard", handoff.shard(),
                "source", handoff.source().asText(),
                "destination", handoff.destination().asText(),
                "windowMillis", Long.toString(
                        handoff.quiesceInstant().isPresent()
                                ? at - handoff.quiesceInstant().getAsLong() : 0L)));
        return advanced(id, handoff.state(), outcome);
    }

    private StepOutcome verify(String id, Handoff handoff, Driving state, long at) {
        if (!declaration.supportsVerify()) {
            // MOVE-181: where the hooks declare no verification, the committed record is what
            // cleanup may follow.
            return advanced(id, handoff.state(), stepMachine(id, "verifySuccess", at));
        }
        VerifyResult answer = hooks.verify(context(handoff, state));
        return switch (answer) {
            case VerifyResult.Matched matched -> {
                state.attempts = 0;
                yield advanced(id, handoff.state(), stepMachine(id, "verifySuccess", at));
            }
            // A mismatch is not retried: the same copies answer the same thing.
            case VerifyResult.Mismatched mismatched -> advanced(id, handoff.state(),
                    stepMachine(id, "verifyMismatch", at));
            case VerifyResult.Retryable retryable ->
                    retry(id, state, at, handoff.state(), "attemptsExhausted");
        };
    }

    private StepOutcome rollback(String id, Handoff handoff, Driving state, long at) {
        if (!declaration.supportsRollback()) {
            // Hooks that declare no rollback have nothing to release, so the handoff reaches
            // `aborted` with no hook called, as MOVE-411 leaves one aborted from `planned`.
            // MOVE-421 states rollback's duty without the carve-out MOVE-181 gives verification,
            // and `docs/design/adr/0085` records this reading of the two together.
            return advanced(id, handoff.state(), stepMachine(id, "rollbackSuccess", at));
        }
        return afterHook(id, state, at, handoff.state(),
                hooks.rollback(context(handoff, state)), "rollbackSuccess",
                "attemptsExhausted");
    }

    /** What one {@code HookResult} means for the handoff that produced it. */
    private StepOutcome afterHook(String id, Driving state, long at, HandoffState from,
                                  HookResult result, String successTrigger,
                                  String exhaustedTrigger) {
        return switch (result) {
            case HookResult.Success success -> {
                state.attempts = 0;
                yield successTrigger == null
                        ? new StepOutcome.Progressed(HandoffId.of(id), 0)
                        : advanced(id, from, stepMachine(id, successTrigger, at));
            }
            case HookResult.Deferred deferred -> {
                // MOVE-171: a deferral counts against no attempt budget.
                state.deferredUntil = at + deferred.retryAfterMillis();
                yield new StepOutcome.Deferred(HandoffId.of(id), deferred.retryAfterMillis());
            }
            case HookResult.Retryable retryable ->
                    retry(id, state, at, from, exhaustedTrigger);
            // MOVE-171: a permanent result is never retried.
            case HookResult.Permanent permanent -> exhausted(id, state, at, from,
                    exhaustedTrigger);
        };
    }

    /** {@code MOVE-171}: a retryable answer, retried up to the budget with the backoff. */
    private StepOutcome retry(String id, Driving state, long at, HandoffState from,
                              String exhaustedTrigger) {
        state.attempts++;
        if (state.attempts >= policy.maxAttemptsPerStep()) {
            return exhausted(id, state, at, from, exhaustedTrigger);
        }
        long backoff = policy.retryBackoffMillis(state.attempts);
        state.deferredUntil = at + backoff;
        return new StepOutcome.Deferred(HandoffId.of(id), (int) backoff);
    }

    /**
     * The attempt budget spent, under the trigger the hook that spent it answers to.
     *
     * <p>{@code MOVE-231} makes a commit nobody established undetermined, while a quiesce that
     * never succeeded leaves no record and compensates instead, so the caller names the trigger
     * rather than the state deciding it.
     */
    private StepOutcome exhausted(String id, Driving state, long at, HandoffState from,
                                  String trigger) {
        state.attempts = 0;
        return advanced(id, from, stepMachine(id, trigger, at));
    }

    /**
     * The public outcome one machine step produced.
     *
     * <p>The states come from the outcome rather than from the handoff, because the handoff has
     * already moved by the time this reads it. A machine that answered {@code idle} moved nothing,
     * and {@code MOVE-093} and {@code RATE-111} both make that an {@code Idle} step rather than
     * progress.
     */
    private StepOutcome advanced(String id,
            HandoffState from,
            com.codeheadsystems.sharder.core.internal.migrate.MigrationPlan.StepOutcome outcome) {
        HandoffId handoffId = HandoffId.of(id);
        return switch (outcome.outcome()) {
            case "settled" -> new StepOutcome.Settled(handoffId,
                    HandoffState.of(outcome.terminalState()));
            case "advanced" -> new StepOutcome.Advanced(handoffId,
                    HandoffState.of(outcome.fromState()), HandoffState.of(outcome.toState()));
            default -> new StepOutcome.Idle();
        };
    }

    /** One quiesce recorded, taken under the machine's lock. */
    private com.codeheadsystems.sharder.core.internal.migrate.MigrationPlan.QuiesceOutcome
            quiesceMachine(String id, int leaseMillis, long at) {
        synchronized (machineLock) {
            return machine.quiesce(id, leaseMillis, at);
        }
    }

    /** The context one hook call reads, under {@code MOVE-111}. */
    private HandoffContext context(Handoff handoff, Driving state) {
        return new HandoffContext(ShardId.of(handoff.shard()), ShardId.of(handoff.sourceShard()),
                source.topologyId(), handoff.fromEpoch(), handoff.toEpoch(), handoff.source(),
                handoff.destination(), state.attempts + 1, policy.stepDeadlineMillis());
    }

    @Override
    public RecoveryReport recover(MonotonicClock clock) {
        long at = clock.millis();
        List<HandoffId> resolved = new ArrayList<>();
        List<HandoffId> unresolved = new ArrayList<>();
        List<HandoffId> failed = new ArrayList<>();
        for (String id : machine.handoffs()) {
            if (machine.handoff(id).state().terminal()) {
                continue;
            }
            // The claim is taken per handoff, so a `step` advancing another one runs beside this
            // pass and no observation races the hook that is already in flight for this one.
            Driving state = claim(id);
            try {
                Handoff handoff = machine.handoff(id);
                if (handoff.state().terminal()) {
                    continue;
                }
                // CORE-063: the hook is called with no lock held.
                switch (hooks.observe(context(handoff, state))) {
                    case ObserveResult.Observed observed -> {
                        resume(id, handoff, observed.observation());
                        (machine.handoff(id).state() == HandoffState.FAILED ? failed : resolved)
                                .add(HandoffId.of(id));
                    }
                    // MOVE-112: the two answers are distinct, and neither establishes anything.
                    case ObserveResult.Unavailable unavailable -> unresolved.add(HandoffId.of(id));
                    case ObserveResult.Undetermined undetermined ->
                            unresolved.add(HandoffId.of(id));
                }
            } finally {
                state.claimed.set(false);
            }
        }
        int retryAfter = unresolved.isEmpty() ? 0 : policy.retryBackoffBaseMillis();
        return new RecoveryReport(resolved, unresolved, failed, retryAfter);
    }

    @Override
    public ReobserveOutcome reobserve(HandoffId id, MonotonicClock clock) {
        // MOVE-062: the supplied source is the plan's for the duration of this call, and an
        // observation is measured against nothing, so the reading is taken and not held.
        clock.millis();
        Driving state = claim(id.value());
        try {
            Handoff handoff = machine.handoff(id.value());
            if (!handoff.state().terminal()) {
                // MOVE-233 re-observes a terminal handoff; a live one is advanced by `step`.
                return new ReobserveOutcome.Refused("the handoff is not terminal");
            }
            ReobserveOutcome outcome = switch (hooks.observe(context(handoff, state))) {
                case ObserveResult.Observed observed -> {
                    resume(id.value(), handoff, observed.observation());
                    yield new ReobserveOutcome.Resumed(id,
                            machine.handoff(id.value()).state());
                }
                case ObserveResult.Unavailable unavailable -> new ReobserveOutcome.Unresolved();
                case ObserveResult.Undetermined undetermined -> new ReobserveOutcome.Unresolved();
            };
            // OBS-020: `answer` is what the hook established, and `resumedState` the state the
            // handoff is in afterwards, which is unchanged where nothing was established.
            report("reobserved", Severity.INFO, Map.of("handoff", id.value(),
                    "shard", handoff.shard(),
                    "answer", outcome instanceof ReobserveOutcome.Resumed ? "observed"
                            : outcome instanceof ReobserveOutcome.Unresolved ? "unresolved"
                            : "refused",
                    "resumedState", machine.handoff(id.value()).state().spelling()));
            return outcome;
        } finally {
            state.claimed.set(false);
        }
    }

    /** {@code MOVE-211}: the state an observation assigns, evaluated in the order stated. */
    private void resume(String id, Handoff handoff, Observation observation) {
        Optional<CutoverRecord> record = observation.cutoverRecord();
        boolean belongs = record.filter(held -> belongsTo(held, handoff)).isPresent();
        boolean foreign = record.isPresent() && !belongs;
        HandoffState state =
                com.codeheadsystems.sharder.core.internal.migrate.MigrationPlan.resumedState(
                        belongs, foreign, observation.sourceQuiesced(),
                        observation.destinationPrepared());
        synchronized (machineLock) {
            machine.resume(id, state, record.filter(held -> belongs).map(CutoverRecord::epoch));
        }
    }

    /**
     * {@code MOVE-102}: whether a cutover record belongs to this handoff.
     *
     * <p>The record's shard is the handoff's, its owner is the destination, and its epoch lies in
     * the plan's rebase interval, which is every epoch above the source epoch and at or below the
     * plan's target.
     */
    private boolean belongsTo(CutoverRecord record, Handoff handoff) {
        return record.shardId().asText().equals(handoff.shard())
                && record.owner().equals(handoff.destination())
                && record.epoch() > handoff.fromEpoch()
                && record.epoch() <= machine.targetEpoch();
    }

    @Override
    public RebaseReport rebase(TopologySnapshot to) {
        if (!(to instanceof DocumentSnapshot newer)) {
            throw new InvalidArgumentException("the snapshot was not produced by this library");
        }
        if (newer.epoch() <= machine.targetEpoch()) {
            // MOVE-095: a rebase onto an epoch the plan already targets moves nothing.
            throw new PlanRefusedException(PlanRefusedException.Cause.EPOCH_NOT_ADVANCING);
        }
        if (!newer.topologyId().equals(source.topologyId())) {
            throw new PlanRefusedException(PlanRefusedException.Cause.TOPOLOGY_MISMATCH);
        }
        if (OwnershipDelta.incomparable(source.document(), newer.document()).isPresent()) {
            throw new PlanRefusedException(PlanRefusedException.Cause.INCOMPARABLE_SHARDS);
        }
        // A rebase waits for every claim rather than interrupting a step that holds one, under
        // the concurrent use table of the Java binding. The identifiers are claimed in the order
        // the plan lists them, which is the order every other caller takes them in.
        List<Driving> claims = new ArrayList<>();
        try {
            machine.handoffs().forEach(id -> claims.add(claim(id)));
            // MOVE-096: one candidate ordering per handoff the rebase classifies, and no ordering
            // for a handoff it does not, which is every handoff at the cutover or beyond.
            PlacementEngine engine = newer.engine();
            Map<String, List<String>> replicaSets = new LinkedHashMap<>();
            for (String id : machine.handoffs()) {
                Handoff handoff = machine.handoff(id);
                if (!CLASSIFIED.contains(handoff.state())) {
                    continue;
                }
                replicaSets.computeIfAbsent(handoff.shard(), named -> {
                    List<String> identities = new ArrayList<>();
                    OwnershipDelta.replicas(engine, named)
                            .forEach(node -> identities.add(node.asText()));
                    return List.copyOf(identities);
                });
            }
            synchronized (machineLock) {
                var report = machine.rebase(newer.epoch(), replicaSets);
                target = newer;
                delta = com.codeheadsystems.sharder.topology.OwnershipDelta.between(source, newer);
                RebaseReport answer = new RebaseReport(report.fromEpoch(), report.toEpoch(),
                        ids(report.rebased()), ids(report.aborted()), ids(report.unchanged()));
                report("rebased", Severity.INFO, Map.of(
                        "fromEpoch", Long.toString(answer.fromEpoch()),
                        "toEpoch", Long.toString(answer.toEpoch()),
                        "rebased", Integer.toString(answer.rebased().size()),
                        "aborted", Integer.toString(answer.aborted().size()),
                        "unchanged", Integer.toString(answer.unchanged().size())));
                return answer;
            }
        } finally {
            claims.forEach(claim -> claim.claimed.set(false));
        }
    }

    @Override
    public void abort(HandoffId id, String reason) {
        Driving state = claim(id.value());
        try {
            abortClaimed(id.value(), state);
        } finally {
            state.claimed.set(false);
        }
    }

    @Override
    public void abortAll(String reason) {
        for (String id : machine.handoffs()) {
            Driving state = claim(id);
            try {
                if (ABORTABLE.contains(machine.handoff(id).state())) {
                    // MOVE-441 governs `abortAll` exactly as it governs one abort, so a handoff
                    // past the cutover is passed over rather than compensated.
                    abortClaimed(id, state);
                }
            } finally {
                state.claimed.set(false);
            }
        }
    }

    /**
     * One abort, with the claim already held.
     *
     * <p>{@code MOVE-441} admits an abort in {@code planned}, {@code preparing},
     * {@code transferring}, {@code catchingUp}, and {@code cutover} and nowhere else: past the
     * cutover the ownership has moved, and moving it back is a topology change rather than an
     * abort. {@code MOVE-431} adds the observation a {@code cutover} abort turns on, because
     * {@code MOVE-191} forbids compensating a handoff whose record exists.
     */
    private void abortClaimed(String id, Driving state) {
        Handoff handoff = machine.handoff(id);
        // ERR-010 names no condition for a refused abort and ERR-050's causes are closed over the
        // properties of a plan, so the refusal is reported as the caller error it is. The gap is
        // recorded in `docs/design/adr/0085`.
        if (!ABORTABLE.contains(handoff.state())) {
            throw new InvalidArgumentException("the abort is refused: a handoff in "
                    + handoff.state().spelling() + " admits none, under MOVE-441");
        }
        if (handoff.state() == HandoffState.CUTOVER) {
            // CORE-063: the hook is called with no lock held.
            ObserveResult answer = hooks.observe(context(handoff, state));
            if (!(answer instanceof ObserveResult.Observed observed)) {
                throw new InvalidArgumentException(
                        "the abort is refused: the durable state was not established");
            }
            if (observed.observation().cutoverRecord()
                    .filter(record -> belongsTo(record, handoff)).isPresent()) {
                throw new InvalidArgumentException(
                        "the abort is refused: the cutover record exists, under MOVE-191");
            }
        }
        synchronized (machineLock) {
            machine.abort(id);
        }
    }

    @Override
    public void onSnapshotInstalled(TopologySnapshot snapshot) {
        if (!(snapshot instanceof DocumentSnapshot installed)) {
            throw new InvalidArgumentException("the snapshot was not produced by this library");
        }
        com.codeheadsystems.sharder.core.internal.migrate.MigrationPlan.InstallOutcome outcome;
        synchronized (machineLock) {
            // MOVE-092: the mark records the installed snapshot and nothing else. No hook is
            // called, no preference list is evaluated, and no delta is computed.
            outcome = machine.onSnapshotInstalled(installed.topologyId(), installed.epoch(),
                    source.topologyId());
        }
        if (outcome.superseded()) {
            report("superseded", Severity.WARNING, Map.of(
                    "installedEpoch", Long.toString(installed.epoch()),
                    "abortedCount", Integer.toString(outcome.aborted().size()),
                    "finishingCount", Integer.toString(outcome.finishing().size())));
        }
        if (outcome.rebasePending() != null) {
            // MOVE-091: the plan is marked and the integrator decides whether to rebase it.
            report("rebase_pending", Severity.WARNING, Map.of(
                    "installedEpoch", Long.toString(installed.epoch()),
                    "targetEpoch", Long.toString(outcome.rebasePending())));
        }
    }

    private static List<HandoffId> ids(List<String> named) {
        List<HandoffId> ids = new ArrayList<>(named.size());
        named.forEach(id -> ids.add(HandoffId.of(id)));
        return List.copyOf(ids);
    }
}
