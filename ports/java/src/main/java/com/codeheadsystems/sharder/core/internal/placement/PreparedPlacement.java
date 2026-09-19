package com.codeheadsystems.sharder.core.internal.placement;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.Node;
import com.codeheadsystems.sharder.core.internal.hash.DomainHash;
import com.codeheadsystems.sharder.core.internal.hash.U64;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * A placement strategy prepared over one snapshot, which is stage 6 of {@code TOPO-001}.
 *
 * <p>Preparation is a pure function of the snapshot under {@code PLACE-012}, so two prepared
 * placements built from documents with equal digests answer identically for every routing key and
 * every eligible node set. A candidate ordering reads neither health, nor time, nor a node's
 * address, under {@code PLACE-011}.
 */
public interface PreparedPlacement {

    /**
     * The candidate ordering for a routing key, as a cursor over it.
     *
     * <p>{@code PLACE-015} permits the ordering to be computed lazily, and requires the first
     * {@code p} entries a caller consumes to equal the first {@code p} of the ordering computed
     * eagerly, for every {@code p}. A routing call consumes the prefix {@code CORE-046} bounds it
     * at and no more, so a topology of a thousand nodes costs a prefix rather than a thousand
     * entries on every call.
     */
    Iterator<NodeId> cursor(byte[] routingKey, EligibleSet eligible);

    /** The whole candidate ordering, drained from the cursor, which {@code CORE-047} answers. */
    default List<NodeId> candidates(byte[] routingKey, EligibleSet eligible) {
        return drain(cursor(routingKey, eligible));
    }

    /** Every entry a cursor answers, in order. */
    static List<NodeId> drain(Iterator<NodeId> cursor) {
        List<NodeId> ordering = new ArrayList<>();
        while (cursor.hasNext()) {
            ordering.add(cursor.next());
        }
        return List.copyOf(ordering);
    }

    /** The first entries of a cursor, at most {@code limit} of them. */
    static List<NodeId> take(Iterator<NodeId> cursor, int limit) {
        List<NodeId> prefix = new ArrayList<>(limit);
        while (prefix.size() < limit && cursor.hasNext()) {
            prefix.add(cursor.next());
        }
        return List.copyOf(prefix);
    }

    /** The shard of a routing key, computed over the placement set under {@code PLACE-030}. */
    Optional<String> shardOf(byte[] routingKey);

    /** Every shard the strategy names, in the enumeration order of {@code PLACE-031}. */
    List<String> shards();

    /**
     * The same enumeration as a cursor over it.
     *
     * <p>A ring of a million tokens names a million shards, and a caller that wants their count or
     * a prefix of them has no need of a million strings at once. The default drains the list,
     * which is what a strategy naming few shards does anyway.
     */
    default Iterator<String> shardCursor() {
        return shards().iterator();
    }

    /** The candidate ordering of a shard, as a cursor, under {@code PLACE-033}. */
    Iterator<NodeId> cursorForShard(String shard, EligibleSet eligible);

    /** The whole candidate ordering of a shard. */
    default List<NodeId> candidatesForShard(String shard, EligibleSet eligible) {
        return drain(cursorForShard(shard, eligible));
    }

    /** The cause {@code ERR-021} gives where this strategy produced no candidate. */
    String emptyCause(byte[] routingKey, EligibleSet eligible);

    /** The strategy the document names, prepared over its placement set. */
    static PreparedPlacement of(TopologyDocument document) {
        DomainHash hash = DomainHash.ofKey(document.hashSeed());
        return switch (document.strategy().kind()) {
            case "ring" -> new RingPlacement(document, hash);
            case "rendezvous" -> new RendezvousPlacement(document, hash);
            case "slot" -> new SlotPlacement(document, hash);
            case "directory" -> new DirectoryPlacement(document);
            default -> throw new IllegalArgumentException(
                    "no placement strategy named " + document.strategy().kind());
        };
    }

    /**
     * The virtual node count of {@code PLACE-050}.
     *
     * <p>The product is computed in 64 bits before the cap is applied, under {@code PLACE-051}: it
     * reaches 4096000000 at the configured maxima, which overflows a signed 32-bit integer.
     */
    static int virtualNodeCount(Node node, int perWeightUnit, int cap) {
        long requested = (long) node.weight() * (long) perWeightUnit;
        return (int) Math.min(requested, cap);
    }

    /** The unsigned 64-bit rendering of {@code HASH-044}, which names a ring shard. */
    static String token(long value) {
        return U64.toHex(value);
    }
}
