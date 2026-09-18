package com.codeheadsystems.sharder.core.internal.route;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.Node;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The replica prefix, the spread requirement, and the relaxation ladder of {@code REPL-012},
 * {@code SPREAD-010}, and {@code SPREAD-011}.
 *
 * <p>A stage enforces node distinctness together with the finest levels of the spread list, and the
 * builder takes the smallest stage that fills the replica prefix. Distinctness is enforced
 * unconditionally and no stage relaxes it, under {@code SPREAD-002}.
 */
public final class SpreadLadder {

    /** One stage of the ladder, with what it enforces and what it selected. */
    public record Stage(int index, List<String> enforces, List<String> relaxes,
                        List<NodeId> selected, boolean reachesFactor) {
    }

    private final List<String> domainLevels;
    private final List<String> spread;
    private final boolean strict;
    private final Map<NodeId, List<String>> paths;

    /** The ladder a document's replication policy and domain levels describe. */
    public SpreadLadder(TopologyDocument document) {
        this.domainLevels = document.domainLevels();
        this.spread = document.replication().spread();
        this.strict = "strict".equals(document.replication().spreadPolicy());
        Map<NodeId, List<String>> paths = new LinkedHashMap<>();
        for (Node node : document.nodes()) {
            List<String> identifiers = new ArrayList<>(domainLevels.size());
            for (String level : domainLevels) {
                identifiers.add(node.domains().get(level));
            }
            paths.put(node.id(), List.copyOf(identifiers));
        }
        this.paths = Map.copyOf(paths);
    }

    /** The count of levels the spread list names, which is the number of stages less one. */
    public int levelCount() {
        return spread.size();
    }

    /** Whether the policy is {@code strict}, which evaluates stage 0 alone, under
     * {@code SPREAD-014}. */
    public boolean strict() {
        return strict;
    }

    /** Every stage of the ladder over one candidate ordering, in ascending order. */
    public List<Stage> stages(List<NodeId> candidates, int factor) {
        List<Stage> stages = new ArrayList<>();
        for (int index = 0; index <= spread.size(); index++) {
            List<String> enforced = spread.subList(index, spread.size());
            List<NodeId> selected = select(candidates, factor, enforced);
            stages.add(new Stage(index, List.copyOf(enforced),
                    List.copyOf(spread.subList(0, index)), selected, selected.size() == factor));
        }
        return List.copyOf(stages);
    }

    /**
     * The stage the policy chooses: stage 0 under {@code strict}, and under {@code relaxed} the
     * smallest stage filling the replica prefix, falling back to the last stage under
     * {@code SPREAD-013}.
     */
    public Stage chosen(List<NodeId> candidates, int factor) {
        List<Stage> stages = stages(candidates, factor);
        if (strict) {
            return stages.get(0);
        }
        for (Stage stage : stages) {
            if (stage.reachesFactor()) {
                return stage;
            }
        }
        return stages.get(stages.size() - 1);
    }

    /**
     * {@code SPREAD-011}: one greedy forward pass over the candidate ordering, admitting each entry
     * that no enforced level has already filled to its occupancy cap, halting at the factor.
     *
     * <p>The cap is 1 at every level under {@code SPREAD-007}, so an entry is refused exactly where
     * the prefix already holds one sharing a failure domain with it at an enforced level.
     */
    private List<NodeId> select(List<NodeId> candidates, int factor, List<String> levels) {
        List<NodeId> prefix = new ArrayList<>(factor);
        for (NodeId candidate : candidates) {
            if (prefix.contains(candidate) || exceedsCap(candidate, prefix, levels)) {
                continue;
            }
            prefix.add(candidate);
            if (prefix.size() == factor) {
                break;
            }
        }
        return List.copyOf(prefix);
    }

    private boolean exceedsCap(NodeId candidate, List<NodeId> prefix, List<String> levels) {
        for (String level : levels) {
            List<String> path = domainPath(candidate, level);
            for (NodeId admitted : prefix) {
                if (domainPath(admitted, level).equals(path)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code SPREAD-006}: the node's domain identifiers from the coarsest declared level through
     * {@code level}, in {@code domainLevels} order.
     *
     * <p>The span is {@code domainLevels} and never the spread list, so a spread of one rack level
     * over three declared levels compares the whole triple rather than the rack identifier alone.
     */
    private List<String> domainPath(NodeId id, String level) {
        List<String> identifiers = paths.get(id);
        int through = domainLevels.indexOf(level);
        return identifiers.subList(0, through + 1);
    }
}
