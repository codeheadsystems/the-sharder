package com.codeheadsystems.sharder.core.internal.health;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.config.HealthSettings;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.Node;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.snapshot.DocumentSnapshot;
import com.codeheadsystems.sharder.health.HealthSignal;
import com.codeheadsystems.sharder.health.HealthState;
import com.codeheadsystems.sharder.health.HealthView;
import com.codeheadsystems.sharder.health.HintObserver;
import com.codeheadsystems.sharder.topology.TopologySnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * The built-in health state machine of {@code HEALTH-020} through {@code HEALTH-055}.
 *
 * <p>Health state is runtime state a caller holds. It is derived from the signals that caller
 * observes, is never agreed between callers, and never reaches a preference list: the filter of
 * {@code FAIL-002} removes entries and reorders nothing, so two callers with different views
 * attempt different nodes and still agree on who owns the shard.
 *
 * <p>Every computation here is unsigned integer arithmetic, and integer division truncates towards
 * zero, under {@code HEALTH-021}.
 */
public final class SlidingWindowHealthView implements HealthView {

    /**
     * One node's health entry, which survives an epoch change under {@code HEALTH-006}.
     *
     * <p>Every field but two is written under the view's lock. {@code state} is volatile because
     * {@code stateOf} reads it without that lock, so a routing call never blocks on the health
     * view even though {@code CORE-064} would permit it to. {@code probeCounter} is atomic because
     * {@code admitProbe} increments it without the lock, under {@code HEALTH-051}.
     */
    private static final class Entry {
        private final SlidingWindow window;
        private volatile HealthState state = HealthState.UNKNOWN;
        private int consecutiveFailures;
        private int ejectionCount;
        private final AtomicInteger probeCounter = new AtomicInteger();
        private long newestSignal = Long.MIN_VALUE;
        private long unavailableSince;
        private long probationSince;
        private long availableSince = Long.MIN_VALUE;

        private Entry(HealthSettings settings) {
            this.window = new SlidingWindow(settings);
        }
    }

    /** One transition, which {@code HEALTH-048} requires an event for. */
    public record Transition(NodeId node, HealthState from, HealthState to, String trigger) {
    }

    private final HealthSettings settings;
    private final HintObserver observer;
    private final Queue<Transition> pending = new ConcurrentLinkedQueue<>();
    private final Map<NodeId, Entry> entries = new ConcurrentHashMap<>();
    private final List<Transition> transitions = new ArrayList<>();
    private Set<NodeId> placementSet = Set.of();
    private boolean placementSetKnown;

    /** A view under the given parameters, holding no entry. */
    public SlidingWindowHealthView(HealthSettings settings) {
        this(settings, null);
    }

    /** A view that reports every transition to an observer as well. */
    public SlidingWindowHealthView(HealthSettings settings, HintObserver observer) {
        this.settings = settings;
        this.observer = observer;
    }

    /** The parameters in force. */
    public HealthSettings settings() {
        return settings;
    }

    @Override
    public void report(HealthSignal signal) {
        report(signal.node(), signal.outcome().spelling(), signal.observed());
    }

    /**
     * The placement set outlier ejection compares against, under {@code HEALTH-030}.
     *
     * <p>A snapshot this library did not produce carries no parsed document, so the view keeps the
     * placement set it holds and runs no outlier ejection, which is what {@code HEALTH-016} makes
     * of a view that ignores the call.
     */
    @Override
    public void onSnapshotInstalled(TopologySnapshot snapshot) {
        if (snapshot instanceof DocumentSnapshot document) {
            onSnapshotInstalled(document.document());
        }
    }

    /** The state of a node, which is {@code unknown} where no signal has been ingested. */
    @Override
    public HealthState stateOf(NodeId node) {
        Entry entry = entries.get(node);
        return entry == null ? HealthState.UNKNOWN : entry.state;
    }

    /** Whether the health filter leaves an entry attemptable, under {@code HEALTH-005}. */
    public boolean attemptable(NodeId node) {
        return stateOf(node).attemptable();
    }

    /** The transitions emitted since the last call, which an event sink would have carried. */
    public synchronized List<Transition> drainTransitions() {
        List<Transition> drained = List.copyOf(transitions);
        transitions.clear();
        return drained;
    }

    /**
     * {@code HEALTH-016}: the placement set the view compares against and the size the ejection
     * ceiling bounds.
     *
     * <p>A view that is never given one runs no outlier ejection and refuses no transition, which
     * is why the call is the only way it learns either.
     */
    public void onSnapshotInstalled(TopologyDocument document) {
        synchronized (this) {
            install(document);
        }
        notifyObserver();
    }

    private void install(TopologyDocument document) {
        Set<NodeId> installed = new LinkedHashSet<>();
        for (Node node : document.placementSet()) {
            installed.add(node.id());
        }
        if (settings.resetOnPlacementReentry()) {
            // HEALTH-007: an identity that re-enters the placement set discards its entry, so it
            // holds `unknown` with no window, no counters, and no probe counter.
            for (NodeId node : installed) {
                if (placementSetKnown && !placementSet.contains(node)) {
                    entries.remove(node);
                }
            }
        }
        placementSet = Set.copyOf(installed);
        placementSetKnown = true;
    }

    /**
     * {@code HEALTH-010}: one observation.
     *
     * <p>A signal is accepted for any identity, including one the snapshot in force does not hold,
     * and a signal older than the newest already ingested for that node is ignored under
     * {@code HEALTH-012}. Timers are evaluated first, under {@code HEALTH-047}.
     */
    public void report(NodeId node, String outcome, long observed) {
        synchronized (this) {
            ingest(node, outcome, observed);
        }
        notifyObserver();
    }

    private void ingest(NodeId node, String outcome, long observed) {
        Entry entry = entries.computeIfAbsent(node, ignored -> new Entry(settings));
        if (observed < entry.newestSignal) {
            return;
        }
        entry.newestSignal = observed;
        // The timers run under the lock this ingestion already holds, so the observer is notified
        // once, by the public call, rather than from inside the lock.
        timers(observed);

        // HEALTH-022: `cancelled` counts as neither a success nor a failure.
        if ("cancelled".equals(outcome)) {
            return;
        }
        boolean success = "success".equals(outcome);
        entry.window.record(observed, success);
        if (success) {
            entry.consecutiveFailures = 0;
        } else {
            entry.consecutiveFailures++;
        }
        evaluate(node, entry, observed, success);
    }

    /** {@code HEALTH-015}: timers evaluated for every node, in ascending node identity. */
    @Override
    public void advance(long now) {
        synchronized (this) {
            timers(now);
        }
        notifyObserver();
    }

    private void timers(long now) {
        List<NodeId> nodes = new ArrayList<>(entries.keySet());
        nodes.sort(NodeId::compareTo);
        for (NodeId node : nodes) {
            timers(node, entries.get(node), now);
        }
    }

    /**
     * {@code HEALTH-051}: the probe counter, incremented by each call, admitting the probe exactly
     * where the incremented value modulo the divisor is 1.
     *
     * <p>The increment takes no lock, under the concurrent use table of the Java binding: the
     * attempt walk calls this under {@code HEALTH-017}, and a walk that blocked on the view would
     * put the health lock on the path of every attempt.
     */
    @Override
    public boolean admitProbe(NodeId node) {
        Entry entry = entries.get(node);
        if (entry == null || entry.state != HealthState.PROBATION) {
            return true;
        }
        return entry.probeCounter.incrementAndGet() % settings.probationDivisor() == 1;
    }

    /** The comparison set of {@code HEALTH-030}, in ascending node identity. */
    public synchronized List<NodeId> comparisonSet(long now) {
        List<NodeId> set = new ArrayList<>();
        for (Map.Entry<NodeId, Entry> held : entries.entrySet()) {
            // A health entry the placement set does not hold neither enters the set nor moves the
            // median, whatever signals have been ingested for it.
            if (placementSet.contains(held.getKey())
                    && held.getValue().window.total(now) >= settings.minimumSamples()) {
                set.add(held.getKey());
            }
        }
        set.sort(NodeId::compareTo);
        return List.copyOf(set);
    }

    /** The peer median of {@code HEALTH-031}, the lower of two central values where even. */
    public synchronized Optional<Integer> peerMedian(long now) {
        List<Integer> percents = new ArrayList<>();
        for (NodeId node : comparisonSet(now)) {
            percents.add(entries.get(node).window.failurePercent(now));
        }
        if (percents.isEmpty()) {
            return Optional.empty();
        }
        percents.sort(Comparator.naturalOrder());
        return Optional.of(percents.get((percents.size() - 1) / 2));
    }

    /** The failure percentage of one node over the window as it stands. */
    public synchronized int failurePercent(NodeId node, long now) {
        Entry entry = entries.get(node);
        return entry == null ? 0 : entry.window.failurePercent(now);
    }

    /** The size of the placement set the view was last given. */
    public synchronized int placementSetSize() {
        return placementSet.size();
    }

    /** The count of nodes of the placement set the view holds {@code unavailable}. */
    public synchronized int ejectedCount() {
        int ejected = 0;
        for (Map.Entry<NodeId, Entry> held : entries.entrySet()) {
            if (placementSet.contains(held.getKey())
                    && held.getValue().state == HealthState.UNAVAILABLE) {
                ejected++;
            }
        }
        return ejected;
    }

    /**
     * {@code HEALTH-034}: whether the ceiling refuses a further ejection.
     *
     * <p>The comparison is exact over both products, and it is strict, so exactly half the set may
     * be held unavailable at the default of fifty per cent.
     */
    public synchronized boolean ejectionRefused() {
        // HEALTH-016: a view that is given no placement set runs no outlier ejection and refuses
        // no transition, so the ceiling is inert rather than total until a snapshot arrives.
        return placementSetKnown
                && refused(ejectedCount(), settings.maxEjectionPercent(), placementSet.size());
    }

    /** The ceiling test over explicit operands, which the formula vectors assert directly. */
    public static boolean refused(long ejected, long maxEjectionPercent, long placementSetSize) {
        return (ejected + 1) * 100 > maxEjectionPercent * placementSetSize;
    }

    private void timers(NodeId node, Entry entry, long now) {
        if (entry.state == HealthState.UNAVAILABLE
                && now - entry.unavailableSince >= settings.ejectionMillis(entry.ejectionCount)) {
            // HEALTH-044: only the elapse of the interval reaches probation, so a stale success
            // cannot cancel an ejection.
            entry.probeCounter.set(0);
            entry.probationSince = now;
            transition(node, entry, HealthState.PROBATION, "ejectionInterval");
            return;
        }
        if (entry.state == HealthState.PROBATION
                && now - entry.probationSince >= settings.probationMillis()) {
            transition(node, entry, HealthState.AVAILABLE, "probationElapsed");
            entry.availableSince = now;
            return;
        }
        if (entry.state == HealthState.AVAILABLE && entry.availableSince != Long.MIN_VALUE
                && now - entry.availableSince >= settings.ejectionResetMillis()) {
            // HEALTH-025: the ejection count resets once the node has held available throughout.
            entry.ejectionCount = 0;
        }
    }

    private void evaluate(NodeId node, Entry entry, long now, boolean success) {
        switch (entry.state) {
            case UNKNOWN, AVAILABLE -> {
                if (success) {
                    if (entry.state == HealthState.UNKNOWN) {
                        transition(node, entry, HealthState.AVAILABLE, "success");
                        entry.availableSince = now;
                    }
                } else {
                    transition(node, entry, HealthState.SUSPECT, "failure");
                }
            }
            case SUSPECT -> suspect(node, entry, now);
            case PROBATION -> {
                if (!success) {
                    // HEALTH-046: a single failure returns the node to unavailable, and the
                    // ceiling does not refuse it, because the node was already ejected.
                    entry.ejectionCount++;
                    entry.unavailableSince = now;
                    transition(node, entry, HealthState.UNAVAILABLE, "probationFailure");
                }
            }
            default -> {
                // HEALTH-054: no signal moves a node out of unavailable.
            }
        }
    }

    private void suspect(NodeId node, Entry entry, long now) {
        long total = entry.window.total(now);
        if (entry.window.failures(now) == 0 && total >= settings.minimumSamples()) {
            transition(node, entry, HealthState.AVAILABLE, "windowClean");
            entry.availableSince = now;
            return;
        }
        Optional<String> trigger = ejectionTrigger(node, entry, now, total);
        if (trigger.isEmpty()) {
            return;
        }
        if (ejectionRefused()) {
            // HEALTH-034: a node whose transition the ceiling refuses holds suspect.
            return;
        }
        entry.ejectionCount++;
        entry.unavailableSince = now;
        transition(node, entry, HealthState.UNAVAILABLE, trigger.get());
    }

    /** {@code HEALTH-043}: the first trigger that holds, in the order that requirement writes. */
    private Optional<String> ejectionTrigger(NodeId node, Entry entry, long now, long total) {
        if (entry.consecutiveFailures >= settings.consecutiveFailureThreshold()) {
            return Optional.of("consecutiveFailures");
        }
        int percent = entry.window.failurePercent(now);
        if (total >= settings.minimumSamples() && percent >= settings.failureRatePercent()) {
            return Optional.of("failureRate");
        }
        if (outlier(node, percent, now)) {
            return Optional.of("outlier");
        }
        return Optional.empty();
    }

    /** {@code HEALTH-032}: an outlier is at least the peer median plus the margin. */
    private boolean outlier(NodeId node, int percent, long now) {
        if (!placementSetKnown || comparisonSet(now).size() < settings.outlierMinimumNodes()) {
            return false;
        }
        return peerMedian(now)
                .map(median -> percent >= median + settings.outlierMarginPercent())
                .orElse(false);
    }

    private void transition(NodeId node, Entry entry, HealthState to, String trigger) {
        if (entry.state == to) {
            return;
        }
        HealthState from = entry.state;
        transitions.add(new Transition(node, from, to, trigger));
        entry.state = to;
        if (observer != null) {
            // CORE-063: the observer is an extension point, so it is called after the lock is
            // released rather than under it. The transition is queued here and drained by the
            // public call that produced it.
            pending.add(new Transition(node, from, to, trigger));
        }
    }

    /**
     * Reports every queued transition to the observer, with no lock held.
     *
     * <p>{@code HEALTH-048} states the event; an observer is the second place the same transition
     * is reported, for an integrator whose health lives outside this process. A defective observer
     * is not permitted to fail the signal that produced the transition, so it is called inside a
     * {@code catch}.
     */
    private void notifyObserver() {
        if (observer == null) {
            return;
        }
        Transition queued;
        while ((queued = pending.poll()) != null) {
            try {
                observer.onTransition(queued.node(), queued.from(), queued.to(),
                        queued.trigger());
            } catch (RuntimeException ignored) {
                // The transition stands whatever the observer did with it.
            }
        }
    }
}
