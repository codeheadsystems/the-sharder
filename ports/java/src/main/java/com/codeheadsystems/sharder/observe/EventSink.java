package com.codeheadsystems.sharder.observe;

/**
 * Where events go.
 *
 * <p>Where no sink is supplied, events are counted by name and their payloads are not buffered,
 * under {@code OBS-025}.
 */
@FunctionalInterface
public interface EventSink {

    /** Accepts one event, on the unit of execution that produced it. */
    void accept(Event event);
}
