package com.codeheadsystems.sharder.core.internal.placement;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.Node;
import com.codeheadsystems.sharder.core.internal.hash.DomainHash;
import com.codeheadsystems.sharder.core.internal.hash.U64;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code ring} strategy of {@code RING-001} through {@code RING-032}.
 *
 * <p>One ring order is built over the placement set at preparation, and a routing call walks it,
 * skipping an entry whose owner is not eligible. {@code RING-026} states that this answers exactly
 * as a ring order rebuilt over the eligible set would, because removing entries preserves the
 * relative order of those that remain.
 */
public final class RingPlacement implements PreparedPlacement {

    /** One ring entry: a token value, its owner, and the index the owner derived it at. */
    private record Entry(long token, NodeId owner, int index) {
    }

    /**
     * One ring entry as a reader sees it, with the token in the rendering of {@code HASH-044}.
     *
     * <p>The ring order is an internal structure, and the determinism vectors of {@code RING-010}
     * and {@code RING-011} assert it directly, because two identities deriving one token value is
     * the case a port is least likely to reach by its own tests.
     */
    public record RingEntry(String token, NodeId owner, int index) {
    }

    private final DomainHash hash;
    private final boolean derived;
    private final List<Entry> ring;

    RingPlacement(TopologyDocument document, DomainHash hash) {
        this.hash = hash;
        this.derived = "derived".equals(document.strategy().tokenAssignment());
        this.ring = ringOrder(document);
    }

    /**
     * The ring order of {@code RING-010}: by token value ascending as unsigned 64-bit integers,
     * then by owner identity ascending, then by token index ascending.
     */
    private List<Entry> ringOrder(TopologyDocument document) {
        List<Entry> entries = new ArrayList<>();
        for (Node node : document.placementSet()) {
            byte[] id = node.id().toBytes();
            if (derived) {
                int count = PreparedPlacement.virtualNodeCount(node,
                        document.strategy().tokensPerWeightUnit(),
                        document.strategy().maxTokensPerNode());
                for (int index = 0; index < count; index++) {
                    entries.add(new Entry(hash.ringToken(id, index), node.id(), index));
                }
            } else {
                // RING-004: weight does not reach an authored token list, so a node of weight 0
                // that carries tokens owns the ranges they terminate.
                List<Long> tokens = node.tokens();
                for (int index = 0; index < tokens.size(); index++) {
                    entries.add(new Entry(tokens.get(index), node.id(), index));
                }
            }
        }
        entries.sort(RingPlacement::ringLess);
        return List.copyOf(entries);
    }

    private static int ringLess(Entry left, Entry right) {
        int byToken = U64.compare(left.token(), right.token());
        if (byToken != 0) {
            return byToken;
        }
        int byOwner = left.owner().compareTo(right.owner());
        return byOwner != 0 ? byOwner : Integer.compare(left.index(), right.index());
    }

    /** The ring order, in the order {@code RING-010} defines. */
    public List<RingEntry> ringOrder() {
        return ring.stream()
                .map(entry -> new RingEntry(U64.toHex(entry.token()), entry.owner(), entry.index()))
                .toList();
    }

    @Override
    public List<NodeId> candidates(byte[] routingKey, EligibleSet eligible) {
        return walk(positionAtOrAbove(hash.keyHash(routingKey)), eligible);
    }

    @Override
    public Optional<String> shardOf(byte[] routingKey) {
        if (ring.isEmpty()) {
            return Optional.empty();
        }
        // RING-030: the owning entry is computed over the placement set, so an override constraint
        // never renames a shard.
        int owning = positionAtOrAbove(hash.keyHash(routingKey));
        return Optional.of(U64.toHex(ring.get(owning).token()));
    }

    @Override
    public List<String> shards() {
        // RING-031: each distinct token value once, in ascending ring order.
        Set<String> shards = new LinkedHashSet<>();
        ring.forEach(entry -> shards.add(U64.toHex(entry.token())));
        return List.copyOf(shards);
    }

    @Override
    public List<NodeId> candidatesForShard(String shard, EligibleSet eligible) {
        // RING-032: a shard whose token is absent from the eligible ring starts the walk at the
        // next entry at or above it, wrapping, rather than answering empty.
        return walk(positionAtOrAbove(U64.parseHex(shard)), eligible);
    }

    @Override
    public String emptyCause(byte[] routingKey, EligibleSet eligible) {
        // ERR-021 separates a derived ring, where no eligible node earned a token, from an
        // explicit one, where no eligible node was authored any.
        return derived ? "zeroVirtualNodes" : "noEligibleTokenOwner";
    }

    /**
     * {@code RING-020}: the least index whose token is at or above the value, wrapping to the first
     * entry where no token is.
     */
    private int positionAtOrAbove(long value) {
        int low = 0;
        int high = ring.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (U64.compare(ring.get(middle).token(), value) >= 0) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low == ring.size() ? 0 : low;
    }

    /**
     * {@code RING-021}: the owners encountered walking ascending from the owning entry, wrapping
     * once, visiting every entry exactly once, appending each owner the first time it is met.
     */
    private List<NodeId> walk(int start, EligibleSet eligible) {
        if (ring.isEmpty()) {
            return List.of();
        }
        Set<NodeId> seen = new LinkedHashSet<>();
        for (int step = 0; step < ring.size(); step++) {
            NodeId owner = ring.get((start + step) % ring.size()).owner();
            if (eligible.contains(owner)) {
                seen.add(owner);
            }
        }
        return List.copyOf(seen);
    }
}
