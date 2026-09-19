package com.codeheadsystems.sharder.core.internal.route;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.Node;
import com.codeheadsystems.sharder.error.InvalidArgumentException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Read affinity, under {@code READ-010} through {@code READ-023}.
 *
 * <p>Reads and writes share one preference list. Affinity is a separate call that reorders within
 * the replica prefix and leaves ownership untouched: it moves no entry across the boundary between
 * the prefix and the fallback tail, changes neither the achieved replica count nor the shard nor
 * the fencing token, and reads no health state.
 */
public final class ReadAffinity {

    /** The affinity a caller requests, under {@code READ-010}. */
    public record Request(String level, List<String> path, OptionalInt window) {
    }

    private ReadAffinity() {
    }

    /**
     * The reordered list of {@code READ-013}: a stable partition of the first {@code window}
     * entries into those whose domain path at the level agrees with the requested path, then the
     * rest, each group keeping preference list order.
     *
     * <p>Where no replica shares the path the answer equals the preference list, under
     * {@code READ-023}: an absent local replica is not an error.
     */
    public static List<NodeId> reorder(TopologyDocument document, List<NodeId> preferenceList,
                                       int replicaCount, Request request) {
        int through = document.domainLevels().indexOf(request.level());
        if (through < 0) {
            // READ-011: an undeclared level is refused rather than answered without affinity.
            throw new InvalidArgumentException(
                    "the affinity level " + request.level() + " is not declared");
        }
        if (request.path().size() != through + 1) {
            // READ-017: a path one level short agrees with no domain path, which is the answer a
            // cluster holding no local replica gets, so the request is refused rather than
            // answered indistinguishably.
            throw new InvalidArgumentException("the affinity path holds " + request.path().size()
                    + " identifiers where the level takes " + (through + 1));
        }

        // READ-012: the window is clamped to the replica prefix length, and takes it where the
        // request names none.
        int window = Math.min(request.window().orElse(replicaCount), replicaCount);
        List<NodeId> local = new ArrayList<>();
        List<NodeId> remote = new ArrayList<>();
        for (int position = 0; position < window; position++) {
            NodeId node = preferenceList.get(position);
            if (agrees(document, node, through, request.path())) {
                local.add(node);
            } else {
                remote.add(node);
            }
        }
        List<NodeId> ordered = new ArrayList<>(preferenceList.size());
        ordered.addAll(local);
        ordered.addAll(remote);
        // READ-014: an entry at or above the window is unchanged in identity and in position.
        ordered.addAll(preferenceList.subList(window, preferenceList.size()));
        return List.copyOf(ordered);
    }

    /** Whether a node's domain path through the level agrees with the requested path. */
    private static boolean agrees(TopologyDocument document, NodeId node, int through,
                                  List<String> path) {
        Optional<Node> held = document.node(node);
        if (held.isEmpty()) {
            return false;
        }
        for (int index = 0; index <= through; index++) {
            String identifier = held.get().domains().get(document.domainLevels().get(index));
            if (identifier == null || !identifier.equals(path.get(index))) {
                return false;
            }
        }
        return true;
    }
}
