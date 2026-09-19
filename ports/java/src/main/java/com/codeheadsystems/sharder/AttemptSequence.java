package com.codeheadsystems.sharder;

import com.codeheadsystems.sharder.health.Outcome;
import java.util.Optional;

/**
 * The attempt walk of one routing decision, under {@code FAIL-020}.
 *
 * <p>A sequence belongs to one request and one unit of execution under {@code CORE-057}. It is not
 * synchronized. {@link #next()} never waits, sleeps, or backs off, under {@code FAIL-027}: spacing
 * between attempts belongs to the caller.
 */
public interface AttemptSequence {

    /** The next node to attempt, empty where the sequence is exhausted, under {@code FAIL-023}. */
    Optional<NodeId> next();

    /** Reports what an attempt did, at the instant given. */
    void recordOutcome(NodeId node, Outcome outcome, long at);

    /** Reports what an attempt did, reading the configured clock. */
    void recordOutcome(NodeId node, Outcome outcome);

    /** How many attempts the sequence may still offer. */
    int remaining();

    /**
     * The node to retry at after a redirect, under {@code FENCE-221}.
     *
     * <p>The walk reads the identities this sequence has already attempted, the bound of
     * {@code CFG-040}, and the retry budget, because {@code FENCE-231} makes a followed redirect a
     * retry against that budget. Where no node remains it raises
     * {@code RedirectExhaustedException} carrying the cause of {@code ERR-043}.
     */
    NodeId followRedirect(NodeId owner, long at);

    /** The node to retry at after a redirect, reading the configured clock. */
    NodeId followRedirect(NodeId owner);
}
