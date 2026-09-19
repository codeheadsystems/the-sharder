package com.codeheadsystems.sharder.migrate;

/**
 * What one {@code observe} call read, under {@code MOVE-111}.
 *
 * <p>{@code Unavailable} states that the hook could not read the durable state, and
 * {@code Undetermined} that it read it and the state does not establish whether a cutover record
 * exists. {@code MOVE-112} forbids treating the two as one answer.
 */
public sealed interface ObserveResult {

    /** The durable state was read, and this is what it says. */
    record Observed(Observation observation) implements ObserveResult {
    }

    /** The durable state could not be read. */
    record Unavailable(String reason) implements ObserveResult {
    }

    /** The durable state was read and establishes nothing about a cutover record. */
    record Undetermined() implements ObserveResult {
    }
}
