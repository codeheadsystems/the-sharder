package com.codeheadsystems.sharder.topology;

import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.core.internal.snapshot.DocumentSnapshot;
import com.codeheadsystems.sharder.error.InvalidArgumentException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The shards whose replica sets differ between two snapshots, under {@code TOPO-211}.
 *
 * <p>The delta is computed on demand rather than at installation, and it reads the replica prefix
 * of each shard rather than the whole preference list. Its entries come in the two groups
 * {@code TOPO-213} fixes: first the shards the later snapshot enumerates, in that snapshot's
 * order, then the shards only the earlier one enumerates. A shard the earlier snapshot did not
 * enumerate has an empty before set, and one the later snapshot does not enumerate has an empty
 * after set.
 *
 * <p>A delta joins the two snapshots on the shard identifier, so it answers which shards changed
 * owner and not where a new shard's contents are. Where an epoch changes which shards exist, that
 * second question is answered by {@code HandoffCoordinator.lineage}.
 */
public record OwnershipDelta(List<ShardChange> changes) {

    /** The changes are copied, so a caller holds a delta across an installation unchanged. */
    public OwnershipDelta {
        changes = List.copyOf(changes);
    }

    /**
     * The delta between two snapshots.
     *
     * <p>A delta joins two snapshots on shard identifiers, so two snapshots whose shard identity
     * is not comparable under {@code TOPO-231} refuse the computation rather than reporting every
     * shard as wholly changed.
     */
    public static OwnershipDelta between(TopologySnapshot from, TopologySnapshot to) {
        DocumentSnapshot before = snapshot(from, "from");
        DocumentSnapshot after = snapshot(to, "to");
        Optional<String> incomparable = com.codeheadsystems.sharder.core.internal.route
                .OwnershipDelta.incomparable(before.document(), after.document());
        if (incomparable.isPresent()) {
            throw new InvalidArgumentException(
                    "the two snapshots differ in " + incomparable.get()
                            + ", so their shard identity is not comparable");
        }
        List<ShardChange> changes = new ArrayList<>();
        com.codeheadsystems.sharder.core.internal.route.OwnershipDelta
                .between(before.engine(), after.engine())
                .forEach(change -> changes.add(new ShardChange(ShardId.of(change.shard()),
                        change.before(), change.after(), change.gained(), change.lost())));
        return new OwnershipDelta(changes);
    }

    private static DocumentSnapshot snapshot(TopologySnapshot snapshot, String name) {
        if (snapshot instanceof DocumentSnapshot document) {
            return document;
        }
        throw new InvalidArgumentException(
                "the " + name + " snapshot was not produced by this library");
    }
}
