package com.codeheadsystems.sharder.observe;

import java.util.Map;

/**
 * One event, under {@code OBS-021}.
 *
 * <p>An event reaches a sink synchronously, on the unit of execution that produced it, under
 * {@code OBS-023}. The emitter wraps every sink call in a {@code catch (Throwable)}, counts the
 * failure under {@code sharder.events.sink_failures}, and does not rethrow, so a defective sink
 * cannot fail a routing call.
 */
public record Event(String name, long at, String topologyId, long epoch, Severity severity,
                    Map<String, String> payload) {

    /** The payload is copied, so an emitter reuses nothing a sink holds. */
    public Event {
        payload = Map.copyOf(payload);
    }
}
