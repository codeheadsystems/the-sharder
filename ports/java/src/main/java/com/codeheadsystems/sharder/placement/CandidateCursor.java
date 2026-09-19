package com.codeheadsystems.sharder.placement;

import com.codeheadsystems.sharder.NodeId;

/**
 * The lazy ordered traversal of the candidate ordering that {@code PLACE-015} requires.
 *
 * <p>A preference list builder consumes only the prefix it needs: at factor 3 over a thousand-node
 * ring it reads a handful of entries out of a thousand.
 *
 * <p>It is not an {@code Iterator}, because {@code hasNext} forces the next element to be computed
 * and buffered before a caller has decided it wants one, and a ring walk's next element is a scan
 * over ring entries whose owners have already been emitted. It is not a {@code Stream}, because
 * {@code Stream} offers {@code parallel}, which {@code CORE-012} forbids outright, and
 * {@code collect}, which discards the laziness the requirement exists to provide.
 *
 * <p>A cursor belongs to one unit of execution under {@code CORE-012} and {@code CORE-057}, and
 * takes no lock.
 */
public interface CandidateCursor {

    /** Whether a further candidate exists, and advances to it where one does. */
    boolean advance();

    /** The current candidate, valid after {@link #advance()} answered true. */
    NodeId node();
}
