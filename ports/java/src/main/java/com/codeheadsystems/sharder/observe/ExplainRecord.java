package com.codeheadsystems.sharder.observe;

import com.codeheadsystems.sharder.Digest;
import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.PreferenceEntry;
import com.codeheadsystems.sharder.RoutingKey;
import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.Shortfall;
import com.codeheadsystems.sharder.core.internal.json.JcsWriter;
import com.codeheadsystems.sharder.core.internal.json.JsonValue;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonArray;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonNumber;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonString;
import com.codeheadsystems.sharder.topology.FencingToken;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The account of how the snapshot in force routes one key, under {@code OBS-040}.
 *
 * <p>The record names every eligible node and every exclusion, so its cost grows with the node set
 * and {@code OBS-046} keeps it off the routing path: {@code route} computes one only where the
 * caller asked. Computing one changes no state, under {@code OBS-045}, and it reports the same
 * routing key, override, shard, candidate ordering, and preference list that {@code route} reports
 * for the same key against the same snapshot, under {@code OBS-044}.
 */
public record ExplainRecord(
        byte[] key,
        RoutingKey routingKey,
        KeyTransform keyTransform,
        FencingToken token,
        Digest digest,
        String strategy,
        Optional<OverrideNote> matchedOverride,
        Optional<ShardId> shard,
        List<StrategyInput> strategyInputs,
        List<NodeId> eligible,
        List<NodeId> candidates,
        List<PreferenceEntry> preferenceList,
        List<Exclusion> exclusions,
        List<String> relaxedLevels,
        Optional<ShortfallNote> shortfall) {

    /** The key transform the document applied, with the members its kind carries. */
    public record KeyTransform(String kind, Map<String, String> fields) {

        /** The fields are copied. */
        public KeyTransform {
            fields = Map.copyOf(fields);
        }
    }

    /** One arithmetic input the strategy performed, under {@code OBS-042}. */
    public record StrategyInput(String name, String value) {
    }

    /** The override entry the routing key matched. */
    public record OverrideNote(int index, String matcherKind, byte[] matcherValue, String mode) {

        /** The matcher value is copied on the way in and on the way out. */
        public OverrideNote {
            matcherValue = matcherValue.clone();
        }

        @Override
        public byte[] matcherValue() {
            return matcherValue.clone();
        }
    }

    /** The replication shortfall, where the list is shorter than the factor. */
    public record ShortfallNote(Shortfall cause, int factor, int achieved) {
    }

    /** Every collection is copied, and the key is copied on the way in and on the way out. */
    public ExplainRecord {
        key = key.clone();
        strategyInputs = List.copyOf(strategyInputs);
        eligible = List.copyOf(eligible);
        candidates = List.copyOf(candidates);
        preferenceList = List.copyOf(preferenceList);
        exclusions = List.copyOf(exclusions);
        relaxedLevels = List.copyOf(relaxedLevels);
    }

    @Override
    public byte[] key() {
        return key.clone();
    }

    /**
     * The record as a JSON object whose member names are those of {@code OBS-041}, under
     * {@code OBS-047}.
     *
     * <p>Octet-valued fields are lowercase hexadecimal, and the object is written through the
     * canonical writer, which is how the serialisation is met without a JSON dependency.
     */
    public String toJson() {
        Map<String, JsonValue> members = new LinkedHashMap<>();
        members.put("key", hex(key));
        members.put("routingKey", hex(routingKey.toBytes()));
        Map<String, JsonValue> transform = new LinkedHashMap<>();
        transform.put("kind", new JsonString(keyTransform.kind()));
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        keyTransform.fields().forEach((name, value) -> fields.put(name, new JsonString(value)));
        transform.put("fields", new JsonObject(fields));
        members.put("keyTransform", new JsonObject(transform));
        Map<String, JsonValue> tokenMembers = new LinkedHashMap<>();
        tokenMembers.put("topologyId", new JsonString(token.topologyId()));
        tokenMembers.put("epoch", new JsonNumber(Long.toString(token.epoch())));
        members.put("token", new JsonObject(tokenMembers));
        members.put("digest", new JsonString(digest.toHex()));
        members.put("strategy", new JsonString(strategy));
        members.put("matchedOverride", matchedOverride.<JsonValue>map(note -> {
            Map<String, JsonValue> override = new LinkedHashMap<>();
            override.put("index", new JsonNumber(Integer.toString(note.index())));
            override.put("matcherKind", new JsonString(note.matcherKind()));
            override.put("matcherValue", hex(note.matcherValue()));
            override.put("mode", new JsonString(note.mode()));
            return new JsonObject(override);
        }).orElseGet(JsonValue.JsonNull::new));
        members.put("shard", shard.<JsonValue>map(identifier -> new JsonString(identifier.asText()))
                .orElseGet(JsonValue.JsonNull::new));
        List<JsonValue> inputs = new ArrayList<>();
        strategyInputs.forEach(input -> {
            Map<String, JsonValue> entry = new LinkedHashMap<>();
            entry.put("name", new JsonString(input.name()));
            entry.put("value", new JsonString(input.value()));
            inputs.add(new JsonObject(entry));
        });
        members.put("strategyInputs", new JsonArray(inputs));
        members.put("eligible", identities(eligible));
        members.put("candidates", identities(candidates));
        List<JsonValue> list = new ArrayList<>();
        preferenceList.forEach(entry -> {
            Map<String, JsonValue> member = new LinkedHashMap<>();
            member.put("node", new JsonString(entry.node().asText()));
            member.put("position", new JsonNumber(Integer.toString(entry.position())));
            member.put("role", new JsonString(entry.role().spelling()));
            member.put("health", new JsonString(entry.health().spelling()));
            list.add(new JsonObject(member));
        });
        members.put("preferenceList", new JsonArray(list));
        List<JsonValue> dropped = new ArrayList<>();
        exclusions.forEach(exclusion -> {
            Map<String, JsonValue> member = new LinkedHashMap<>();
            member.put("node", new JsonString(exclusion.node().asText()));
            member.put("stage", new JsonString(exclusion.stage().spelling()));
            member.put("reason", new JsonString(exclusion.reason()));
            dropped.add(new JsonObject(member));
        });
        members.put("exclusions", new JsonArray(dropped));
        List<JsonValue> relaxed = new ArrayList<>();
        relaxedLevels.forEach(level -> relaxed.add(new JsonString(level)));
        members.put("relaxedLevels", new JsonArray(relaxed));
        members.put("shortfall", shortfall.<JsonValue>map(note -> {
            Map<String, JsonValue> member = new LinkedHashMap<>();
            member.put("cause", new JsonString(note.cause().spelling()));
            member.put("factor", new JsonNumber(Integer.toString(note.factor())));
            member.put("achieved", new JsonNumber(Integer.toString(note.achieved())));
            return new JsonObject(member);
        }).orElseGet(JsonValue.JsonNull::new));
        return new String(JcsWriter.canonicalise(new JsonObject(members)), StandardCharsets.UTF_8);
    }

    private static JsonValue hex(byte[] octets) {
        return new JsonString(HexFormat.of().formatHex(octets));
    }

    private static JsonValue identities(List<NodeId> nodes) {
        List<JsonValue> elements = new ArrayList<>(nodes.size());
        nodes.forEach(node -> elements.add(new JsonString(node.asText())));
        return new JsonArray(elements);
    }
}
