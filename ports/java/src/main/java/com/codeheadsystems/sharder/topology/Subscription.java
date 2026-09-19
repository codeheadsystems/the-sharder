package com.codeheadsystems.sharder.topology;

/** A push subscription, which a router cancels when it closes. */
public interface Subscription extends AutoCloseable {

    /** Stops delivery to the sink. A second call does nothing. */
    void cancel();

    @Override
    default void close() {
        cancel();
    }
}
