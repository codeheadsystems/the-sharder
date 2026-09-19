package com.codeheadsystems.sharder.core.internal.migrate;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.placement.ShardExtents;
import com.codeheadsystems.sharder.core.internal.route.OwnershipDelta;
import com.codeheadsystems.sharder.core.internal.route.PlacementEngine;
import com.codeheadsystems.sharder.migrate.HandoffState;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
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

    /** The states {@code MOVE-096} classifies, which are the states a rebase may move. */
    private static final Set<HandoffState> REBASABLE = EnumSet.of(
            HandoffState.PLANNED, HandoffState.PREPARING, HandoffState.TRANSFERRING,
            HandoffState.CATCHING_UP);

    private final Map<String, Handoff> handoffs = new LinkedHashMap<>();
    private int maxConcurrentHandoffs = 4;
    private int maxConcurrentPerSourceNode = 1;
    private int maxConcurrentPerDestinationNode = 1;
    private final long quiesceLeaseMarginMillis;
    private long commitDeadlineMillis = 30000L;
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

    /** The same, carrying the parent and the kind a lineage gives it under {@code LIN-041}. */
    public static Handoff handoffOf(String id, String shard, String sourceShard, Handoff.Kind kind,
                                    NodeId source, NodeId destination, long fromEpoch,
                                    long toEpoch) {
        return new Handoff(id, shard, sourceShard, kind, source, destination, fromEpoch, toEpoch);
    }

    /**
     * The plan between two snapshots, derived from the lineage under {@code LIN-041}.
     *
     * <p>One handoff is admitted for each shard of the later snapshot, each shard its extent draws
     * from, and each destination that does not already hold that parent's contents. Where the two
     * snapshots enumerate the same shards the lineage is the identity of {@code LIN-007} and every
     * shard is its own parent, which is the ownership delta's answer and the case every topology
     * the suite carried before the lineage falls into.
     *
     * <p>Building the plan over the delta alone is what {@code LIN-044} forbids. A shard that
     * absorbed a folded extent keeps its replica set, so the delta reports no entry for it, and a
     * destination that holds one parent but not the other would receive nothing. A shard divided
     * out of a parent has no entry in the earlier snapshot at all, so the source would fall back to
     * the destination and the plan would tell a node to copy from itself.
     *
     * <p>Plan construction is a pure function of the two snapshots and the policy, which is what
     * lets a restarted coordinator rebuild the same plan under {@code MOVE-221}.
     */
    public static MigrationPlan of(PlacementEngine from, PlacementEngine to,
                                   long quiesceLeaseMarginMillis) {
        MigrationPlan plan = new MigrationPlan(quiesceLeaseMarginMillis, to.document().epoch());
        Map<String, List<String>> parents = ShardExtents.parents(from, to);
        Map<String, ShardExtents.Lineage> classes = new LinkedHashMap<>();
        for (ShardExtents.Entry entry : ShardExtents.classify(from, to, OwnershipDelta::replicas)) {
            classes.put(entry.shard(), entry.lineage());
        }
        for (String shard : to.placement().shards()) {
            List<NodeId> destinations = OwnershipDelta.replicas(to, shard);
            List<String> drawn = parents.getOrDefault(shard, List.of(shard));
            ShardExtents.Lineage lineage = classes.get(shard);

            // LIN-057: a division is sequenced before every handoff that draws from the divided
            // parent, and a fold after every handoff that draws into the folded shard.
            if (lineage == ShardExtents.Lineage.DIVIDED) {
                String parent = drawn.get(0);
                for (NodeId node : destinations) {
                    if (OwnershipDelta.replicas(from, parent).contains(node)) {
                        admit(plan, shard, parent, Handoff.Kind.DIVIDE, node, node, from, to);
                    }
                }
            }
            for (String parent : drawn) {
                List<NodeId> held = OwnershipDelta.replicas(from, parent);
                if (held.isEmpty()) {
                    // LIN-043: a shard with no parent has no contents to move.
                    continue;
                }
                List<NodeId> needing = new ArrayList<>(destinations);
                needing.removeAll(held);
                List<NodeId> departing = new ArrayList<>(held);
                departing.removeAll(destinations);
                for (int entry = 0; entry < needing.size(); entry++) {
                    // LIN-045: a replica giving the shard up is drained in preference to one
                    // keeping it, so a plan rebuilt from the same snapshots names the same source.
                    NodeId source = entry < departing.size() ? departing.get(entry) : held.get(0);
                    admit(plan, shard, parent, Handoff.Kind.HANDOFF, source, needing.get(entry),
                            from, to);
                }
            }
            if (lineage == ShardExtents.Lineage.MERGED) {
                for (NodeId node : destinations) {
                    boolean holdsAParent = drawn.stream()
                            .anyMatch(parent -> OwnershipDelta.replicas(from, parent).contains(node));
                    if (holdsAParent) {
                        admit(plan, shard, shard, Handoff.Kind.COMBINE, node, node, from, to);
                    }
                }
            }
        }
        return plan;
    }

    /** One entry of a plan, named so that a rebuilt plan names it identically under MOVE-221. */
    private static void admit(MigrationPlan plan, String shard, String sourceShard,
                              Handoff.Kind kind, NodeId source, NodeId destination,
                              PlacementEngine from, PlacementEngine to) {
        String prefix = kind.local() ? "l-" : "h-";
        // The suffix counts the entries already admitted for this shard whatever their kind, so a
        // local step and the handoff beside it never collide and the numbering is positional.
        long already = plan.handoffs.values().stream()
                .filter(existing -> existing.shard().equals(shard))
                .count();
        String id = already == 0 ? prefix + shard : prefix + shard + "-" + already;
        plan.handoffs.put(id, new Handoff(id, shard, sourceShard, kind, source, destination,
                from.document().epoch(), to.document().epoch()));
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

    /**
     * The commit deadline of {@code CFG-050}, which {@code MOVE-333} and {@code MOVE-336} read.
     *
     * <p>It is a setting rather than a constant: a deployment whose commit is a single write to a
     * store it already holds open sets it far below the default, and the lease a source grants is
     * compared against whatever it holds.
     */
    public void commitDeadlineMillis(long millis) {
        this.commitDeadlineMillis = millis;
    }

    /**
     * Forgets a quiesce, which {@code MOVE-331} takes a fresh one after.
     *
     * <p>{@code MOVE-333} stops a commit whose deadline runs past the horizon the lease leaves.
     * The handoff stays in {@code cutover} and the next step quiesces again, so the instant the
     * expired lease was measured from is cleared here rather than compared against a second time.
     */
    public void clearQuiesce(String id) {
        handoff(id).clearQuiesce();
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
        if (rebasePending != null && ("admittedByRatePolicy".equals(trigger)
                || "residueAtOrBelowThreshold".equals(trigger))) {
            // MOVE-093: while a rebase is pending no handoff leaves `planned` and none reaches
            // `cutover`. Every other transition stays available, so work already begun finishes,
            // and `abort`, `abortAll`, `recover`, and `reobserve` stay admissible.
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
                    handoff(id).kind().local() ? HandoffState.DIVIDING : HandoffState.PREPARING);
            // LIN-051: the same admission, named for the state it reaches, which a scenario
            // drives by trigger rather than by reading the handoff's kind.
            case "admittedByRatePolicyLocal" -> require(from, HandoffState.PLANNED,
                    HandoffState.DIVIDING);
            // LIN-051: a local step moves nothing between nodes, so it runs the short sequence.
            case "divideSuccess", "combineSuccess" -> require(from, HandoffState.DIVIDING,
                    HandoffState.COMPLETE);
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
                // MOVE-421: a local step is undone by the inverse hook, so it aborts like the
                // three states that already do, under LIN-056.
                case PREPARING, TRANSFERRING, CATCHING_UP, DIVIDING -> HandoffState.ABORTING;
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
            // MOVE-011: a copy that matches neither the parent's extent nor the child's.
            case "dividePermanent" -> "undivided";
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
        List<String> unchanged = new ArrayList<>();
        long from = targetEpoch;
        for (Handoff handoff : handoffs.values()) {
            // MOVE-099 reports a terminal handoff as unchanged as well: it is in no group
            // MOVE-096 classifies, and an integrator reconciling the three lists against the
            // plan's handoffs would otherwise find it in none of them.
            if (handoff.state().terminal()) {
                unchanged.add(handoff.id());
                continue;
            }
            // MOVE-096 classifies `planned`, `preparing`, `transferring`, and `catchingUp` and no
            // other state. MOVE-099 reports the rest as unchanged: a handoff that entered the
            // cutover keeps the target epoch it held at that transition and runs to a terminal
            // state under it, because ownership has moved or is moving and compensation after a
            // cutover is a new plan rather than a rebase.
            if (!REBASABLE.contains(handoff.state())) {
                unchanged.add(handoff.id());
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
        // MOVE-094: the mark stands where it names an epoch this rebase did not reach.
        if (rebasePending != null && rebasePending <= toEpochValue) {
            rebasePending = null;
        }
        return new RebaseReport(from, toEpochValue, List.copyOf(rebased), List.copyOf(aborted),
                List.copyOf(unchanged));
    }
}
