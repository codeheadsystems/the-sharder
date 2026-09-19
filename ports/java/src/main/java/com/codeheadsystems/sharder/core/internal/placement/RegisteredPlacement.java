package com.codeheadsystems.sharder.core.internal.placement;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.NodeSet;
import com.codeheadsystems.sharder.RoutingKey;
import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.placement.CandidateCursor;
import com.codeheadsystems.sharder.placement.PlacementStrategy;
import com.codeheadsystems.sharder.topology.TopologySnapshot;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A strategy an integrator registered, as the routing path reads it.
 *
 * <p>The strategy is prepared against the snapshot that carries it, under {@code CORE-011}, and the
 * snapshot is not built until the engine that holds this placement exists. The preparation is
 * therefore deferred to the first call that needs it and then held: {@code PLACE-012} makes
 * {@code prepare} a pure function of the snapshot, so a second preparation would answer what the
 * first did and the race that computes one twice changes no result.
 *
 * <p>A strategy that reads {@code snapshot.placement()} inside its own {@code prepare} asks the
 * snapshot for the thing being prepared, and is refused rather than recursing.
 */
public final class RegisteredPlacement implements PreparedPlacement {

    private final PlacementStrategy strategy;
    private final Supplier<TopologySnapshot> binding;
    private final Object lock = new Object();
    private volatile com.codeheadsystems.sharder.placement.PreparedPlacement prepared;
    private volatile Thread preparing;

    /** The placement of one registered strategy over the snapshot the binding answers. */
    public RegisteredPlacement(PlacementStrategy strategy, Supplier<TopologySnapshot> binding) {
        this.strategy = strategy;
        this.binding = binding;
    }

    /** The strategy this placement was registered for. */
    public PlacementStrategy strategy() {
        return strategy;
    }

    private com.codeheadsystems.sharder.placement.PreparedPlacement prepared() {
        com.codeheadsystems.sharder.placement.PreparedPlacement held = prepared;
        if (held != null) {
            return held;
        }
        // The thread preparing is what distinguishes a strategy reading its own placement from a
        // second thread arriving while the first prepares. The second waits on the lock and then
        // reads what the first produced; the first is refused rather than recursing.
        if (Thread.currentThread() == preparing) {
            throw new IllegalStateException(
                    "the strategy " + strategy.name() + " read its own placement while preparing");
        }
        synchronized (lock) {
            held = prepared;
            if (held != null) {
                return held;
            }
            TopologySnapshot snapshot = binding.get();
            if (snapshot == null) {
                throw new IllegalStateException("the strategy " + strategy.name()
                        + " was read before its snapshot was built");
            }
            preparing = Thread.currentThread();
            try {
                held = strategy.prepare(snapshot);
            } finally {
                preparing = null;
            }
            prepared = held;
            return held;
        }
    }

    @Override
    public void prepare() {
        prepared();
    }

    @Override
    public Iterator<NodeId> cursor(byte[] routingKey, EligibleSet eligible) {
        return iterator(prepared().candidates(RoutingKey.ofBytes(routingKey), nodeSet(eligible)));
    }

    @Override
    public Optional<String> shardOf(byte[] routingKey) {
        return prepared().shardOf(RoutingKey.ofBytes(routingKey)).map(ShardId::asText);
    }

    @Override
    public List<String> shards() {
        List<String> shards = new ArrayList<>();
        Iterator<ShardId> cursor = prepared().shards();
        while (cursor.hasNext()) {
            shards.add(cursor.next().asText());
        }
        return List.copyOf(shards);
    }

    @Override
    public Iterator<NodeId> cursorForShard(String shard, EligibleSet eligible) {
        return iterator(prepared().candidatesForShard(ShardId.of(shard), nodeSet(eligible)));
    }

    @Override
    public String emptyCause(byte[] routingKey, EligibleSet eligible) {
        // ERR-021 names no cause a registered strategy states, so an empty ordering under one is
        // reported as the empty placement set the row of last resort names.
        return "emptyPlacementSet";
    }

    /** The eligible set as the public surface takes it. */
    private static NodeSet nodeSet(EligibleSet eligible) {
        return NodeSet.of(eligible.identities());
    }

    /** The public cursor as an iterator, which advances one candidate at a time either way. */
    private static Iterator<NodeId> iterator(CandidateCursor cursor) {
        return new Iterator<>() {
            private NodeId pending;
            private boolean drained;

            @Override
            public boolean hasNext() {
                if (pending == null && !drained) {
                    if (cursor.advance()) {
                        pending = cursor.node();
                    } else {
                        drained = true;
                    }
                }
                return pending != null;
            }

            @Override
            public NodeId next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                NodeId answer = pending;
                pending = null;
                return answer;
            }
        };
    }
}
