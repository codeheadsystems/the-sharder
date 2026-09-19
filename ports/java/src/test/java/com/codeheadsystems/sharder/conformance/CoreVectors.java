package com.codeheadsystems.sharder.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.sharder.Digest;
import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.Digests;
import com.codeheadsystems.sharder.core.internal.document.DocumentValidator;
import com.codeheadsystems.sharder.core.internal.document.ValidationError;
import com.codeheadsystems.sharder.core.internal.json.JcsWriter;
import com.codeheadsystems.sharder.core.internal.json.JsonValue;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import com.codeheadsystems.sharder.core.internal.observe.Observability;
import com.codeheadsystems.sharder.core.internal.observe.PublicationEvents;
import com.codeheadsystems.sharder.core.internal.observe.SkewDetection;
import com.codeheadsystems.sharder.core.internal.route.OwnershipDelta;
import com.codeheadsystems.sharder.core.internal.route.PlacementEngine;
import com.codeheadsystems.sharder.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The vector kinds of the {@code core} conformance level.
 *
 * <p>A file at this level names its documents by path rather than carrying them, because a port
 * reaching {@code core} has the document pipeline a path needs: it reads the file, validates it,
 * canonicalises it, and digests it.
 */
final class CoreVectors {

    private final VectorSource source;
    private final JsonObject file;

    CoreVectors(VectorSource source, JsonObject file) {
        this.source = source;
        this.file = file;
    }

    /** The engine over a document the case names by path or carries inline. */
    private PlacementEngine engine(JsonObject testCase, String member) {
        return new PlacementEngine(com.codeheadsystems.sharder.core.internal.document
                .TopologyDocument.parse(source.readObject(testCase.text(member))));
    }

    /** {@code digest}: a document against its canonical form, its length, and its digest. */
    void digest(JsonObject testCase) {
        JsonValue document = testCase.find("document").isPresent()
                ? testCase.object("document")
                : source.readObject(testCase.text("topology"));
        JsonObject expect = testCase.object("expect");

        byte[] canonical = JcsWriter.canonicalise(document);
        expect.find("canonicalForm").ifPresent(value ->
                assertThat(new String(canonical, StandardCharsets.UTF_8)).as("canonicalForm")
                        .isEqualTo(value.asText()));
        assertThat(canonical.length).as("canonicalLength")
                .isEqualTo(expect.get("canonicalLength").asInt());
        Digest digest = Digests.of(canonical);
        assertThat(digest.toHex()).as("digest").isEqualTo(expect.text("digest"));
    }

    /** {@code validation}: a document against its validity and the rules it breaks. */
    void validation(JsonObject testCase) {
        JsonObject document = source.readObject(testCase.text("document"));
        JsonObject expect = testCase.object("expect");
        List<ValidationError> errors = DocumentValidator.validate(document);

        boolean valid = expect.get("valid").asBoolean();
        assertThat(errors.isEmpty()).as("valid, errors were %s", errors).isEqualTo(valid);
        if (valid) {
            return;
        }
        JsonObject condition = expect.object("condition");
        assertThat(201).as("condition code").isEqualTo(condition.get("code").asInt());
        assertThat("invalidTopology").as("condition name").isEqualTo(condition.text("name"));

        assertThat(errors.stream().map(ValidationError::rule).distinct().toList()).as("rules")
                .isEqualTo(expect.array("rules").texts());
        List<JsonValue> expected = expect.array("errors").elements();
        assertThat(errors).as("error count").hasSize(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            JsonObject entry = expected.get(index).asObject();
            ValidationError error = errors.get(index);
            assertThat(error.path()).as("errors[%d].path", index).isEqualTo(entry.text("path"));
            assertThat(error.rule()).as("errors[%d].rule", index).isEqualTo(entry.text("rule"));
            if (entry.find("detail").isPresent()) {
                assertThat(error.detail()).as("errors[%d].detail", index)
                        .isEqualTo(entry.text("detail"));
            }
        }
    }

    /** {@code errorTaxonomy}: the closed condition set, its report order, and its members. */
    void errorTaxonomy(JsonObject testCase) {
        JsonObject expect = testCase.object("expect");
        if (expect.find("conditionCount").isPresent()) {
            List<ErrorCode> codes = List.of(ErrorCode.values());
            assertThat(codes).as("conditionCount")
                    .hasSize(expect.get("conditionCount").asInt());
            assertThat(codes.stream().map(ErrorCode::code).toList()).as("codes")
                    .isEqualTo(expect.array("codes").elements().stream()
                            .map(JsonValue::asInt).toList());
            assertThat(codes.stream().map(ErrorCode::errorName).sorted().toList()).as("names")
                    .isEqualTo(expect.array("names").texts());
            List<JsonValue> conditions = expect.array("conditions").elements();
            for (int index = 0; index < conditions.size(); index++) {
                JsonObject entry = conditions.get(index).asObject();
                ErrorCode code = ErrorCode.ofCode(entry.get("code").asInt());
                assertThat(code.errorName()).as("name").isEqualTo(entry.text("name"));
                assertThat(code.retryableSpelling()).as("retryable of %s", code.errorName())
                        .isEqualTo(entry.text("retryable"));
                assertThat(code.condition()).as("condition of %s", code.errorName())
                        .isEqualTo(entry.text("condition"));
                assertThat(code.callerResponse()).as("callerResponse of %s", code.errorName())
                        .isEqualTo(entry.text("callerResponse"));
                entry.find("causes").ifPresent(causes ->
                        assertThat(code.causes()).as("causes of %s", code.errorName())
                                .containsExactlyInAnyOrderElementsOf(causes.asArray().texts()));
            }
            return;
        }
        if (expect.find("order").isPresent()) {
            assertThat(ErrorCode.REPORT_ORDER.stream().map(ErrorCode::errorName).toList())
                    .as("report order").isEqualTo(expect.array("order").texts());
            return;
        }
        if (expect.find("raisesCondition").isPresent()) {
            // ERR-009: a shortfall is a successful decision reported through the decision itself.
            assertThat(false).as("raisesCondition")
                    .isEqualTo(expect.get("raisesCondition").asBoolean());
            assertThat("shortfall").as("reportedThrough")
                    .isEqualTo(expect.text("reportedThrough"));
            return;
        }
        assertThat(ErrorCode.MEMBERS).as("members").isEqualTo(expect.array("members").texts());
        assertThat(false).as("carriesKeyByDefault")
                .isEqualTo(expect.get("carriesKeyByDefault").asBoolean());
        assertThat(expect.array("mergeablePairs").size()).as("mergeablePairs").isZero();
        assertThat(true).as("causeIsAClosedSubReason")
                .isEqualTo(expect.get("causeIsAClosedSubReason").asBoolean());
    }

    /** {@code observabilityInventory}: the metrics, the events, and what every event carries. */
    void observabilityInventory(JsonObject testCase) {
        JsonObject expect = testCase.object("expect");
        String surface = expect.find("surface").map(JsonValue::asText).orElse("routing");
        List<Observability.Metric> metrics = Observability.metricsOf(surface);
        List<Observability.Event> events = Observability.eventsOf(surface);
        if (expect.find("metricCount").isPresent()) {
            assertThat(metrics).as("metricCount")
                    .hasSize(expect.get("metricCount").asInt());
            assertThat(metrics.stream().map(Observability.Metric::name).sorted()
                    .toList()).as("names").isEqualTo(expect.array("names").texts());
            for (JsonValue row : expect.array("metrics").elements()) {
                JsonObject entry = row.asObject();
                Observability.Metric metric = metrics.stream()
                        .filter(candidate -> candidate.name().equals(entry.text("name")))
                        .findFirst().orElseThrow();
                assertThat(metric.instrument()).as("instrument of %s", metric.name())
                        .isEqualTo(entry.text("instrument"));
                assertThat(metric.labels().stream().sorted().toList())
                        .as("labels of %s", metric.name())
                        .isEqualTo(entry.array("labels").texts());
            }
            if (expect.find("labelValues").isEmpty()) {
                return;
            }
            for (Map.Entry<String, JsonValue> row
                    : expect.object("labelValues").members().entrySet()) {
                Map<String, List<String>> values = Observability.LABEL_VALUES.get(row.getKey());
                assertThat(values).as("label values of %s", row.getKey()).isNotNull();
                row.getValue().asObject().members().forEach((label, expected) ->
                        assertThat(values.get(label)).as("values of %s.%s", row.getKey(), label)
                                .isEqualTo(expected.asArray().texts()));
            }
            return;
        }
        if (expect.find("eventCount").isPresent()) {
            assertThat(events).as("eventCount")
                    .hasSize(expect.get("eventCount").asInt());
            assertThat(events.stream().map(Observability.Event::name).sorted()
                    .toList()).as("names").isEqualTo(expect.array("names").texts());
            for (JsonValue row : expect.array("events").elements()) {
                JsonObject entry = row.asObject();
                Observability.Event event = events.stream()
                        .filter(candidate -> candidate.name().equals(entry.text("name")))
                        .findFirst().orElseThrow();
                assertThat(event.severity()).as("severity of %s", event.name())
                        .isEqualTo(entry.text("severity"));
                assertThat(event.payload()).as("payload of %s", event.name())
                        .containsExactlyElementsOf(entry.array("payload").texts());
            }
            return;
        }
        assertThat(Observability.COMMON_EVENT_MEMBERS).as("commonMembers")
                .isEqualTo(expect.array("commonMembers").texts());
        assertThat(Observability.SEVERITIES).as("severities")
                .isEqualTo(expect.array("severities").texts());
        assertThat(false).as("carriesKey").isEqualTo(expect.get("carriesKey").asBoolean());
        assertThat(false).as("carriesRoutingKey")
                .isEqualTo(expect.get("carriesRoutingKey").asBoolean());
        assertThat(true).as("countsByNameWithoutASink")
                .isEqualTo(expect.get("countsByNameWithoutASink").asBoolean());
    }

    /** {@code publicationEvents}: the events one publication of a document emits. */
    void publicationEvents(JsonObject testCase) {
        JsonObject document = source.readObject(testCase.text("topology"));
        com.codeheadsystems.sharder.core.internal.document.TopologyDocument parsed =
                com.codeheadsystems.sharder.core.internal.document.TopologyDocument.parse(document);
        List<PublicationEvents.Emitted> emitted = PublicationEvents.of(parsed);
        List<JsonValue> expected = testCase.object("expect").array("events").elements();
        assertThat(emitted).as("event count").hasSize(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            JsonObject entry = expected.get(index).asObject();
            PublicationEvents.Emitted event = emitted.get(index);
            assertThat(event.name()).as("events[%d].event", index)
                    .isEqualTo(entry.text("event"));
            assertThat(event.severity()).as("events[%d].severity", index)
                    .isEqualTo(entry.text("severity"));
            assertThat(parsed.topologyId()).as("events[%d].topologyId", index)
                    .isEqualTo(entry.text("topologyId"));
            assertThat(parsed.epoch()).as("events[%d].epoch", index)
                    .isEqualTo(entry.get("epoch").asLong());
            for (Map.Entry<String, JsonValue> member : entry.members().entrySet()) {
                if (List.of("event", "severity", "topologyId", "epoch").contains(member.getKey())) {
                    continue;
                }
                Object carried = event.payload().get(member.getKey());
                assertThat(carried).as("events[%d].%s", index, member.getKey()).isNotNull();
                assertThat(rendered(carried)).as("events[%d].%s", index, member.getKey())
                        .isEqualTo(rendered(member.getValue()));
            }
        }
    }

    /** {@code ownershipDelta}: the shards whose replica sets differ between two snapshots. */
    void ownershipDelta(JsonObject testCase) {
        PlacementEngine before = engine(testCase, "before");
        PlacementEngine after = engine(testCase, "after");
        JsonObject expect = testCase.object("expect");
        if (expect.find("comparable").isPresent()) {
            java.util.Optional<String> differing =
                    OwnershipDelta.incomparable(before.document(), after.document());
            assertThat(differing).as("comparable").isPresent();
            assertThat(differing.orElseThrow()).as("differingField")
                    .isEqualTo(expect.text("differingField"));
            JsonObject condition = expect.object("condition");
            ErrorCode code = ErrorCode.ofName(condition.text("name"));
            assertThat(code.code()).as("condition code").isEqualTo(condition.get("code").asInt());
            assertThat(code.causes()).as("cause").contains(condition.text("cause"));
            return;
        }
        List<OwnershipDelta.ShardChange> delta = OwnershipDelta.between(before, after);

        assertThat(delta).as("shardsChanged").hasSize(expect.get("shardsChanged").asInt());
        List<JsonValue> expected = expect.array("delta").elements();
        for (int index = 0; index < expected.size(); index++) {
            JsonObject entry = expected.get(index).asObject();
            OwnershipDelta.ShardChange change = delta.get(index);
            assertThat(change.shard()).as("delta[%d].shard", index)
                    .isEqualTo(entry.text("shard"));
            assertThat(names(change.before())).as("delta[%d].before", index)
                    .isEqualTo(entry.array("before").texts());
            assertThat(names(change.after())).as("delta[%d].after", index)
                    .isEqualTo(entry.array("after").texts());
            assertThat(names(change.gained())).as("delta[%d].gained", index)
                    .isEqualTo(entry.array("gained").texts());
            assertThat(names(change.lost())).as("delta[%d].lost", index)
                    .isEqualTo(entry.array("lost").texts());
        }
    }

    /** {@code propertyWitness}: a sampled bound over the sample {@code PROP-006} fixes. */
    void propertyWitness(JsonObject testCase) {
        JsonObject expect = testCase.object("expect");
        if (expect.find("seed").isPresent()) {
            long seed = Long.parseUnsignedLong(expect.text("seed"), 16);
            KeySample sample = new KeySample(seed);
            List<String> draws = new ArrayList<>();
            for (int index = 0; index < expect.array("firstDraws").size(); index++) {
                draws.add(com.codeheadsystems.sharder.core.internal.hash.U64.toHex(sample.next()));
            }
            assertThat(draws).as("firstDraws").isEqualTo(expect.array("firstDraws").texts());
            List<byte[]> keys = KeySample.keys(seed, expect.array("firstKeys").size(), 16);
            assertThat(keys.stream().map(key -> HexFormat.of().formatHex(key)).toList())
                    .as("firstKeys").isEqualTo(expect.array("firstKeys").texts());
            return;
        }
        if (testCase.find("before").isPresent()) {
            movementWitness(testCase, expect);
            return;
        }
        balanceWitness(testCase, expect);
    }

    private void balanceWitness(JsonObject testCase, JsonObject expect) {
        PlacementEngine engine = engine(testCase, "topology");
        JsonObject sample = testCase.object("sample");
        List<byte[]> keys = KeySample.keys(
                Long.parseUnsignedLong(sample.text("seed"), 16),
                sample.get("count").asInt(), sample.get("keyOctets").asInt());
        Map<String, Integer> observed = new java.util.LinkedHashMap<>();
        for (byte[] key : keys) {
            String first = engine.route(key).candidates().get(0).asText();
            observed.merge(first, 1, Integer::sum);
        }
        long total = expect.get("sumVirtualNodes").asLong();
        long sampleSize = expect.get("sampleSize").asInt();
        long multiplier = expect.get("multiplier").asLong();
        for (JsonValue row : expect.array("perNode").elements()) {
            JsonObject entry = row.asObject();
            String node = entry.text("node");
            long count = observed.getOrDefault(node, 0);
            assertThat(count).as("observed of %s", node)
                    .isEqualTo(entry.get("observed").asLong());
            long virtualNodes = entry.get("virtualNodes").asLong();
            long left = multiplier * Math.abs(count * total - sampleSize * virtualNodes);
            long right = sampleSize * virtualNodes;
            assertThat(left).as("left of %s", node).isEqualTo(entry.get("left").asLong());
            assertThat(right).as("right of %s", node).isEqualTo(entry.get("right").asLong());
            assertThat(left <= right).as("holds of %s", node)
                    .isEqualTo(entry.get("holds").asBoolean());
        }
        assertThat(expect.get("passed").asBoolean()).as("passed").isTrue();
    }

    private void movementWitness(JsonObject testCase, JsonObject expect) {
        PlacementEngine before = engine(testCase, "before");
        PlacementEngine after = engine(testCase, "after");
        JsonObject sample = testCase.object("sample");
        List<byte[]> keys = KeySample.keys(
                Long.parseUnsignedLong(sample.text("seed"), 16),
                sample.get("count").asInt(), sample.get("keyOctets").asInt());
        long moved = 0;
        for (byte[] key : keys) {
            if (!before.route(key).candidates().get(0)
                    .equals(after.route(key).candidates().get(0))) {
                moved++;
            }
        }
        assertThat(moved).as("movedCount").isEqualTo(expect.get("movedCount").asLong());
        JsonObject fraction = expect.object("expectedFraction");
        long numerator = fraction.get("numerator").asLong();
        long denominator = fraction.get("denominator").asLong();
        long sampleSize = expect.get("sampleSize").asLong();
        long multiplier = expect.get("multiplier").asLong();
        long left = multiplier * Math.abs(moved * denominator - sampleSize * numerator);
        long right = sampleSize * numerator;
        assertThat(left).as("left").isEqualTo(expect.get("left").asLong());
        assertThat(right).as("right").isEqualTo(expect.get("right").asLong());
        assertThat(left <= right).as("passed").isEqualTo(expect.get("passed").asBoolean());
    }

    /** The skew formulas of {@code OBS-031} and {@code OBS-032}. */
    static boolean skewFormula(String formula, JsonObject inputs) {
        return switch (formula) {
            case "shardIsHot" -> SkewDetection.shardIsHot(
                    inputs.get("shardRequests").asUnsignedLong(),
                    inputs.get("shardCount").asUnsignedLong(),
                    inputs.get("totalRequests").asUnsignedLong(),
                    inputs.get("hotShardFactorPercent").asUnsignedLong());
            case "keySkew" -> SkewDetection.keySkew(
                    inputs.get("hottestKeyRequests").asUnsignedLong(),
                    inputs.get("requests").asUnsignedLong(),
                    inputs.get("keySkewPercent").asUnsignedLong());
            default -> throw new AssertionError("the driver implements no formula " + formula);
        };
    }

    private static List<String> names(List<NodeId> identities) {
        return identities.stream().map(NodeId::asText).toList();
    }

    /** A payload member and its expectation, compared as text so that a count matches a number. */
    private static String rendered(Object value) {
        if (value instanceof JsonValue json) {
            return switch (json) {
                case JsonValue.JsonString string -> string.value();
                case JsonValue.JsonNumber number -> number.literal();
                case JsonValue.JsonArray array -> String.join(",", array.texts());
                default -> json.toString();
            };
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        }
        return String.valueOf(value);
    }

    /** {@code scale}: the same placement function over a topology of a thousand nodes. */
    void scale(JsonObject testCase) {
        PlacementEngine engine = engine(testCase, "topology");
        JsonObject expect = testCase.object("expect");

        assertThat(engine.document().nodes()).as("nodeCount")
                .hasSize(expect.get("nodeCount").asInt());
        assertThat(engine.document().placementSet()).as("placementSetCount")
                .hasSize(expect.get("placementSetCount").asInt());

        // PLACE-073: the total is measured over the placement set before preparation, and named
        // with the setting it is compared against.
        assertThat(engine.placementTotal()).as("total").isEqualTo(expect.get("total").asLong());
        assertThat(engine.placementTotalSetting()).as("totalSetting")
                .isEqualTo(expect.text("totalSetting"));

        int shards = 0;
        List<String> prefix = new ArrayList<>();
        java.util.Iterator<String> cursor = engine.placement().shardCursor();
        while (cursor.hasNext()) {
            String shard = cursor.next();
            if (prefix.size() < expect.array("shardPrefix").size()) {
                prefix.add(shard);
            }
            shards++;
        }
        assertThat(shards).as("shardCount").isEqualTo(expect.get("shardCount").asInt());
        assertThat(prefix).as("shardPrefix").isEqualTo(expect.array("shardPrefix").texts());

        List<String> forFirstShard = expect.array("candidatesForFirstShard").texts();
        if (!forFirstShard.isEmpty()) {
            assertThat(names(com.codeheadsystems.sharder.core.internal.placement.PreparedPlacement
                    .take(engine.placement().cursorForShard(prefix.get(0),
                            engine.placementEligible()), forFirstShard.size())))
                    .as("candidatesForFirstShard").isEqualTo(forFirstShard);
        }

        for (JsonValue row : expect.array("rows").elements()) {
            JsonObject entry = row.asObject();
            var decision = engine.route(PlaceVectors.octets(entry.object("key")));
            assertThat(HexFormat.of().formatHex(decision.routingKey())).as("routingKey")
                    .isEqualTo(entry.text("routingKey"));
            JsonValue shard = entry.get("shard");
            assertThat(decision.shard()).as("shard").isEqualTo(shard.isNull()
                    ? java.util.Optional.<String>empty() : java.util.Optional.of(shard.asText()));
            assertThat(decision.factor()).as("factor").isEqualTo(entry.get("factor").asInt());
            assertThat(decision.replicaCount()).as("replicaCount")
                    .isEqualTo(entry.get("replicaCount").asInt());
            assertThat(decision.materialisedEntries()).as("materialisedEntries")
                    .isEqualTo(entry.get("materialisedEntries").asInt());
            assertThat(decision.relaxedLevels()).as("relaxedLevels")
                    .isEqualTo(entry.array("relaxedLevels").texts());
            assertThat(decision.spreadStage()).as("spreadStage")
                    .isEqualTo(entry.get("spreadStage").asInt());
            assertThat(decision.shortfall()).as("shortfall").isEqualTo(entry.text("shortfall"));

            List<String> candidatePrefix = entry.array("candidatePrefix").texts();
            assertThat(names(com.codeheadsystems.sharder.core.internal.placement.PreparedPlacement
                    .take(engine.cursor(decision.routingKey()), candidatePrefix.size())))
                    .as("candidatePrefix").isEqualTo(candidatePrefix);

            List<JsonValue> preference = entry.array("preferenceListPrefix").elements();
            List<com.codeheadsystems.sharder.NodeId> list = decision.preferenceList();
            for (int index = 0; index < preference.size(); index++) {
                JsonObject expected = preference.get(index).asObject();
                assertThat(list.get(index).asText()).as("preferenceListPrefix[%d].node", index)
                        .isEqualTo(expected.text("node"));
                assertThat(index).as("preferenceListPrefix[%d].position", index)
                        .isEqualTo(expected.get("position").asInt());
                assertThat(decision.roleAt(index)).as("preferenceListPrefix[%d].role", index)
                        .isEqualTo(expected.text("role"));
            }
        }
    }

    /** {@code readAffinity}: the preference list, and the reordering within the replica prefix. */
    void readAffinity(JsonObject testCase) {
        PlacementEngine engine = new PlacementEngine(
                com.codeheadsystems.sharder.core.internal.document.TopologyDocument.parse(
                        source.readObject(file.text("topology"))));
        byte[] key = PlaceVectors.octets(testCase.object("key"));
        JsonObject affinity = testCase.object("affinity");
        com.codeheadsystems.sharder.core.internal.route.ReadAffinity.Request request =
                new com.codeheadsystems.sharder.core.internal.route.ReadAffinity.Request(
                        affinity.text("level"),
                        affinity.array("path").texts(),
                        affinity.get("window").isNull()
                                ? java.util.OptionalInt.empty()
                                : java.util.OptionalInt.of(affinity.get("window").asInt()));

        if (testCase.find("expectError").isPresent()) {
            JsonObject error = testCase.object("expectError");
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            com.codeheadsystems.sharder.core.internal.route.ReadAffinity.reorder(
                                    engine.document(), engine.route(key).preferenceList(),
                                    engine.route(key).replicaCount(), request))
                    .isInstanceOfSatisfying(
                            com.codeheadsystems.sharder.error.InvalidArgumentException.class,
                            raised -> {
                                assertThat(raised.code()).as("code")
                                        .isEqualTo(error.get("code").asInt());
                                assertThat(raised.errorName()).as("name")
                                        .isEqualTo(error.text("name"));
                            });
            return;
        }

        var decision = engine.route(key);
        JsonObject expect = testCase.object("expect");
        List<com.codeheadsystems.sharder.NodeId> preference = decision.preferenceList();
        assertPositions(preference, expect.array("preferenceList"), decision.replicaCount(),
                "preferenceList");
        List<com.codeheadsystems.sharder.NodeId> ordered =
                com.codeheadsystems.sharder.core.internal.route.ReadAffinity.reorder(
                        engine.document(), preference, decision.replicaCount(), request);
        assertPositions(ordered, expect.array("ordered"), decision.replicaCount(), "ordered");
        expect.find("materialisedEntries").ifPresent(value ->
                assertThat(decision.materialisedEntries()).as("materialisedEntries")
                        .isEqualTo(value.asInt()));
        expect.find("replicaCount").ifPresent(value ->
                assertThat(decision.replicaCount()).as("replicaCount").isEqualTo(value.asInt()));
        expect.find("shard").ifPresent(value ->
                assertThat(decision.shard()).as("shard").contains(value.asText()));
    }

    /** One list against its expectation, entry by entry, with the role each position carries. */
    private void assertPositions(List<com.codeheadsystems.sharder.NodeId> actual,
                                 JsonValue.JsonArray expected, int replicaCount, String what) {
        assertThat(actual).as("%s length", what).hasSize(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            JsonObject entry = expected.get(index).asObject();
            assertThat(actual.get(index).asText()).as("%s[%d].node", what, index)
                    .isEqualTo(entry.text("node"));
            assertThat(index).as("%s[%d].position", what, index)
                    .isEqualTo(entry.get("position").asInt());
            // READ-016: the roles follow the positions of the unreordered list, so a reordering
            // inside the replica prefix leaves every role where it was.
            assertThat(index < replicaCount ? "replica" : "fallback")
                    .as("%s[%d].role", what, index).isEqualTo(entry.text("role"));
        }
    }
}
