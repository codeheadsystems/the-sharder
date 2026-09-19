package com.codeheadsystems.sharder.core.internal.placement;

import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.hash.U64;
import com.codeheadsystems.sharder.core.internal.route.OwnershipDelta;
import com.codeheadsystems.sharder.core.internal.route.PlacementEngine;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shard extents and the lineage over them, under {@code LIN-001} through {@code LIN-034}.
 *
 * <p>The extent of a shard is the set of routing keys {@code shardOf} maps to it, determined by the
 * document alone under {@code LIN-001}. Under {@code ring} an extent is the half-open token
 * interval of {@code RING-020}, which wraps at zero, so containment is interval arithmetic over
 * unsigned 64-bit bounds and every comparison here goes through {@link U64}. A signed comparison
 * would read an extent near the top of the space as empty, which is why this class sits in the
 * package {@code verifyUnsignedComparisons} covers rather than beside the coordinator.
 *
 * <p>Under {@code slot} {@code TOPO-231} holds {@code slotCount} equal, and under {@code directory}
 * {@code LIN-013} refuses a pair whose shard sets differ, so both are the identity lineage of
 * {@code LIN-007} and neither reaches the interval arithmetic.
 */
public final class ShardExtents {

    /** The class of one shard under {@code LIN-021}. */
    public enum Lineage {
        UNCHANGED, MOVED, DIVIDED, MERGED, FRESH, SPLIT, FOLDED, VACATED
    }

    /** One shard's class and the shards its extent draws from. */
    public record Entry(String shard, Lineage lineage, List<String> parents) {
    }

    /** A lineage that cannot be computed, carrying the cause {@code ERR-050} reports. */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String cause;

        Refused(String cause, String detail) {
            super(cause + ": " + detail);
            this.cause = cause;
        }

        public String cause() {
            return cause;
        }
    }

    /**
     * One inclusive segment {@code [lo, hi]} of the hash space, with unsigned 64-bit bounds.
     *
     * <p>Bounds are inclusive rather than half-open because the space ends at an unsigned value a
     * {@code long} cannot hold one past, and an interval that wraps past zero is two segments, so
     * every segment here has {@code lo} at or below {@code hi} and no arithmetic reasons about a
     * wrap. Nothing measures a width: a width of the whole space overflows, so containment is
     * decided by subtraction instead.
     */
    private record Segment(long lo, long hi) {
    }

    private ShardExtents() {
    }

    /** The keys {@code h} with {@code lowExclusive < h <= highInclusive}, as segments. */
    private static List<Segment> segments(long lowExclusive, long highInclusive) {
        // An equality of two 64-bit values compiles to LCMP, which the placement path forbids, so
        // the comparison goes through U64 like every other one here.
        if (U64.compare(lowExclusive, highInclusive) == 0) {
            // One token owns the whole space, from zero to the greatest unsigned value.
            return List.of(new Segment(0L, -1L));
        }
        long lo = lowExclusive + 1;
        if (U64.compare(lo, highInclusive) <= 0) {
            return List.of(new Segment(lo, highInclusive));
        }
        // The interval wraps past zero, so it is the head of the space and its tail.
        return List.of(new Segment(0L, highInclusive), new Segment(lo, -1L));
    }

    /** The extent of each shard the ring names, under {@code LIN-011}. */
    private static Map<String, List<Segment>> ringExtents(List<String> shards) {
        Map<String, List<Segment>> out = new LinkedHashMap<>();
        int count = shards.size();
        for (int index = 0; index < count; index++) {
            long token = U64.parseHex(shards.get(index));
            long predecessor = count == 1
                    ? token
                    : U64.parseHex(shards.get((index - 1 + count) % count));
            out.put(shards.get(index), segments(predecessor, token));
        }
        return out;
    }

    private static Optional<Segment> overlap(Segment left, Segment right) {
        long lo = U64.max(left.lo(), right.lo());
        long hi = U64.min(left.hi(), right.hi());
        return U64.compare(lo, hi) <= 0 ? Optional.of(new Segment(lo, hi)) : Optional.empty();
    }

    private static boolean meets(List<Segment> first, List<Segment> second) {
        for (Segment left : first) {
            for (Segment right : second) {
                if (overlap(left, right).isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** What remains of {@code inner} once every segment of {@code outer} is taken out of it. */
    private static List<Segment> subtract(List<Segment> inner, List<Segment> outer) {
        List<Segment> remaining = new ArrayList<>(inner);
        for (Segment cut : outer) {
            List<Segment> next = new ArrayList<>();
            for (Segment piece : remaining) {
                Optional<Segment> shared = overlap(piece, cut);
                if (shared.isEmpty()) {
                    next.add(piece);
                    continue;
                }
                Segment taken = shared.get();
                if (U64.compare(piece.lo(), taken.lo()) < 0) {
                    next.add(new Segment(piece.lo(), taken.lo() - 1));
                }
                if (U64.compare(taken.hi(), piece.hi()) < 0) {
                    next.add(new Segment(taken.hi() + 1, piece.hi()));
                }
            }
            remaining = next;
        }
        return remaining;
    }

    private static boolean contains(List<Segment> outer, List<Segment> inner) {
        return subtract(inner, outer).isEmpty();
    }

    private static boolean equal(List<Segment> first, List<Segment> second) {
        return contains(first, second) && contains(second, first);
    }

    /**
     * One region of the keyspace the two directory tables cut it into, under {@code LIN-016}.
     *
     * <p>{@code exact} is the key equal to {@code node}; otherwise it is the keys strictly
     * extending {@code node} that no deeper node of the combined trie is a prefix of.
     */
    private record Region(boolean exact, String node) {
    }

    /** Every decoded matcher value of either table, rendered as hexadecimal, and the empty one. */
    private static List<Region> regions(TopologyDocument before, TopologyDocument after) {
        Set<String> nodes = new java.util.TreeSet<>();
        nodes.add("");
        for (TopologyDocument document : List.of(before, after)) {
            for (TopologyDocument.DirectoryEntry entry : document.strategy().entries()) {
                nodes.add(HexFormat.of().formatHex(entry.match().value()));
            }
        }
        List<Region> out = new ArrayList<>();
        nodes.stream().filter(node -> !node.isEmpty())
                .forEach(node -> out.add(new Region(true, node)));
        nodes.forEach(node -> out.add(new Region(false, node)));
        return List.copyOf(out);
    }

    /**
     * {@code PLACE-065}: the entry index winning a region, or {@code -1} where none matches it.
     *
     * <p>An {@code exact} matcher wins only the region that is its own key, because a region of
     * keys strictly extending a node contains no node and every matcher value is one.
     */
    private static int winner(TopologyDocument document, Region region) {
        List<TopologyDocument.DirectoryEntry> entries = document.strategy().entries();
        HexFormat hex = HexFormat.of();
        if (region.exact()) {
            for (int index = 0; index < entries.size(); index++) {
                TopologyDocument.Matcher matcher = entries.get(index).match();
                if ("exact".equals(matcher.kind())
                        && hex.formatHex(matcher.value()).equals(region.node())) {
                    return index;
                }
            }
        }
        int best = -1;
        int bestLength = -1;
        for (int index = 0; index < entries.size(); index++) {
            TopologyDocument.Matcher matcher = entries.get(index).match();
            if (!"prefix".equals(matcher.kind())) {
                continue;
            }
            String value = hex.formatHex(matcher.value());
            if (!region.node().startsWith(value)) {
                continue;
            }
            if (value.length() > bestLength) {
                best = index;
                bestLength = value.length();
            }
        }
        return best;
    }

    /** {@code LIN-013}: each shard's extent as the set of regions its entry wins. */
    private static Map<String, Set<Region>> directoryExtents(PlacementEngine engine,
                                                             List<Region> regions) {
        List<String> shards = engine.placement().shards();
        Map<String, Set<Region>> out = new LinkedHashMap<>();
        shards.forEach(shard -> out.put(shard, new LinkedHashSet<>()));
        for (Region region : regions) {
            int index = winner(engine.document(), region);
            if (index >= 0) {
                out.get(shards.get(index)).add(region);
            }
        }
        return out;
    }

    /**
     * A shard's extent, whichever geometry decides it.
     *
     * <p>Under {@code ring} it is a set of intervals of the hash space; under {@code directory} it
     * is a set of regions of the prefix trie. The three predicates below are what the
     * classification reads, so nothing above this point depends on which kind is in play.
     */
    private sealed interface Extent permits RingExtent, DirExtent {
    }

    private record RingExtent(List<Segment> segments) implements Extent {
    }

    private record DirExtent(Set<Region> regions) implements Extent {
    }

    private static boolean meetsExtent(Extent first, Extent second) {
        if (first instanceof RingExtent left && second instanceof RingExtent right) {
            return meets(left.segments(), right.segments());
        }
        Set<Region> left = ((DirExtent) first).regions();
        Set<Region> right = ((DirExtent) second).regions();
        return left.stream().anyMatch(right::contains);
    }

    private static boolean containsExtent(Extent outer, Extent inner) {
        if (outer instanceof RingExtent left && inner instanceof RingExtent right) {
            return contains(left.segments(), right.segments());
        }
        return ((DirExtent) outer).regions().containsAll(((DirExtent) inner).regions());
    }

    private static boolean equalExtent(Extent first, Extent second) {
        if (first instanceof RingExtent left && second instanceof RingExtent right) {
            return equal(left.segments(), right.segments());
        }
        return ((DirExtent) first).regions().equals(((DirExtent) second).regions());
    }

    /** The two extent maps, decided by the geometry the strategy kind gives. */
    private static List<Map<String, Extent>> geometry(PlacementEngine before,
                                                      PlacementEngine after) {
        Map<String, Extent> earlier = new LinkedHashMap<>();
        Map<String, Extent> later = new LinkedHashMap<>();
        if ("directory".equals(after.document().strategy().kind())) {
            List<Region> regions = regions(before.document(), after.document());
            directoryExtents(before, regions).forEach((k, v) -> earlier.put(k, new DirExtent(v)));
            directoryExtents(after, regions).forEach((k, v) -> later.put(k, new DirExtent(v)));
        } else {
            ringExtents(before.placement().shards())
                    .forEach((k, v) -> earlier.put(k, new RingExtent(v)));
            ringExtents(after.placement().shards())
                    .forEach((k, v) -> later.put(k, new RingExtent(v)));
        }
        return List.of(earlier, later);
    }

    /**
     * For each shard the later snapshot names, the shards of the earlier one its extent draws from,
     * under {@code LIN-004}.
     */
    public static Map<String, List<String>> parents(PlacementEngine before, PlacementEngine after) {
        List<String> earlier = before.placement().shards();
        List<String> later = after.placement().shards();
        if (earlier.equals(later) || "slot".equals(after.document().strategy().kind())) {
            Map<String, List<String>> identity = new LinkedHashMap<>();
            later.forEach(shard -> identity.put(shard, List.of(shard)));
            return identity;
        }
        List<Map<String, Extent>> extents = geometry(before, after);
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String shard : later) {
            List<String> drawn = new ArrayList<>();
            for (String candidate : earlier) {
                if (meetsExtent(extents.get(0).get(candidate), extents.get(1).get(shard))) {
                    drawn.add(candidate);
                }
            }
            out.put(shard, List.copyOf(drawn));
        }
        return out;
    }

    /**
     * The lineage of two snapshots, in the order {@code LIN-033} fixes.
     *
     * <p>{@code replicaSet} answers a shard's replica set under one snapshot, which separates
     * {@code unchanged} from {@code moved} and is the only thing here that reads placement.
     */
    public static List<Entry> classify(PlacementEngine before, PlacementEngine after,
                                       ReplicaSet replicaSet) {
        Optional<String> differing = OwnershipDelta.incomparable(before.document(),
                after.document());
        if (differing.isPresent()) {
            throw new Refused("incomparableShards", differing.get());
        }
        String kind = after.document().strategy().kind();
        if ("rendezvous".equals(kind)) {
            throw new Refused("strategyUnsupported", "rendezvous enumerates no shard");
        }
        List<String> earlier = before.placement().shards();
        List<String> later = after.placement().shards();
        if ("slot".equals(kind) || earlier.equals(later)) {
            // LIN-007 and LIN-012: an equal shard set is an equal extent set, and TOPO-231 has
            // already refused a slot pair whose slotCount differs.
            return identity(later, before, after, replicaSet);
        }

        List<Map<String, Extent>> extents = geometry(before, after);
        Map<String, Extent> earlierExtents = extents.get(0);
        Map<String, Extent> laterExtents = extents.get(1);
        Set<String> enumeratedLater = new LinkedHashSet<>(later);
        List<Entry> entries = new ArrayList<>();
        for (String shard : later) {
            Extent mine = laterExtents.get(shard);
            List<String> drawn = new ArrayList<>();
            for (String candidate : earlier) {
                Extent theirs = earlierExtents.get(candidate);
                if (!meetsExtent(theirs, mine)) {
                    continue;
                }
                if (!containsExtent(theirs, mine) && !containsExtent(mine, theirs)) {
                    throw new Refused("unalignedLineage", shard);
                }
                drawn.add(candidate);
            }
            if (drawn.isEmpty()) {
                entries.add(new Entry(shard, Lineage.FRESH, List.of()));
            } else if (drawn.size() > 1) {
                entries.add(new Entry(shard, Lineage.MERGED, List.copyOf(drawn)));
            } else if (equalExtent(earlierExtents.get(drawn.get(0)), mine)) {
                entries.add(new Entry(shard, moved(shard, before, after, replicaSet),
                        List.copyOf(drawn)));
            } else {
                entries.add(new Entry(shard, Lineage.DIVIDED, List.copyOf(drawn)));
            }
        }
        for (String shard : earlier) {
            if (enumeratedLater.contains(shard)) {
                continue;
            }
            Extent mine = earlierExtents.get(shard);
            List<String> children = new ArrayList<>();
            for (String candidate : later) {
                if (meetsExtent(laterExtents.get(candidate), mine)) {
                    children.add(candidate);
                }
            }
            Lineage lineage = children.isEmpty() ? Lineage.VACATED
                    : children.size() > 1 ? Lineage.SPLIT : Lineage.FOLDED;
            entries.add(new Entry(shard, lineage, List.copyOf(children)));
        }
        return List.copyOf(entries);
    }

    private static List<Entry> identity(List<String> shards, PlacementEngine before,
                                        PlacementEngine after, ReplicaSet replicaSet) {
        List<Entry> entries = new ArrayList<>();
        for (String shard : shards) {
            entries.add(new Entry(shard, moved(shard, before, after, replicaSet), List.of(shard)));
        }
        return List.copyOf(entries);
    }

    private static Lineage moved(String shard, PlacementEngine before, PlacementEngine after,
                                 ReplicaSet replicaSet) {
        return replicaSet.of(before, shard).equals(replicaSet.of(after, shard))
                ? Lineage.UNCHANGED : Lineage.MOVED;
    }

    /** A shard's replica set under one snapshot, which {@code TOPO-211} defines. */
    @FunctionalInterface
    public interface ReplicaSet {
        List<com.codeheadsystems.sharder.NodeId> of(PlacementEngine engine, String shard);
    }
}
