package com.codeheadsystems.sharder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * An unordered set of node identities, iterated in ascending node identity.
 *
 * <p>{@code CORE-002} forbids the iteration order of a node set from reaching a result. A
 * {@code java.util.Set} hands a caller {@code stream}, {@code iterator}, and {@code parallelStream}
 * in hash order, which is an invitation to exactly that defect, so this class is purpose-built and
 * iterates in a defined total order instead. An implementation that leans on the order accidentally
 * produces the same result on every virtual machine and in every run, and the permuted-{@code nodes}
 * vectors of {@code PROP-005} are what catch the lean.
 */
public final class NodeSet implements Iterable<NodeId> {

    private static final NodeSet EMPTY = new NodeSet(List.of());

    private final List<NodeId> ordered;

    private NodeSet(List<NodeId> ordered) {
        this.ordered = ordered;
    }

    /** The set over {@code ids}, with a repeat counted once. */
    public static NodeSet of(Collection<NodeId> ids) {
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return EMPTY;
        }
        List<NodeId> ordered = new ArrayList<>(ids.size());
        for (NodeId id : ids) {
            Objects.requireNonNull(id, "id");
            if (!ordered.contains(id)) {
                ordered.add(id);
            }
        }
        Collections.sort(ordered);
        return new NodeSet(List.copyOf(ordered));
    }

    /** The empty set. */
    public static NodeSet empty() {
        return EMPTY;
    }

    /** Whether the set holds {@code id}. */
    public boolean contains(NodeId id) {
        return ordered.contains(id);
    }

    /** The number of identities the set holds. */
    public int size() {
        return ordered.size();
    }

    /** Whether the set holds no identity. */
    public boolean isEmpty() {
        return ordered.isEmpty();
    }

    @Override
    public Iterator<NodeId> iterator() {
        return ordered.iterator();
    }

    /** The identities in ascending node identity, as a list. */
    public List<NodeId> asList() {
        return ordered;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NodeSet set && ordered.equals(set.ordered);
    }

    @Override
    public int hashCode() {
        return ordered.hashCode();
    }

    @Override
    public String toString() {
        return ordered.toString();
    }
}
