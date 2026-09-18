package com.codeheadsystems.sharder.core.internal.placement;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.SlotAssignment;
import com.codeheadsystems.sharder.core.internal.hash.DomainHash;
import com.codeheadsystems.sharder.core.internal.hash.U64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code slot} strategy of {@code SLOT-001} through {@code SLOT-032}.
 *
 * <p>A key's slot index is {@code keyHash(rk) mod slotCount} as an unsigned remainder, which is the
 * only division placement performs. The index reads no node, so adding or removing a node moves no
 * key between slots, under {@code SLOT-004}; what moves is which nodes a slot is assigned to.
 */
final class SlotPlacement implements PreparedPlacement {

    private final DomainHash hash;
    private final int slotCount;
    private final List<SlotAssignment> assignments;

    SlotPlacement(TopologyDocument document, DomainHash hash) {
        this.hash = hash;
        this.slotCount = document.strategy().slotCount();
        this.assignments = document.strategy().assignments();
    }

    @Override
    public List<NodeId> candidates(byte[] routingKey, EligibleSet eligible) {
        return covering(slotIndex(routingKey))
                .map(entry -> authored(entry.nodes(), eligible))
                .orElseGet(List::of);
    }

    @Override
    public Optional<String> shardOf(byte[] routingKey) {
        // SLOT-030: the slot index in decimal ASCII with no leading zeros.
        return Optional.of(Long.toString(slotIndex(routingKey)));
    }

    @Override
    public List<String> shards() {
        // SLOT-031: every index from 0 to slotCount - 1, ascending.
        List<String> shards = new java.util.ArrayList<>(slotCount);
        for (int index = 0; index < slotCount; index++) {
            shards.add(Integer.toString(index));
        }
        return List.copyOf(shards);
    }

    @Override
    public List<NodeId> candidatesForShard(String shard, EligibleSet eligible) {
        return covering(Long.parseLong(shard))
                .map(entry -> authored(entry.nodes(), eligible))
                .orElseGet(List::of);
    }

    @Override
    public String emptyCause(byte[] routingKey, EligibleSet eligible) {
        // ERR-021 evaluates the uncovered index before the excluded node list.
        return covering(slotIndex(routingKey)).isPresent()
                ? "authoredListExcludedAll"
                : "noSlotEntry";
    }

    private long slotIndex(byte[] routingKey) {
        return U64.mod(hash.keyHash(routingKey), slotCount);
    }

    private Optional<SlotAssignment> covering(long index) {
        // SLOT-011: validation makes every index covered exactly once, so the first is the one.
        return assignments.stream()
                .filter(entry -> entry.slots().stream().anyMatch(range -> range.covers(index)))
                .findFirst();
    }

    /**
     * {@code SLOT-012}: the covering entry's nodes in array order, filtered to the eligible set
     * under {@code PLACE-014} and deduplicated under {@code PLACE-013}.
     */
    private static List<NodeId> authored(List<NodeId> nodes, EligibleSet eligible) {
        Set<NodeId> ordering = new LinkedHashSet<>();
        nodes.stream().filter(eligible::contains).forEach(ordering::add);
        return List.copyOf(ordering);
    }
}
