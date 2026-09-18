package com.codeheadsystems.sharder.core.internal.placement;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.Node;
import com.codeheadsystems.sharder.core.internal.hash.DomainHash;
import com.codeheadsystems.sharder.core.internal.hash.U64;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The {@code rendezvous} strategy of {@code RV-001} through {@code RV-022}.
 *
 * <p>A node's score is the largest of its virtual nodes' scores, compared as unsigned 64-bit
 * integers. No score is scaled by weight, and weight reaches the ordering only through the virtual
 * node count, under {@code RV-004} and {@code PLACE-041}.
 */
public final class RendezvousPlacement implements PreparedPlacement {

    /** One scored node, ordered by {@code RV-010}: score descending, then identity. */
    private record Scored(long score, NodeId id) {
    }

    private final DomainHash hash;
    private final List<Node> placementSet;
    private final List<Integer> counts;

    RendezvousPlacement(TopologyDocument document, DomainHash hash) {
        this.hash = hash;
        this.placementSet = document.placementSet();
        List<Integer> counts = new ArrayList<>();
        for (Node node : placementSet) {
            counts.add(PreparedPlacement.virtualNodeCount(node,
                    document.strategy().virtualNodesPerWeightUnit(),
                    document.strategy().maxVirtualNodesPerNode()));
        }
        this.counts = List.copyOf(counts);
    }

    /**
     * A node's score of {@code RV-003}: the largest of its virtual nodes' scores, compared as
     * unsigned 64-bit values.
     *
     * <p>The determinism vectors assert the score two identities share, because a score tie is
     * where the node identity tie-break of {@code RV-010} is the only thing deciding an ordering.
     */
    public long score(byte[] routingKey, NodeId id) {
        for (int index = 0; index < placementSet.size(); index++) {
            if (!placementSet.get(index).id().equals(id)) {
                continue;
            }
            byte[] octets = id.toBytes();
            long best = 0;
            for (int virtual = 0; virtual < counts.get(index); virtual++) {
                best = U64.max(best, hash.rvScore(routingKey, octets, virtual));
            }
            return best;
        }
        throw new IllegalArgumentException("no node named " + id.asText());
    }

    @Override
    public List<NodeId> candidates(byte[] routingKey, EligibleSet eligible) {
        List<Scored> scored = new ArrayList<>();
        for (int index = 0; index < placementSet.size(); index++) {
            Node node = placementSet.get(index);
            int count = counts.get(index);
            // RV-002: a node of no virtual node has no score and no place in the ordering.
            if (count == 0 || !eligible.contains(node.id())) {
                continue;
            }
            byte[] id = node.id().toBytes();
            long best = 0;
            for (int virtual = 0; virtual < count; virtual++) {
                best = U64.max(best, hash.rvScore(routingKey, id, virtual));
            }
            scored.add(new Scored(best, node.id()));
        }
        scored.sort((left, right) -> {
            int byScore = U64.compare(right.score(), left.score());
            return byScore != 0 ? byScore : left.id().compareTo(right.id());
        });
        return scored.stream().map(Scored::id).toList();
    }

    @Override
    public Optional<String> shardOf(byte[] routingKey) {
        // RV-020: the shard names one routing key, so its identifier is that key in hexadecimal.
        return Optional.of(HexFormat.of().formatHex(routingKey));
    }

    @Override
    public List<String> shards() {
        // RV-021: a rendezvous topology names no shard extent.
        return List.of();
    }

    @Override
    public List<NodeId> candidatesForShard(String shard, EligibleSet eligible) {
        return candidates(HexFormat.of().parseHex(shard), eligible);
    }

    @Override
    public String emptyCause(byte[] routingKey, EligibleSet eligible) {
        return "zeroVirtualNodes";
    }
}
