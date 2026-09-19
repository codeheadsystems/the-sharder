package com.codeheadsystems.sharder.migrate;

import com.codeheadsystems.sharder.ShardId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Where each shard's contents come from across an epoch, under {@code LIN-031}.
 *
 * <p>An ownership delta joins two snapshots on the shard identifier and answers which shards
 * changed owner. Where an epoch changes which shards exist, that join has no answer for the ones
 * that appeared or vanished. A lineage is the second join, over the keys a shard holds rather than
 * over its name, and it is what a plan reads to decide where a divided shard's contents are.
 *
 * <p>It is derived from the two topology documents. No member of a document records it, and an
 * implementation never computes one by sampling routing keys, under {@code LIN-001}.
 *
 * <p>Where the two snapshots enumerate the same shards the lineage is the identity: every shard is
 * its own parent and nothing is divided, merged, fresh, or vacated. That is every pair under
 * {@code slot}, and every pair under any kind whose shard set did not move.
 */
public record ShardLineage(List<Entry> entries) {

    /**
     * One shard's class and the shards its extent draws from.
     *
     * <p>{@code parents} names the shards of the earlier snapshot for an entry the later snapshot
     * enumerates, and the shards of the later snapshot for one only the earlier snapshot
     * enumerates. It is empty for a shard that is {@code FRESH} or {@code VACATED}.
     */
    public record Entry(ShardId shard, LineageClass lineage, List<ShardId> parents) {

        /** The lists are copied, so a caller holds an entry across an installation unchanged. */
        public Entry {
            parents = List.copyOf(parents);
        }
    }

    /** The entries are copied, in the order {@code LIN-033} fixes. */
    public ShardLineage {
        entries = List.copyOf(entries);
    }

    /** The entry for one shard, where the lineage carries one. */
    public Optional<Entry> entry(ShardId shard) {
        return entries.stream().filter(entry -> entry.shard().equals(shard)).findFirst();
    }

    /**
     * How many shards fall in each class, which is what {@code migration.lineage} reports.
     *
     * <p>An operator reads this to see whether an epoch refined the keyspace or redistributed it.
     * The library takes no position on which it should have been, under
     * {@code adr/0091}.
     */
    public Map<LineageClass, Long> counts() {
        Map<LineageClass, Long> out = new TreeMap<>();
        for (Entry entry : entries) {
            out.merge(entry.lineage(), 1L, Long::sum);
        }
        return Map.copyOf(out);
    }

    /** Whether every shard kept the extent it held, which is the identity of {@code LIN-007}. */
    public boolean identity() {
        return entries.stream().allMatch(entry -> entry.lineage().extentUnchanged());
    }
}
