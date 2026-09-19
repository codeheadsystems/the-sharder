package com.codeheadsystems.sharder.core.internal.observe;

import com.codeheadsystems.sharder.observe.Event;
import com.codeheadsystems.sharder.observe.EventSink;
import com.codeheadsystems.sharder.observe.Labels;
import com.codeheadsystems.sharder.observe.MetricsRegistry;
import com.codeheadsystems.sharder.observe.MetricsView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where a router's metrics and events go, under {@code OBS-004} and {@code OBS-025}.
 *
 * <p>Where a registry is supplied the values are forwarded to it and held here as well, so
 * {@code Router.metrics()} answers the same numbers whichever way an integrator reads them. Where a
 * sink is supplied every event reaches it synchronously, on the unit of execution that produced it,
 * under {@code OBS-023}; a sink that throws is counted under
 * {@code sharder.events.sink_failures} and does not fail the call that emitted the event, which is
 * one of the two places this library catches {@code Throwable}.
 */
public final class MetricsHolder implements MetricsView {

    /** The counter a defective sink increments. */
    public static final String SINK_FAILURES = "sharder.events.sink_failures";

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> events = new ConcurrentHashMap<>();
    private final MetricsRegistry registry;
    private final EventSink sink;

    /** The holder over the registry and sink the configuration supplied, either of them absent. */
    public MetricsHolder(Optional<MetricsRegistry> registry, Optional<EventSink> sink) {
        this.registry = registry.orElse(null);
        this.sink = sink.orElse(null);
    }

    /** Adds {@code delta} to a counter, and forwards it to a supplied registry. */
    public void counter(String name, Labels labels, long delta) {
        counters.computeIfAbsent(key(name, labels), ignored -> new AtomicLong()).addAndGet(delta);
        if (registry != null) {
            registry.counter(name, labels, delta);
        }
    }

    /** Emits one event, counting it by name where no sink is supplied. */
    public void emit(Event event) {
        events.computeIfAbsent(event.name(), ignored -> new AtomicLong()).incrementAndGet();
        if (sink == null) {
            return;
        }
        try {
            sink.accept(event);
        } catch (Throwable failure) {
            counters.computeIfAbsent(SINK_FAILURES, ignored -> new AtomicLong()).incrementAndGet();
        }
    }

    @Override
    public Map<String, Long> counters() {
        Map<String, Long> reading = new LinkedHashMap<>();
        counters.forEach((name, value) -> reading.put(name, value.get()));
        return Map.copyOf(reading);
    }

    @Override
    public Optional<Long> counter(String name, Labels labels) {
        AtomicLong value = counters.get(key(name, labels));
        return value == null ? Optional.empty() : Optional.of(value.get());
    }

    @Override
    public Map<String, Long> events() {
        Map<String, Long> reading = new LinkedHashMap<>();
        events.forEach((name, value) -> reading.put(name, value.get()));
        return Map.copyOf(reading);
    }

    private static String key(String name, Labels labels) {
        if (labels.size() == 0) {
            return name;
        }
        StringBuilder key = new StringBuilder(name);
        for (int index = 0; index < labels.size(); index++) {
            key.append(index == 0 ? '{' : ',').append(labels.name(index)).append('=')
                    .append(labels.value(index));
        }
        return key.append('}').toString();
    }
}
