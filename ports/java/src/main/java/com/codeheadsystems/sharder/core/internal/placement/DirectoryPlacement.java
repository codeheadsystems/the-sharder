package com.codeheadsystems.sharder.core.internal.placement;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.DirectoryEntry;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code directory} strategy of {@code DIR-001} through {@code DIR-022}.
 *
 * <p>The matched entry's node list is the candidate ordering as the document spells it. Nothing
 * reorders it by hash, score, or failure domain, under {@code DIR-005}, and nothing supplies a
 * default entry for a routing key no entry matches, under {@code DIR-011}.
 */
final class DirectoryPlacement implements PreparedPlacement {

    private final List<DirectoryEntry> entries;

    DirectoryPlacement(TopologyDocument document) {
        this.entries = document.strategy().entries();
    }

    @Override
    public boolean eager() {
        return true;
    }

    @Override
    public java.util.Iterator<NodeId> cursor(byte[] routingKey, EligibleSet eligible) {
        return authoredCandidates(routingKey, eligible).iterator();
    }

    /** The authored ordering, which is a list the document spells rather than a walk. */
    private List<NodeId> authoredCandidates(byte[] routingKey, EligibleSet eligible) {
        return matched(routingKey)
                .map(entry -> authored(entry.nodes(), eligible))
                .orElseGet(List::of);
    }

    @Override
    public Optional<String> shardOf(byte[] routingKey) {
        // DIR-020: `<kind>:<base16 of the decoded matcher value>`, and no shard where none matches.
        return matched(routingKey).map(DirectoryPlacement::render);
    }

    @Override
    public List<String> shards() {
        // DIR-021: the entries in array order.
        return entries.stream().map(DirectoryPlacement::render).toList();
    }

    @Override
    public java.util.Iterator<NodeId> cursorForShard(String shard, EligibleSet eligible) {
        return shardCandidates(shard, eligible).iterator();
    }

    private List<NodeId> shardCandidates(String shard, EligibleSet eligible) {
        return entries.stream()
                .filter(entry -> render(entry).equals(shard))
                .findFirst()
                .map(entry -> authored(entry.nodes(), eligible))
                .orElseGet(List::of);
    }

    @Override
    public String emptyCause(byte[] routingKey, EligibleSet eligible) {
        // ERR-021 evaluates the unmatched routing key before the excluded node list.
        return matched(routingKey).isPresent() ? "authoredListExcludedAll" : "noDirectoryEntry";
    }

    private Optional<DirectoryEntry> matched(byte[] routingKey) {
        return Matchers.matched(entries, DirectoryEntry::match, routingKey);
    }

    private static String render(DirectoryEntry entry) {
        return entry.match().kind() + ":" + HexFormat.of().formatHex(entry.match().value());
    }

    private static List<NodeId> authored(List<NodeId> nodes, EligibleSet eligible) {
        Set<NodeId> ordering = new LinkedHashSet<>();
        nodes.stream().filter(eligible::contains).forEach(ordering::add);
        return List.copyOf(ordering);
    }
}
