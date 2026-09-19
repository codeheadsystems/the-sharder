package com.codeheadsystems.sharder.core.internal.document;

import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import com.codeheadsystems.sharder.core.internal.json.JsonValue;
import com.codeheadsystems.sharder.topology.ValidationError;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The validation rules of {@code 20-topology-format.md}, which stage 3 of {@code TOPO-001} applies.
 *
 * <p>Schema validation is necessary and not sufficient, and this class carries both halves: the
 * rules a schema expresses and the rules it cannot. A document that breaks any of them is rejected
 * whole, under {@code TOPO-011}: nothing here repairs a document, drops an offending node, or
 * merges a partially valid document into the snapshot in force.
 *
 * <p>Every error is reported rather than the first, because an authority correcting a document
 * wants the whole list.
 */
public final class DocumentValidator {

    private static final String SUPPORTED_VERSION = "1.0";
    private static final String SUPPORTED_ALGORITHM = "siphash-2-4";
    private static final Pattern SEED = Pattern.compile("[0-9a-f]{32}");

    private DocumentValidator() {
    }

    /** The members a document carries under every rule below, which the schema also requires. */
    private static final List<String> REQUIRED =
            List.of("formatVersion", "topologyId", "epoch", "strategy", "nodes");

    /** Every rule the document breaks, in the order the rules are stated. */
    public static List<ValidationError> validate(JsonObject root) {
        List<ValidationError> errors = new ArrayList<>();
        // A rule below reads each of these, so a document missing one is refused here rather than
        // read: an absent member is a validation error the operator repairs, not a failure of the
        // reader.
        REQUIRED.forEach(member -> {
            if (root.find(member).isEmpty()) {
                errors.add(new ValidationError(member, "missingMember", "the member is absent"));
            }
        });
        if (!errors.isEmpty()) {
            return List.copyOf(errors);
        }
        version(root, errors);
        hash(root, errors);
        List<String> levels = levels(root);
        nodes(root, levels, errors);
        spread(root, levels, errors);
        strategy(root, errors);
        overrides(root, levels, errors);
        return List.copyOf(errors);
    }

    private static void version(JsonObject root, List<ValidationError> errors) {
        String version = root.text("formatVersion");
        if (!SUPPORTED_VERSION.equals(version)) {
            // A reader refuses a major it does not support, and a minor above the one it supports.
            errors.add(new ValidationError("formatVersion", "unsupportedVersion", version));
        }
    }

    private static void hash(JsonObject root, List<ValidationError> errors) {
        Optional<JsonObject> hash = root.find("hash").map(JsonValue::asObject);
        if (hash.isEmpty()) {
            return;
        }
        hash.get().find("algorithm").map(JsonValue::asText).ifPresent(algorithm -> {
            if (!SUPPORTED_ALGORITHM.equals(algorithm)) {
                errors.add(new ValidationError(
                        "hash.algorithm", "unsupportedAlgorithm", algorithm));
            }
        });
        hash.get().find("seed").map(JsonValue::asText).ifPresent(seed -> {
            if (!SEED.matcher(seed).matches()) {
                errors.add(new ValidationError("hash.seed", "malformedSeed", seed));
            }
        });
    }

    private static List<String> levels(JsonObject root) {
        return root.find("domainLevels").map(JsonValue::asArray)
                .map(JsonValue.JsonArray::texts).orElse(List.of());
    }

    private static void nodes(JsonObject root, List<String> levels, List<ValidationError> errors) {
        Set<String> seen = new LinkedHashSet<>();
        List<JsonValue> nodes = root.array("nodes").elements();
        for (int index = 0; index < nodes.size(); index++) {
            JsonObject node = nodes.get(index).asObject();
            String id = node.text("id");
            if (!seen.add(id)) {
                errors.add(new ValidationError("nodes[" + index + "].id", "duplicateNodeId", id));
            }
            domains(node, index, levels, errors);
        }
    }

    /** Every node carries exactly one domain entry for each declared level, and no others. */
    private static void domains(JsonObject node, int index, List<String> levels,
                                List<ValidationError> errors) {
        Map<String, JsonValue> carried = node.find("domains").map(JsonValue::asObject)
                .map(JsonObject::members).orElse(Map.of());
        String path = "nodes[" + index + "].domains";
        for (String level : levels) {
            if (!carried.containsKey(level)) {
                errors.add(new ValidationError(path, "missingLevel", level));
            }
        }
        for (String level : carried.keySet()) {
            if (!levels.contains(level)) {
                errors.add(new ValidationError(path, "undeclaredLevel", level));
            }
        }
    }

    /** The spread names declared levels alone, in the relative order the levels are declared in. */
    private static void spread(JsonObject root, List<String> levels,
                               List<ValidationError> errors) {
        List<String> spread = root.find("replication").map(JsonValue::asObject)
                .flatMap(replication -> replication.find("spread"))
                .map(JsonValue::asArray).map(JsonValue.JsonArray::texts).orElse(List.of());
        if (spread.isEmpty()) {
            return;
        }
        List<Integer> positions = new ArrayList<>();
        for (String level : spread) {
            int at = levels.indexOf(level);
            if (at < 0) {
                errors.add(new ValidationError("replication.spread", "undeclaredLevel", level));
            } else {
                positions.add(at);
            }
        }
        for (int index = 1; index < positions.size(); index++) {
            if (positions.get(index) <= positions.get(index - 1)) {
                errors.add(new ValidationError("replication.spread", "levelOrder",
                        String.join(",", spread)));
                return;
            }
        }
    }

    private static void strategy(JsonObject root, List<ValidationError> errors) {
        JsonObject strategy = root.object("strategy");
        Set<String> identities = identities(root);
        switch (strategy.text("kind")) {
            case "ring" -> ring(root, strategy, errors);
            case "slot" -> slot(strategy, identities, errors);
            case "directory" -> directory(strategy, identities, errors);
            default -> {
                // `rendezvous` carries no authored list, so it carries no rule of its own here.
            }
        }
    }

    private static void ring(JsonObject root, JsonObject strategy, List<ValidationError> errors) {
        boolean derived = !"explicit".equals(strategy.find("tokenAssignment")
                .map(JsonValue::asText).orElse("derived"));
        Map<String, Integer> tokens = new HashMap<>();
        List<JsonValue> nodes = root.array("nodes").elements();
        for (int index = 0; index < nodes.size(); index++) {
            JsonObject node = nodes.get(index).asObject();
            String path = "nodes[" + index + "].tokens";
            List<String> carried = node.find("tokens").map(JsonValue::asArray)
                    .map(JsonValue.JsonArray::texts).orElse(null);
            if (derived) {
                if (carried != null) {
                    errors.add(new ValidationError(path, "tokensUnderDerived", node.text("id")));
                }
                continue;
            }
            boolean placeable = placeable(node);
            int weight = node.find("weight").map(JsonValue::asInt).orElse(1);
            if ((carried == null || carried.isEmpty()) && placeable && weight != 0) {
                errors.add(new ValidationError(path, "missingTokens", node.text("id")));
                continue;
            }
            if (carried == null) {
                continue;
            }
            for (String token : carried) {
                if (tokens.put(token, index) != null) {
                    errors.add(new ValidationError(path, "duplicateToken", token));
                }
            }
        }
    }

    /** Every slot index from 0 to {@code slotCount - 1} is covered exactly once. */
    private static void slot(JsonObject strategy, Set<String> identities,
                             List<ValidationError> errors) {
        int slotCount = strategy.get("slotCount").asInt();
        Map<Long, Integer> covered = new HashMap<>();
        List<JsonValue> assignments = strategy.find("assignments").map(JsonValue::asArray)
                .map(JsonValue.JsonArray::elements).orElse(List.of());
        for (int index = 0; index < assignments.size(); index++) {
            JsonObject entry = assignments.get(index).asObject();
            for (String range : entry.array("slots").texts()) {
                int dash = range.indexOf('-');
                long low = Long.parseLong(dash < 0 ? range : range.substring(0, dash));
                long high = dash < 0 ? low : Long.parseLong(range.substring(dash + 1));
                for (long slot = low; slot <= high; slot++) {
                    if (covered.put(slot, index) != null) {
                        errors.add(new ValidationError("strategy.assignments[" + index + "].slots",
                                "slotCoveredTwice", Long.toString(slot)));
                    }
                }
            }
            references(entry.array("nodes").texts(), identities,
                    "strategy.assignments[" + index + "].nodes", errors);
        }
        List<Long> missing = new ArrayList<>();
        for (long slot = 0; slot < slotCount; slot++) {
            if (!covered.containsKey(slot)) {
                missing.add(slot);
            }
        }
        if (!missing.isEmpty()) {
            errors.add(new ValidationError("strategy.assignments", "slotNotCovered",
                    missing.size() + " slots, first " + missing.get(0)));
        }
    }

    private static void directory(JsonObject strategy, Set<String> identities,
                                  List<ValidationError> errors) {
        List<JsonValue> entries = strategy.find("entries").map(JsonValue::asArray)
                .map(JsonValue.JsonArray::elements).orElse(List.of());
        Set<String> matchers = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            JsonObject entry = entries.get(index).asObject();
            matcher(entry.object("match"), matchers,
                    "strategy.entries[" + index + "].match", errors);
            references(entry.array("nodes").texts(), identities,
                    "strategy.entries[" + index + "].nodes", errors);
        }
    }

    private static void overrides(JsonObject root, List<String> levels,
                                  List<ValidationError> errors) {
        Set<String> identities = identities(root);
        Set<String> matchers = new HashSet<>();
        List<JsonValue> entries = root.find("overrides").map(JsonValue::asArray)
                .map(JsonValue.JsonArray::elements).orElse(List.of());
        for (int index = 0; index < entries.size(); index++) {
            JsonObject entry = entries.get(index).asObject();
            String at = "overrides[" + index + "]";
            matcher(entry.object("match"), matchers, at + ".match", errors);
            Optional<JsonValue.JsonArray> pin = entry.find("pin").map(JsonValue::asArray);
            if (pin.isPresent()) {
                references(pin.get().texts(), identities, at + ".pin", errors);
            }
            Optional<JsonObject> constrain = entry.find("constrain").map(JsonValue::asObject);
            if (constrain.isEmpty()) {
                continue;
            }
            Optional<JsonObject> domains = constrain.get().find("domains").map(JsonValue::asObject);
            if (domains.isPresent()) {
                for (String level : domains.get().members().keySet()) {
                    if (!levels.contains(level)) {
                        errors.add(new ValidationError(at + ".constrain.domains",
                                "undeclaredLevel", level));
                    }
                }
            }
            Optional<JsonValue.JsonArray> nodes =
                    constrain.get().find("nodes").map(JsonValue::asArray);
            if (nodes.isPresent()) {
                references(nodes.get().texts(), identities, at + ".constrain.nodes", errors);
            }
        }
    }

    /**
     * No two matchers of one table are identical, compared over the decoded octets and the kind.
     *
     * <p>The detail carries the value the later entry spells, which is the encoding that entry
     * used: two entries colliding across encodings are two spellings of one octet sequence.
     */
    private static void matcher(JsonObject match, Set<String> seen, String path,
                                List<ValidationError> errors) {
        String encoding = match.find("encoding").map(JsonValue::asText).orElse("utf8");
        String value = match.text("value");
        byte[] octets = "base16".equals(encoding)
                ? HexFormat.of().parseHex(value)
                : value.getBytes(StandardCharsets.UTF_8);
        String identity = match.text("kind") + ":" + HexFormat.of().formatHex(octets);
        if (!seen.add(identity)) {
            errors.add(new ValidationError(path, "duplicateMatcher", value));
        }
    }

    private static void references(List<String> named, Set<String> identities, String path,
                                   List<ValidationError> errors) {
        for (String id : named) {
            if (!identities.contains(id)) {
                errors.add(new ValidationError(path, "unknownNodeId", id));
            }
        }
    }

    private static Set<String> identities(JsonObject root) {
        Set<String> identities = new LinkedHashSet<>();
        root.array("nodes").elements()
                .forEach(node -> identities.add(node.asObject().text("id")));
        return identities;
    }

    private static boolean placeable(JsonObject node) {
        String state = node.find("state").map(JsonValue::asText).orElse("active");
        return "active".equals(state) || "draining".equals(state);
    }
}
