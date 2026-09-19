package com.codeheadsystems.sharder.core.internal.migrate;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.route.OwnershipDelta;
import com.codeheadsystems.sharder.core.internal.route.PlacementEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A plan of handoffs between two snapshots, and the passive machine that drives them.
 *
 * <p>The coordinator starts no thread, schedules no work, and calls no movement hook except from a
 * call the integrator makes, under {@code MOVE-051}. Every transition below is one the integrator
 * drives, and the coordinator's own state is never the authority on what has happened: the
 * integrator's durable state, read through {@code observe}, is, under {@code MOVE-201}.
 */
public final class MigrationPlan {

    /**
     * What one step of a handoff did.
     *
     * <p>{@code advanced} moved it, {@code settled} moved it into a terminal state, {@code idle}
     * did nothing because a precondition of the step did not hold, and {@code noop} did nothing
     * because the step had already been taken.
     */
    public record StepOutcome(String outcome, String id, String fromState, String toState,
                              String terminalState, String failureKind, String reason,
                              Long commitHorizon) {

        StepOutcome(String outcome, String id, String fromState, String toState,
                    String terminalState, String failureKind) {
            this(outcome, id, fromState, toState, terminalState, failureKind, null, null);
        }
    }

    /** What a quiesce established: the instant, the lease, and the horizon a commit must beat. */
    public record QuiesceOutcome(String outcome, long quiesceInstant, long leaseMillis,
                                 long commitHorizon, String state, String reason) {

        QuiesceOutcome(String outcome, long quiesceInstant, long leaseMillis, long commitHorizon,
                       String state) {
            this(outcome, quiesceInstant, leaseMillis, commitHorizon, state, null);
        }
    }

    /** What the installation of a snapshot did to the plan, under {@code MOVE-091}. */
    public record InstallOutcome(boolean superseded, List<String> aborted, List<String> finishing,
                                 Long rebasePending) {
    }

    /** What a rebase did to each handoff, under {@code MOVE-096}. */
    public record RebaseReport(long fromEpoch, long toEpoch, List<String> rebased,
                               List<String> aborted, List<String> unchanged) {
    }

    private final Map<String, Handoff> handoffs = new LinkedHashMap<>();
    private int maxConcurrentHandoffs = 4;
    private int maxConcurrentPerSourceNode = 1;
    private int maxConcurrentPerDestinationNode = 1;
    private final long quiesceLeaseMarginMillis;
    private final long commitDeadlineMillis = 30000L;
    private Long rebasePending;
    private long targetEpoch;

    private MigrationPlan(long quiesceLeaseMarginMillis, long targetEpoch) {
        this.quiesceLeaseMarginMillis = quiesceLeaseMarginMillis;
        this.targetEpoch = targetEpoch;
    }

    /** A plan over handoffs a caller names, for a plan no snapshot pair produces. */
    public static MigrationPlan of(String topologyId, long fromEpoch, long toEpoch,
                                   List<Handoff> named, long quiesceLeaseMarginMillis) {
        MigrationPlan plan = new MigrationPlan(quiesceLeaseMarginMillis, toEpoch);
        named.forEach(handoff -> plan.handoffs.put(handoff.id(), handoff));
        return plan;
    }

    /** One handoff, for a plan built from a list rather than from a delta. */
    public static Handoff handoffOf(String id, String shard, NodeId source, NodeId destination,
                                    long fromEpoch, long toEpoch) {
        return new Handoff(id, shard, source, destination, fromEpoch, toEpoch);
    }

    /**
     * The plan between two snapshots: one handoff per shard that gained a node, under the
     * ownership delta of {@code TOPO-211}.
     *
     * <p>Plan construction is a pure function of the two snapshots and the policy, which is what
     * lets a restarted coordinator rebuild the same plan under {@code MOVE-221}.
     */
    public static MigrationPlan of(PlacementEngine from, PlacementEngine to,
                                   long quiesceLeaseMarginMillis) {
        MigrationPlan plan = new MigrationPlan(quiesceLeaseMarginMillis, to.document().epoch());
        for (OwnershipDelta.ShardChange change : OwnershipDelta.between(from, to)) {
            // A shard that gained a node and lost one is a move from that source to that
            // destination; the pairing is by position, which the delta reports in identity order.
            for (int entry = 0; entry < change.gained().size(); entry++) {
                NodeId destination = change.gained().get(entry);
                NodeId source = entry < change.lost().size()
                        ? change.lost().get(entry)
                        : change.before().isEmpty() ? destination : change.before().get(0);
                // A handoff is named after the shard it moves, so a rebuilt plan names the same
                // handoffs, which MOVE-221 rests on.
                String id = "h-" + change.shard();
                while (plan.handoffs.containsKey(id)) {
                    id = id + "-" + entry;
                }
                plan.handoffs.put(id, new Handoff(id, change.shard(), source, destination,
                        from.document().epoch(), to.document().epoch()));
            }
        }
        return plan;
    }

    /** The handoffs of the plan, in the order it admitted them. */
    public List<String> handoffs() {
        return List.copyOf(handoffs.keySet());
    }

    /** One handoff of the plan. */
    public Handoff handoff(String id) {
        Handoff held = handoffs.get(id);
        if (held == null) {
            throw new IllegalArgumentException("the plan holds no handoff " + id);
        }
        return held;
    }

    /** The epoch the plan targets. */
    public long targetEpoch() {
        return targetEpoch;
    }

    /** The concurrency bounds of {@code CFG-050}, which the rate policy admits handoffs under. */
    public void policy(int concurrent, int perSource, int perDestination) {
        this.maxConcurrentHandoffs = concurrent;
        this.maxConcurrentPerSourceNode = perSource;
        this.maxConcurrentPerDestinationNode = perDestination;
    }

    /** Returns one handoff to {@code planned}, which a scenario driving it twice needs. */
    public void reset(String id) {
        handoff(id).moveTo(HandoffState.PLANNED);
    }

    /** The count of handoffs in each state, under {@code MOVE-061}. */
    public Map<String, Integer> summary() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Handoff handoff : handoffs.values()) {
            counts.merge(handoff.state().spelling(), 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    /**
     * One step of one handoff, driven by the trigger the integrator's hook answer produced.
     *
     * <p>{@code MOVE-021} permits exactly the transitions below and no others, so a trigger that
     * names no transition from the state in force is refused rather than applied.
     */
    public StepOutcome step(String id, String trigger, long at) {
        return step(id, trigger, at, null);
    }

    /** One step under a pressure level the integrator's gauge reported. */
    public StepOutcome step(String id, String trigger, long at, String pressure) {
        Handoff handoff = handoff(id);
        HandoffState from = handoff.state();
        if (from.terminal()) {
            // MOVE-031: nothing transitions out of a terminal state except a re-observation the
            // integrator calls for one named handoff.
            return new StepOutcome("refused", id, from.spelling(), from.spelling(),
                    from.spelling(), handoff.failureKind().orElse(null), "terminal", null);
        }
        if (pressure != null && rebasePending == null) {
            // RATE-081 and RATE-091: neither level admits a handoff out of `planned`, and hard
            // pressure withholds `transfer` and `catchUp` and no other hook, so a handoff already
            // preparing still advances on a prepare that succeeded.
            boolean withheld = "hard".equals(pressure)
                    && (from == HandoffState.TRANSFERRING || from == HandoffState.CATCHING_UP);
            boolean refused = withheld || from == HandoffState.PLANNED;
            if (refused) {
                return new StepOutcome("idle", id, from.spelling(), from.spelling(), null, null,
                        "pressure:" + pressure, null);
            }
        }
        if ("admittedByRatePolicy".equals(trigger) && from == HandoffState.PLANNED
                && rebasePending == null) {
            String bound = concurrencyBound(handoff);
            if (bound != null) {
                return new StepOutcome("idle", id, from.spelling(), from.spelling(), null, null,
                        bound, null);
            }
        }
        if (rebasePending != null && !"abortRequested".equals(trigger)) {
            // MOVE-093: a rebase-pending plan advances no handoff, because the plan in force is
            // about to be rebuilt against a newer snapshot.
            return new StepOutcome("idle", id, from.spelling(), from.spelling(), null, null,
                    "rebasePending", null);
        }
        if ("cutoverCommitted".equals(trigger) && from == HandoffState.CUTOVER
                && handoff.quiesceInstant().isPresent()) {
            // MOVE-333: where the reading plus the commit deadline is above the commit horizon,
            // commitCutover is not called at all and MOVE-331 takes a fresh quiesce.
            long horizon = handoff.quiesceInstant().orElse(0)
                    + handoff.leaseMillis() - quiesceLeaseMarginMillis;
            if (at + commitDeadlineMillis > horizon) {
                return new StepOutcome("idle", id, from.spelling(), from.spelling(), null, null,
                        "quiesceExpired", horizon);
            }
        }
        HandoffState to = switch (trigger) {
            case "admittedByRatePolicy" -> require(from, HandoffState.PLANNED,
                    HandoffState.PREPARING);
            case "prepareSuccess" -> require(from, HandoffState.PREPARING,
                    HandoffState.TRANSFERRING);
            case "noBulkRemaining" -> require(from, HandoffState.TRANSFERRING,
                    HandoffState.CATCHING_UP);
            case "residueAtOrBelowThreshold" -> require(from, HandoffState.CATCHING_UP,
                    HandoffState.CUTOVER);
            case "residueAboveReTransferThreshold" -> require(from, HandoffState.CATCHING_UP,
                    HandoffState.TRANSFERRING);
            case "cutoverCommitted" -> require(from, HandoffState.CUTOVER,
                    HandoffState.VERIFYING);
            case "verifySuccess" -> require(from, HandoffState.VERIFYING, HandoffState.CLEANUP);
            case "cleanupSuccess", "cleanupWaived" -> require(from, HandoffState.CLEANUP,
                    HandoffState.COMPLETE);
            case "rollbackSuccess" -> require(from, HandoffState.ABORTING, HandoffState.ABORTED);
            case "quiesceFailed" -> require(from, HandoffState.CUTOVER, HandoffState.ABORTING);
            case "abortRequested", "abort" -> from == HandoffState.PLANNED
                    ? HandoffState.ABORTED : HandoffState.ABORTING;
            // MOVE-021: attempts exhausted short of the cutover is an abort rather than a failure.
            case "attemptsExhausted" -> switch (from) {
                case PREPARING, TRANSFERRING, CATCHING_UP -> HandoffState.ABORTING;
                default -> null;
            };
            case "quiesceExpired" -> require(from, HandoffState.CUTOVER, HandoffState.ABORTING);
            case "planSuperseded", "rebasedPast" -> from == HandoffState.PLANNED
                    ? HandoffState.ABORTED : HandoffState.ABORTING;
            default -> null;
        };
        if (to == null) {
            return failure(handoff, from, trigger);
        }
        handoff.moveTo(to);
        return to.terminal()
                ? new StepOutcome("settled", id, from.spelling(), to.spelling(), to.spelling(),
                        handoff.failureKind().orElse(null))
                : new StepOutcome("advanced", id, from.spelling(), to.spelling(), null, null);
    }

    /** The triggers ending a handoff in {@code failed}, each naming a kind of
     * {@code MOVE-011}. */
    private StepOutcome failure(Handoff handoff, HandoffState from, String trigger) {
        String kind = switch (trigger) {
            case "verifyMismatch" -> "unverified";
            case "commitUndetermined" -> "undetermined";
            // MOVE-011: the kind names which copy the failure leaves at risk, so an exhausted
            // attempt budget means one thing in verifying, another in cleanup, and another while
            // compensation runs.
            case "attemptsExhausted" -> switch (from) {
                case VERIFYING -> "unverified";
                case CLEANUP -> "residue";
                case ABORTING -> "rollbackFailed";
                default -> throw new IllegalArgumentException(
                        "no failure from " + from.spelling() + " under " + trigger);
            };
            default -> throw new IllegalArgumentException(
                    "no transition from " + from.spelling() + " under " + trigger);
        };
        handoff.fail(kind);
        return new StepOutcome("settled", handoff.id(), from.spelling(),
                HandoffState.FAILED.spelling(), HandoffState.FAILED.spelling(), kind);
    }

    /** The bound a handoff's admission would exceed, or nothing where every bound admits it. */
    private String concurrencyBound(Handoff candidate) {
        int inFlight = 0;
        int fromSource = 0;
        int toDestination = 0;
        for (Handoff handoff : handoffs.values()) {
            if (handoff.state() == HandoffState.PLANNED || handoff.state().terminal()) {
                continue;
            }
            inFlight++;
            if (handoff.source().equals(candidate.source())) {
                fromSource++;
            }
            if (handoff.destination().equals(candidate.destination())) {
                toDestination++;
            }
        }
        boolean refused = inFlight >= maxConcurrentHandoffs
                || fromSource >= maxConcurrentPerSourceNode
                || toDestination >= maxConcurrentPerDestinationNode;
        return refused ? "concurrencyBound" : null;
    }

    private static HandoffState require(HandoffState from, HandoffState expected,
                                        HandoffState to) {
        if (from != expected) {
            throw new IllegalStateException(
                    "no transition from " + from.spelling() + " to " + to.spelling());
        }
        return to;
    }

    /**
     * A successful quiesce: the instant, the lease the source granted, and the horizon a commit
     * must beat.
     *
     * <p>The horizon is the lease less the margin of {@code MOVE-332}, because the source enforces
     * the lease on its own clock and the coordinator evaluates it on the integrator's.
     */
    public QuiesceOutcome quiesce(String id, long leaseMillis, long at) {
        Handoff handoff = handoff(id);
        if (leaseMillis <= quiesceLeaseMarginMillis + commitDeadlineMillis) {
            // MOVE-336: the lease carries no commit window at all, so the quiesce failed and
            // MOVE-021 takes the handoff to aborting.
            handoff.moveTo(HandoffState.ABORTING);
            return new QuiesceOutcome("advanced", at, leaseMillis,
                    at + leaseMillis - quiesceLeaseMarginMillis, handoff.state().spelling(),
                    "leaseBelowCommitWindow");
        }
        handoff.quiesced(at, leaseMillis);
        return new QuiesceOutcome("quiesced", at, leaseMillis,
                at + leaseMillis - quiesceLeaseMarginMillis, handoff.state().spelling());
    }

    /**
     * An abort the integrator requested, under {@code MOVE-021}, which {@code MOVE-491} makes
     * idempotent: a second abort of a handoff already compensating answers {@code noop}.
     */
    public StepOutcome abort(String id) {
        Handoff handoff = handoff(id);
        if (handoff.state() == HandoffState.ABORTING || handoff.state() == HandoffState.ABORTED) {
            return new StepOutcome("noop", id, handoff.state().spelling(),
                    handoff.state().spelling(), null, null);
        }
        return step(id, "abortRequested", 0);
    }

    /**
     * The state {@code MOVE-211} assigns from an observation, evaluating the rows in order.
     *
     * <p>A quiesced source was a prepared destination first, so the third and fourth rows hold
     * together whenever the source quiesced, and the order is what resumes such a handoff at
     * {@code cutover} rather than at {@code transferring}.
     */
    public static HandoffState resumedState(boolean recordBelongs, boolean recordForeign,
                                            boolean sourceQuiesced, boolean destinationPrepared) {
        if (recordBelongs) {
            return HandoffState.VERIFYING;
        }
        if (recordForeign) {
            return HandoffState.ABORTING;
        }
        if (sourceQuiesced) {
            return HandoffState.CUTOVER;
        }
        return destinationPrepared ? HandoffState.TRANSFERRING : HandoffState.PREPARING;
    }

    /** Resumes one handoff at a state an observation assigned. */
    public void resume(String id, HandoffState state, Optional<Long> recordEpoch) {
        Handoff handoff = handoff(id);
        handoff.moveTo(state);
        // MOVE-212: a handoff resumed at verifying takes the epoch of the observed record; one
        // resumed anywhere else keeps the plan's target epoch.
        recordEpoch.filter(epoch -> state == HandoffState.VERIFYING)
                .ifPresent(handoff::targetEpoch);
    }

    /**
     * The installation of a snapshot, under {@code MOVE-091}.
     *
     * <p>An epoch at or below the plan's target changes nothing. A comparable epoch above it marks
     * the plan rebase pending and aborts nothing, because the plan is still the one in force until
     * a rebase moves it.
     */
    public InstallOutcome onSnapshotInstalled(String topologyId, long epoch, String planTopology) {
        if (!topologyId.equals(planTopology)) {
            List<String> aborted = new ArrayList<>();
            List<String> finishing = new ArrayList<>();
            for (Handoff handoff : handoffs.values()) {
                if (handoff.state().terminal()) {
                    continue;
                }
                if (handoff.state().ordinal() >= HandoffState.CUTOVER.ordinal()
                        && handoff.state() != HandoffState.ABORTING) {
                    // MOVE-094: a handoff at the cutover or beyond runs to a terminal state,
                    // because ownership has moved or is moving and abandoning it would leave the
                    // shard between two owners.
                    finishing.add(handoff.id());
                    continue;
                }
                handoff.moveTo(handoff.state() == HandoffState.PLANNED
                        ? HandoffState.ABORTED : HandoffState.ABORTING);
                aborted.add(handoff.id());
            }
            return new InstallOutcome(true, List.copyOf(aborted), List.copyOf(finishing), null);
        }
        if (epoch <= targetEpoch) {
            return new InstallOutcome(false, List.of(), List.of(), null);
        }
        rebasePending = epoch;
        return new InstallOutcome(false, List.of(), List.of(), epoch);
    }

    /**
     * A rebase onto a newer snapshot, one handoff at a time, under {@code MOVE-096}.
     *
     * <p>A handoff whose shard, source, and destination still hold under the newer snapshot
     * continues from the state it is in; the rest are aborted. A rebase moves no ownership and
     * commits no cutover record.
     */
    public RebaseReport rebase(long toEpochValue, Map<String, List<String>> replicaSets) {
        List<String> rebased = new ArrayList<>();
        List<String> aborted = new ArrayList<>();
        long from = targetEpoch;
        for (Handoff handoff : handoffs.values()) {
            if (handoff.state().terminal()) {
                continue;
            }
            List<String> replicas = replicaSets.get(handoff.shard());
            boolean holds = replicas != null
                    && replicas.contains(handoff.destination().asText())
                    && !replicas.contains(handoff.source().asText());
            if (holds) {
                handoff.targetEpoch(toEpochValue);
                rebased.add(handoff.id());
            } else {
                handoff.moveTo(handoff.state() == HandoffState.PLANNED
                        ? HandoffState.ABORTED : HandoffState.ABORTING);
                aborted.add(handoff.id());
            }
        }
        targetEpoch = toEpochValue;
        rebasePending = null;
        return new RebaseReport(from, toEpochValue, List.copyOf(rebased), List.copyOf(aborted),
                List.of());
    }
}
