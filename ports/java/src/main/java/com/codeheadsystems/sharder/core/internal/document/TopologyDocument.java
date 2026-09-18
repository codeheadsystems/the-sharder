package com.codeheadsystems.sharder.core.internal.document;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.json.JsonValue;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A topology document, read into the shapes placement works over.
 *
 * <p>This is the document of {@code 20-topology-format.md} as the placement engine needs it, and
 * not the load pipeline of {@code TOPO-001}: nothing here validates, canonicalises, or digests.
 * A document reaching this class has been validated elsewhere, which at the {@code place}
 * conformance level is the suite's own guarantee that a file carries its documents already valid.
 *
 * <p>Every default of the format is applied here rather than at the point of use, so that a
 * document omitting a member and one writing its default out are the same object, which is what
 * {@code PLACE-040}, {@code KEY-010}, {@code REPL-001}, and {@code RING-006} each require.
 */
public record TopologyDocument(
        String topologyId,
        long epoch,
        byte[] hashSeed,
        KeyTransformSpec keyTransform,
        StrategySpec strategy,
        List<String> domainLevels,
        ReplicationSpec replication,
        List<Node> nodes,
        List<OverrideEntry> overrides) {

    /** The administrative state of a node, of which two are in the placement set. */
    public enum State {
        /** Placeable and serving. */
        ACTIVE("active", true),
        /** Placeable and serving, with its shards moving away at a later epoch. */
        DRAINING("draining", true),
        /** Not placeable. */
        JOINING("joining", false),
        /** Not placeable. */
        LEAVING("leaving", false);

        private final String spelling;
        private final boolean placeable;

        State(String spelling, boolean placeable) {
            this.spelling = spelling;
            this.placeable = placeable;
        }

        /** Whether {@code PLACE-001} admits a node in this state to the placement set. */
        public boolean placeable() {
            return placeable;
        }

        /** The state the document spells. */
        public static State of(String spelling) {
            for (State state : values()) {
                if (state.spelling.equals(spelling)) {
                    return state;
                }
            }
            throw new IllegalArgumentException("no administrative state named " + spelling);
        }
    }

    /** One node of the document, with every default applied. */
    public record Node(NodeId id, State state, int weight, Map<String, String> domains,
                       Map<String, String> tags, List<Long> tokens) {
    }

    /** The key transform of {@code KEY-010}, defaulting to {@code none}. */
    public record KeyTransformSpec(String kind, byte open, byte close, byte separator, int count) {

        /** The transform a document with no {@code keyTransform} member carries. */
        public static KeyTransformSpec none() {
            return new KeyTransformSpec("none", (byte) 0, (byte) 0, (byte) 0, 0);
        }
    }

    /** The strategy object, carrying the members of whichever kind it names. */
    public record StrategySpec(String kind, String tokenAssignment, int tokensPerWeightUnit,
                               int maxTokensPerNode, int virtualNodesPerWeightUnit,
                               int maxVirtualNodesPerNode, int slotCount,
                               List<SlotAssignment> assignments, List<DirectoryEntry> entries) {
    }

    /** One entry of a {@code slot} strategy's {@code assignments} array. */
    public record SlotAssignment(List<SlotRange> slots, List<NodeId> nodes) {
    }

    /** One inclusive slot range, which a single index spells as its own low and high. */
    public record SlotRange(long low, long high) {

        /** Whether the range covers {@code index}. */
        public boolean covers(long index) {
            return index >= low && index <= high;
        }
    }

    /** One entry of a {@code directory} strategy's {@code entries} array. */
    public record DirectoryEntry(Matcher match, List<NodeId> nodes) {
    }

    /** A matcher of {@code PLACE-060}, with its value already decoded to octets. */
    public record Matcher(String kind, byte[] value) {
    }

    /** One entry of the {@code overrides} array. */
    public record OverrideEntry(int index, Matcher match, List<NodeId> pin, Constraint constrain,
                                OptionalInt factor) {
    }

    /** The {@code constrain} member of an override entry, whose members intersect. */
    public record Constraint(Map<String, List<String>> domains, Map<String, List<String>> tags,
                             List<NodeId> nodes) {
    }

    /** The {@code replication} member, defaulting to factor 1 with no spread. */
    public record ReplicationSpec(int factor, List<String> spread, String spreadPolicy) {

        /** The replication a document with no {@code replication} member carries. */
        public static ReplicationSpec defaults() {
            return new ReplicationSpec(1, List.of(), "relaxed");
        }
    }

    /** The document the JSON object spells, with every default applied. */
    public static TopologyDocument parse(JsonObject root) {
        return new TopologyDocument(
                root.text("topologyId"),
                root.get("epoch").asLong(),
                hashSeed(root),
                keyTransform(root),
                strategy(root),
                strings(root, "domainLevels"),
                replication(root),
                nodes(root),
                overrides(root));
    }

    private static byte[] hashSeed(JsonObject root) {
        // HASH-011: a document with no `hash` member, or none with a `seed`, takes sixteen zeroes.
        return root.find("hash")
                .map(JsonValue::asObject)
                .flatMap(hash -> hash.find("seed"))
                .map(seed -> java.util.HexFormat.of().parseHex(seed.asText()))
                .orElseGet(() -> new byte[16]);
    }

    private static KeyTransformSpec keyTransform(JsonObject root) {
        Optional<JsonObject> transform = root.find("keyTransform").map(JsonValue::asObject);
        if (transform.isEmpty()) {
            return KeyTransformSpec.none();
        }
        JsonObject spec = transform.get();
        String kind = spec.text("kind");
        return new KeyTransformSpec(
                kind,
                octet(spec, "open", (byte) 0x7b),
                octet(spec, "close", (byte) 0x7d),
                octet(spec, "separator", (byte) 0),
                spec.find("count").map(JsonValue::asInt).orElse(1));
    }

    private static byte octet(JsonObject spec, String member, byte fallback) {
        return spec.find(member)
                .map(value -> java.util.HexFormat.of().parseHex(value.asText())[0])
                .orElse(fallback);
    }

    private static StrategySpec strategy(JsonObject root) {
        JsonObject spec = root.object("strategy");
        String kind = spec.text("kind");
        List<SlotAssignment> assignments = new ArrayList<>();
        for (JsonValue entry : spec.find("assignments")
                .map(JsonValue::asArray).map(JsonValue.JsonArray::elements).orElse(List.of())) {
            JsonObject object = entry.asObject();
            List<SlotRange> ranges = new ArrayList<>();
            for (String range : object.array("slots").texts()) {
                int dash = range.indexOf('-');
                ranges.add(dash < 0
                        ? new SlotRange(Long.parseLong(range), Long.parseLong(range))
                        : new SlotRange(Long.parseLong(range.substring(0, dash)),
                                Long.parseLong(range.substring(dash + 1))));
            }
            assignments.add(new SlotAssignment(ranges, identities(object.array("nodes"))));
        }
        List<DirectoryEntry> entries = new ArrayList<>();
        for (JsonValue entry : spec.find("entries")
                .map(JsonValue::asArray).map(JsonValue.JsonArray::elements).orElse(List.of())) {
            JsonObject object = entry.asObject();
            entries.add(new DirectoryEntry(matcher(object.object("match")),
                    identities(object.array("nodes"))));
        }
        return new StrategySpec(
                kind,
                // RING-006: a `ring` object omitting the mode is `derived`, and the mode is never
                // inferred from the members the document carries.
                spec.find("tokenAssignment").map(JsonValue::asText).orElse("derived"),
                spec.find("tokensPerWeightUnit").map(JsonValue::asInt).orElse(4),
                spec.find("maxTokensPerNode").map(JsonValue::asInt).orElse(4096),
                spec.find("virtualNodesPerWeightUnit").map(JsonValue::asInt).orElse(1),
                spec.find("maxVirtualNodesPerNode").map(JsonValue::asInt).orElse(1024),
                spec.find("slotCount").map(JsonValue::asInt).orElse(0),
                List.copyOf(assignments),
                List.copyOf(entries));
    }

    private static ReplicationSpec replication(JsonObject root) {
        Optional<JsonObject> spec = root.find("replication").map(JsonValue::asObject);
        if (spec.isEmpty()) {
            return ReplicationSpec.defaults();
        }
        JsonObject replication = spec.get();
        return new ReplicationSpec(
                replication.find("factor").map(JsonValue::asInt).orElse(1),
                replication.find("spread").map(JsonValue::asArray)
                        .map(JsonValue.JsonArray::texts).orElse(List.of()),
                replication.find("spreadPolicy").map(JsonValue::asText).orElse("relaxed"));
    }

    private static List<Node> nodes(JsonObject root) {
        List<Node> nodes = new ArrayList<>();
        for (JsonValue entry : root.array("nodes").elements()) {
            JsonObject object = entry.asObject();
            Map<String, String> domains = new LinkedHashMap<>();
            object.find("domains").map(JsonValue::asObject).ifPresent(value ->
                    value.members().forEach((level, identifier) ->
                            domains.put(level, identifier.asText())));
            Map<String, String> tags = new LinkedHashMap<>();
            object.find("tags").map(JsonValue::asObject).ifPresent(value ->
                    value.members().forEach((key, tag) -> tags.put(key, tag.asText())));
            List<Long> tokens = new ArrayList<>();
            object.find("tokens").map(JsonValue::asArray).ifPresent(value ->
                    value.texts().forEach(token ->
                            tokens.add(Long.parseUnsignedLong(token, 16))));
            nodes.add(new Node(
                    NodeId.of(object.text("id")),
                    State.of(object.find("state").map(JsonValue::asText).orElse("active")),
                    object.find("weight").map(JsonValue::asInt).orElse(1),
                    Map.copyOf(domains),
                    Map.copyOf(tags),
                    List.copyOf(tokens)));
        }
        return List.copyOf(nodes);
    }

    private static List<OverrideEntry> overrides(JsonObject root) {
        List<OverrideEntry> overrides = new ArrayList<>();
        List<JsonValue> entries = root.find("overrides").map(JsonValue::asArray)
                .map(JsonValue.JsonArray::elements).orElse(List.of());
        for (int index = 0; index < entries.size(); index++) {
            JsonObject object = entries.get(index).asObject();
            overrides.add(new OverrideEntry(
                    index,
                    matcher(object.object("match")),
                    object.find("pin").map(JsonValue::asArray)
                            .map(TopologyDocument::identities).orElse(null),
                    object.find("constrain").map(JsonValue::asObject)
                            .map(TopologyDocument::constraint).orElse(null),
                    object.find("factor")
                            .map(value -> OptionalInt.of(value.asInt()))
                            .orElseGet(OptionalInt::empty)));
        }
        return List.copyOf(overrides);
    }

    private static Constraint constraint(JsonObject spec) {
        Map<String, List<String>> domains = new LinkedHashMap<>();
        spec.find("domains").map(JsonValue::asObject).ifPresent(value ->
                value.members().forEach((level, identifiers) ->
                        domains.put(level, identifiers.asArray().texts())));
        Map<String, List<String>> tags = new LinkedHashMap<>();
        spec.find("tags").map(JsonValue::asObject).ifPresent(value ->
                value.members().forEach((key, values) ->
                        tags.put(key, values.asArray().texts())));
        List<NodeId> nodes = spec.find("nodes").map(JsonValue::asArray)
                .map(TopologyDocument::identities).orElse(null);
        return new Constraint(Map.copyOf(domains), Map.copyOf(tags), nodes);
    }

    private static Matcher matcher(JsonObject spec) {
        String kind = spec.text("kind");
        String encoding = spec.find("encoding").map(JsonValue::asText).orElse("utf8");
        String value = spec.text("value");
        byte[] octets = "base16".equals(encoding)
                ? java.util.HexFormat.of().parseHex(value)
                : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new Matcher(kind, octets);
    }

    private static List<NodeId> identities(JsonValue.JsonArray array) {
        return array.texts().stream().map(NodeId::of).toList();
    }

    private static List<String> strings(JsonObject root, String member) {
        return root.find(member).map(JsonValue::asArray)
                .map(JsonValue.JsonArray::texts).orElse(List.of());
    }

    /** The nodes of {@code PLACE-001}: those whose administrative state is placeable. */
    public List<Node> placementSet() {
        return nodes.stream().filter(node -> node.state().placeable()).toList();
    }

    /** The node of that identity, where the document carries one. */
    public Optional<Node> node(NodeId id) {
        return nodes.stream().filter(node -> node.id().equals(id)).findFirst();
    }
}
