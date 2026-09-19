package com.codeheadsystems.sharder.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.TopologyDocument;
import com.codeheadsystems.sharder.core.internal.json.JsonValue;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import com.codeheadsystems.sharder.core.internal.hash.U64;
import com.codeheadsystems.sharder.core.internal.health.HealthSettings;
import com.codeheadsystems.sharder.core.internal.health.HealthView;
import com.codeheadsystems.sharder.core.internal.route.RetryBudget;
import com.codeheadsystems.sharder.core.internal.placement.PreparedPlacement;
import com.codeheadsystems.sharder.core.internal.placement.RendezvousPlacement;
import com.codeheadsystems.sharder.core.internal.placement.RingPlacement;
import com.codeheadsystems.sharder.core.internal.route.PlacementDecision;
import com.codeheadsystems.sharder.core.internal.route.PlacementEngine;
import com.codeheadsystems.sharder.core.internal.route.SpreadLadder;
import com.codeheadsystems.sharder.error.NoCandidateException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The vector kinds of the {@code place} conformance level.
 *
 * <p>Every case reads its topology from the {@code topologyDocuments} member of its file, which a
 * file at this level carries already valid, so the port answers with the placement engine and no
 * document pipeline. A case is compared field by field, and only the fields it carries: a case that
 * omits a member makes no claim about it.
 */
final class PlaceVectors {

    private static final HexFormat HEX = HexFormat.of();

    private final JsonObject file;

    PlaceVectors(JsonObject file) {
        this.file = file;
    }

    /** The engine over the document at that path, which the file carries inline. */
    private PlacementEngine engine(String path) {
        return new PlacementEngine(TopologyDocument.parse(
                file.object("topologyDocuments").object(path)));
    }

    /** The engine over the document a case names, or the one the file names. */
    private PlacementEngine engine(JsonObject testCase) {
        String path = testCase.find("topology").map(JsonValue::asText)
                .orElseGet(() -> file.text("topology"));
        return engine(path);
    }

    /** The octets of a {@code {encoding, value}} pair, under the octet encoding of the suite. */
    static byte[] octets(JsonObject value) {
        String encoding = value.find("encoding").map(JsonValue::asText).orElse("utf8");
        String text = value.text("value");
        return "base16".equals(encoding)
                ? HEX.parseHex(text)
                : text.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> names(List<NodeId> identities) {
        return identities.stream().map(NodeId::asText).toList();
    }

    /** {@code keyTransform}: a key against the routing key it derives. */
    void keyTransform(JsonObject testCase) {
        PlacementEngine engine = engine(testCase);
        byte[] routingKey = engine.routingKey(octets(testCase.object("key")));
        assertThat(HEX.formatHex(routingKey))
                .isEqualTo(testCase.object("expect").text("routingKey"));
    }

    /** {@code routing}: a key against the whole decision, or against the condition it raises. */
    void routing(JsonObject testCase) {
        PlacementEngine engine = engine(testCase);
        byte[] key = octets(testCase.object("key"));

        Optional<JsonObject> expectError = testCase.find("expectError").map(JsonValue::asObject);
        if (expectError.isPresent()) {
            JsonObject error = expectError.get();
            assertThatThrownBy(() -> engine.route(key))
                    .isInstanceOfSatisfying(NoCandidateException.class, raised -> {
                        assertThat(raised.code()).as("code").isEqualTo(error.get("code").asInt());
                        assertThat(raised.errorName()).as("name").isEqualTo(error.text("name"));
                        if (!error.get("cause").isNull()) {
                            assertThat(raised.reason().spelling()).as("cause")
                                    .isEqualTo(error.text("cause"));
                        }
                    });
            return;
        }

        JsonObject expect = testCase.object("expect");
        PlacementDecision decision = engine.route(key);
        assertDecision(decision, expect);
    }

    private void assertDecision(PlacementDecision decision, JsonObject expect) {
        expect.find("routingKey").ifPresent(value ->
                assertThat(HEX.formatHex(decision.routingKey()))
                        .as("routingKey").isEqualTo(value.asText()));
        expect.find("shard").ifPresent(value -> assertThat(decision.shard()).as("shard")
                .isEqualTo(value.isNull()
                        ? Optional.<String>empty() : Optional.of(value.asText())));
        expect.find("token").ifPresent(value -> {
            JsonObject token = value.asObject();
            assertThat(decision.topologyId()).as("token.topologyId")
                    .isEqualTo(token.text("topologyId"));
            assertThat(decision.epoch()).as("token.epoch").isEqualTo(token.get("epoch").asLong());
        });
        expect.find("factor").ifPresent(value ->
                assertThat(decision.factor()).as("factor").isEqualTo(value.asInt()));
        expect.find("replicaCount").ifPresent(value ->
                assertThat(decision.replicaCount()).as("replicaCount").isEqualTo(value.asInt()));
        expect.find("candidates").ifPresent(value ->
                assertThat(names(decision.candidates())).as("candidates")
                        .isEqualTo(value.asArray().texts()));
        expect.find("preferenceList").ifPresent(value ->
                assertPreferenceList(decision, value.asArray()));
        expect.find("materialisedEntries").ifPresent(value ->
                assertThat(decision.materialisedEntries()).as("materialisedEntries")
                        .isEqualTo(value.asInt()));
        expect.find("relaxedLevels").ifPresent(value ->
                assertThat(decision.relaxedLevels()).as("relaxedLevels")
                        .isEqualTo(value.asArray().texts()));
        expect.find("spreadStage").ifPresent(value ->
                assertThat(decision.spreadStage()).as("spreadStage").isEqualTo(value.asInt()));
        expect.find("shortfall").ifPresent(value ->
                assertThat(decision.shortfall()).as("shortfall").isEqualTo(value.asText()));
        expect.find("attemptLimit").ifPresent(value ->
                assertThat(decision.attemptLimit()).as("attemptLimit").isEqualTo(value.asInt()));
        expect.find("matchedOverride").ifPresent(value -> {
            if (value.isNull()) {
                assertThat(decision.matchedOverride()).as("matchedOverride").isEmpty();
                return;
            }
            JsonObject matched = value.asObject();
            assertThat(decision.matchedOverride()).as("matchedOverride").isPresent();
            assertThat(decision.matchedOverride().orElseThrow().index()).as("matchedOverride.index")
                    .isEqualTo(matched.get("index").asInt());
            assertThat(decision.matchedOverride().orElseThrow().mode()).as("matchedOverride.mode")
                    .isEqualTo(matched.text("mode"));
        });
    }

    private void assertPreferenceList(PlacementDecision decision, JsonValue.JsonArray expected) {
        List<NodeId> preference = decision.preferenceList();
        assertThat(preference).as("preferenceList length").hasSize(expected.size());
        for (int position = 0; position < expected.size(); position++) {
            JsonObject entry = expected.get(position).asObject();
            assertThat(preference.get(position).asText())
                    .as("preferenceList[%d].node", position).isEqualTo(entry.text("node"));
            assertThat(position).as("preferenceList[%d].position", position)
                    .isEqualTo(entry.get("position").asInt());
            assertThat(decision.roleAt(position))
                    .as("preferenceList[%d].role", position).isEqualTo(entry.text("role"));
        }
    }

    /** {@code shards}: the enumeration, and the agreement of {@code PLACE-033} per key. */
    void shards(JsonObject testCase) {
        PlacementEngine engine = engine(testCase);
        PreparedPlacement placement = engine.placement();
        JsonObject expect = testCase.object("expect");

        List<String> shards = placement.shards();
        assertThat(shards).as("shardCount").hasSize(expect.get("shardCount").asInt());
        List<String> listed = expect.array("shards").texts();
        if (expect.get("shardsTruncated").asBoolean()) {
            assertThat(shards.subList(0, listed.size())).as("shards prefix").isEqualTo(listed);
        } else {
            assertThat(shards).as("shards").isEqualTo(listed);
        }

        for (JsonValue row : expect.array("perKey").elements()) {
            JsonObject entry = row.asObject();
            byte[] routingKey = engine.routingKey(octets(entry.object("key")));
            Optional<String> shard = placement.shardOf(routingKey);
            JsonValue expectedShard = entry.get("shard");
            assertThat(shard).as("shardOf")
                    .isEqualTo(expectedShard.isNull()
                            ? Optional.empty() : Optional.of(expectedShard.asText()));
            List<String> candidates =
                    names(placement.candidates(routingKey, engine.placementEligible()));
            assertThat(candidates).as("candidates").isEqualTo(entry.array("candidates").texts());
            List<String> forShard = shard
                    .map(value -> names(placement.candidatesForShard(value,
                            engine.placementEligible())))
                    .orElseGet(List::of);
            assertThat(forShard).as("candidatesForShard")
                    .isEqualTo(entry.array("candidatesForShard").texts());
            assertThat(candidates.equals(forShard)).as("agrees")
                    .isEqualTo(entry.get("agrees").asBoolean());
        }
    }

    /** {@code permutation}: a document and its permutation, whose orderings are equal. */
    void permutation(JsonObject testCase) {
        PlacementEngine named = engine(testCase);
        PlacementEngine permuted = new PlacementEngine(
                TopologyDocument.parse(testCase.object("permutedDocument")));
        for (JsonValue row : testCase.object("expect").array("identicalCandidates").elements()) {
            JsonObject entry = row.asObject();
            byte[] key = octets(entry.object("key"));
            List<String> expected = entry.array("candidates").texts();
            assertThat(names(named.route(key).candidates())).as("candidates").isEqualTo(expected);
            assertThat(names(permuted.route(key).candidates())).as("permuted candidates")
                    .isEqualTo(expected);
        }
    }

    /** {@code collidingKeys}: distinct keys that share a routing key share an ordering. */
    void collidingKeys(JsonObject testCase) {
        PlacementEngine engine = engine(testCase);
        JsonObject expect = testCase.object("expect");
        List<JsonValue> keys = testCase.array("keys").elements();
        List<String> routingKeys = new ArrayList<>();
        List<List<String>> orderings = new ArrayList<>();
        for (JsonValue key : keys) {
            byte[] octets = octets(key.asObject());
            routingKeys.add(HEX.formatHex(engine.routingKey(octets)));
            orderings.add(names(engine.route(octets).candidates()));
        }
        expect.find("routingKeys").ifPresent(value ->
                assertThat(routingKeys).as("routingKeys").isEqualTo(value.asArray().texts()));
        byte[] first = engine.routingKey(octets(keys.get(0).asObject()));
        expect.find("keyHash").ifPresent(value ->
                assertThat(U64.toHex(engine.keyHash(first))).as("keyHash")
                        .isEqualTo(value.asText()));
        expect.find("shard").ifPresent(value ->
                assertThat(engine.placement().shardOf(first)).as("shard")
                        .contains(value.asText()));
        expect.find("slotIndex").ifPresent(value ->
                assertThat(engine.placement().shardOf(first)).as("slotIndex")
                        .contains(Integer.toString(value.asInt())));
        expect.find("slotIndexAt7").ifPresent(value ->
                assertThat(U64.mod(engine.keyHash(first), 7)).as("slotIndexAt7")
                        .isEqualTo(value.asInt()));
        List<List<String>> expectedOrderings = expect.array("candidates").elements().stream()
                .map(value -> value.asArray().texts()).toList();
        assertThat(orderings).as("candidates").isEqualTo(expectedOrderings);
        assertThat(Set.copyOf(orderings).size() == 1).as("identical")
                .isEqualTo(expect.get("identical").asBoolean());
    }

    /** {@code movement}: how many first candidates move when one node joins. */
    void movement(JsonObject testCase) {
        PlacementEngine before = engine(testCase.text("before"));
        PlacementEngine after = engine(testCase.text("after"));
        JsonObject expect = testCase.object("expect");

        int sampleSize = expect.get("sampleSize").asInt();
        int moved = 0;
        for (int index = 0; index < sampleSize; index++) {
            byte[] key = ("mv-" + index).getBytes(StandardCharsets.UTF_8);
            if (!before.route(key).candidates().get(0)
                    .equals(after.route(key).candidates().get(0))) {
                moved++;
            }
        }
        assertThat(moved).as("movedCount").isEqualTo(expect.get("movedCount").asInt());
        assertThat(sampleSize - moved).as("unmovedCount")
                .isEqualTo(expect.get("unmovedCount").asInt());

        for (JsonValue row : expect.array("firstCandidates").elements()) {
            JsonObject entry = row.asObject();
            byte[] key = octets(entry.object("key"));
            String first = before.route(key).candidates().get(0).asText();
            String next = after.route(key).candidates().get(0).asText();
            assertThat(first).as("before").isEqualTo(entry.text("before"));
            assertThat(next).as("after").isEqualTo(entry.text("after"));
            assertThat(!first.equals(next)).as("moved")
                    .isEqualTo(entry.get("moved").asBoolean());
        }

        NodeId added = NodeId.of(testCase.text("addedNode"));
        for (JsonValue row : expect.array("orderingSubsequence").elements()) {
            JsonObject entry = row.asObject();
            byte[] key = octets(entry.object("key"));
            List<String> beforeOrdering = names(before.route(key).candidates());
            List<String> afterOrdering = names(after.route(key).candidates());
            assertThat(beforeOrdering).as("beforeCandidates")
                    .isEqualTo(entry.array("beforeCandidates").texts());
            assertThat(afterOrdering).as("afterCandidates")
                    .isEqualTo(entry.array("afterCandidates").texts());
            List<String> without = new ArrayList<>(afterOrdering);
            without.remove(added.asText());
            assertThat(without).as("afterWithoutAddedNode")
                    .isEqualTo(entry.array("afterWithoutAddedNode").texts());
        }
    }

    /** {@code tieBreak}: a topology built on a hash collision, and the ordering it produces. */
    void tieBreak(JsonObject testCase) {
        PlacementEngine engine = engine(testCase);
        JsonObject expect = testCase.object("expect");
        if (engine.placement() instanceof RendezvousPlacement rendezvous) {
            rendezvousTie(engine, rendezvous, expect);
            return;
        }
        RingPlacement ring = (RingPlacement) engine.placement();

        String colliding = expect.text("collidingToken");
        List<RingPlacement.RingEntry> at = ring.ringOrder().stream()
                .filter(entry -> entry.token().equals(colliding))
                .toList();
        List<JsonValue> expected = expect.array("ringOrderAtCollision").elements();
        assertThat(at).as("ringOrderAtCollision").hasSize(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            JsonObject entry = expected.get(index).asObject();
            assertThat(at.get(index).token()).as("token").isEqualTo(entry.text("token"));
            assertThat(at.get(index).owner().asText()).as("owner").isEqualTo(entry.text("owner"));
            assertThat(at.get(index).index()).as("index").isEqualTo(entry.get("index").asInt());
        }

        List<String> owners = at.stream().map(entry -> entry.owner().asText()).toList();
        assertThat(owners).as("owners").isEqualTo(expect.array("owners").texts());
        assertThat(owners.get(0)).as("lowerIdentity").isEqualTo(expect.text("lowerIdentity"));

        JsonObject enumeration = expect.object("shardEnumeration");
        assertThat(ring.ringOrder()).as("ringEntryCount")
                .hasSize(enumeration.get("ringEntryCount").asInt());
        assertThat(ring.shards()).as("shardCount")
                .hasSize(enumeration.get("shardCount").asInt());
        assertThat(ring.shards()).as("shards").isEqualTo(enumeration.array("shards").texts());

        for (JsonValue row : expect.array("candidates").elements()) {
            JsonObject entry = row.asObject();
            assertThat(names(engine.route(octets(entry.object("key"))).candidates()))
                    .as("candidates").isEqualTo(entry.array("candidates").texts());
        }
    }

    /** A rendezvous score tie, where the identity comparison of {@code RV-010} decides alone. */
    private void rendezvousTie(PlacementEngine engine, RendezvousPlacement rendezvous,
                               JsonObject expect) {
        byte[] routingKey = HEX.parseHex(expect.text("routingKey"));
        List<String> tied = expect.array("tiedNodes").texts();
        for (String identity : tied) {
            assertThat(U64.toHex(rendezvous.score(routingKey, NodeId.of(identity))))
                    .as("score of %s", identity).isEqualTo(expect.text("equalScore"));
        }
        assertThat(tied.get(0)).as("lowerIdentity").isEqualTo(expect.text("lowerIdentity"));
        for (JsonValue row : expect.array("candidates").elements()) {
            JsonObject entry = row.asObject();
            PlacementDecision decision = engine.route(octets(entry.object("key")));
            assertThat(names(decision.candidates())).as("candidates")
                    .isEqualTo(entry.array("candidates").texts());
            entry.find("shard").ifPresent(value ->
                    assertThat(decision.shard()).as("shard").contains(value.asText()));
        }
    }

    /** {@code stages}: every relaxation stage of one key, and the one the policy chooses. */
    void stages(JsonObject testCase) {
        PlacementEngine engine = engine(testCase);
        JsonObject expect = testCase.object("expect");
        byte[] key = octets(testCase.object("key"));
        PlacementDecision decision = engine.route(key);
        int factor = expect.get("factor").asInt();

        assertThat(names(decision.candidates())).as("candidates")
                .isEqualTo(expect.array("candidates").texts());
        assertThat(decision.factor()).as("factor").isEqualTo(factor);

        List<SpreadLadder.Stage> stages = engine.ladder().stages(decision.candidates(), factor);
        List<JsonValue> expectedStages = expect.array("stages").elements();
        assertThat(stages).as("stage count").hasSize(expectedStages.size());
        for (int index = 0; index < expectedStages.size(); index++) {
            JsonObject entry = expectedStages.get(index).asObject();
            SpreadLadder.Stage stage = stages.get(index);
            assertThat(stage.index()).as("stage").isEqualTo(entry.get("stage").asInt());
            assertThat(stage.enforces()).as("enforces").isEqualTo(entry.array("enforces").texts());
            assertThat(stage.relaxes()).as("relaxes").isEqualTo(entry.array("relaxes").texts());
            assertThat(names(stage.selected())).as("selected")
                    .isEqualTo(entry.array("selected").texts());
            assertThat(stage.reachesFactor()).as("reachesFactor")
                    .isEqualTo(entry.get("reachesFactor").asBoolean());
        }

        assertThat(decision.spreadStage()).as("chosenStage")
                .isEqualTo(expect.get("chosenStage").asInt());
        assertThat(decision.relaxedLevels()).as("relaxedLevels")
                .isEqualTo(expect.array("relaxedLevels").texts());
        assertPreferenceList(decision, expect.array("preferenceList"));

        Set<List<String>> distinct = new LinkedHashSet<>();
        stages.forEach(stage -> distinct.add(names(stage.selected())));
        assertThat(distinct).as("distinctStageOutcomes")
                .hasSize(expect.get("distinctStageOutcomes").asInt());
    }

    /** {@code defaults}: a document omitting defaults and one writing them out. */
    void defaults(JsonObject testCase) {
        PlacementEngine omitted = engine(testCase.text("omittedDocument"));
        PlacementEngine explicit = engine(testCase.text("explicitDocument"));
        JsonObject expect = testCase.object("expect");
        boolean identical = true;
        for (JsonValue row : expect.array("rows").elements()) {
            JsonObject entry = row.asObject();
            byte[] key = octets(entry.object("key"));
            PlacementDecision left = omitted.route(key);
            PlacementDecision right = explicit.route(key);
            List<String> expected = entry.array("candidates").texts();
            assertThat(names(left.candidates())).as("omitted candidates").isEqualTo(expected);
            assertThat(names(right.candidates())).as("explicit candidates").isEqualTo(expected);
            entry.find("factor").ifPresent(value -> {
                assertThat(left.factor()).as("omitted factor").isEqualTo(value.asInt());
                assertThat(right.factor()).as("explicit factor").isEqualTo(value.asInt());
            });
            entry.find("replicaCount").ifPresent(value ->
                    assertThat(left.replicaCount()).as("replicaCount").isEqualTo(value.asInt()));
            identical &= names(left.candidates()).equals(names(right.candidates()));
        }
        assertThat(identical).as("identical").isEqualTo(expect.get("identical").asBoolean());
    }

    /** {@code pinShard}: a pinned key keeps its shard, and the two orderings differ. */
    void pinShard(JsonObject testCase) {
        PlacementEngine engine = engine(testCase);
        JsonObject expect = testCase.object("expect");
        byte[] key = octets(testCase.object("key"));
        PlacementDecision decision = engine.route(key);

        assertThat(decision.shard()).as("shard").contains(expect.text("shard"));
        List<String> candidates = names(decision.candidates());
        assertThat(candidates).as("candidates").isEqualTo(expect.array("candidates").texts());
        List<String> forShard = names(engine.placement()
                .candidatesForShard(decision.shard().orElseThrow(), engine.placementEligible()));
        assertThat(forShard).as("candidatesForShard")
                .isEqualTo(expect.array("candidatesForShard").texts());
        assertThat(candidates.equals(forShard)).as("agrees")
                .isEqualTo(expect.get("agrees").asBoolean());
        expect.find("matchedOverride").ifPresent(value -> {
            if (value.isNull()) {
                assertThat(decision.matchedOverride()).as("matchedOverride").isEmpty();
                return;
            }
            JsonObject matched = value.asObject();
            assertThat(decision.matchedOverride().orElseThrow().index()).as("index")
                    .isEqualTo(matched.get("index").asInt());
            assertThat(decision.matchedOverride().orElseThrow().mode()).as("mode")
                    .isEqualTo(matched.text("mode"));
        });
    }

    /** {@code formula}: one named integer formula against its inputs. */
    static void formula(JsonObject testCase) {
        JsonObject inputs = testCase.object("inputs");
        switch (testCase.text("formula")) {
            case "resolvedAttemptLimitBeforeClamp" -> {
                // CORE-048: the call's limit, else the configured one, else the factor plus two.
                JsonValue supplied = inputs.get("routeOptionsAttemptLimit");
                JsonValue configured = inputs.get("configuredAttemptLimit");
                int resolved = !supplied.isNull() ? supplied.asInt()
                        : !configured.isNull() ? configured.asInt()
                        : inputs.get("factor").asInt() + 2;
                assertThat(resolved).isEqualTo(testCase.get("expect").asInt());
            }
            case "compareNodeIdentity" -> {
                NodeId left = NodeId.ofBytes(HEX.parseHex(inputs.text("leftOctets")));
                NodeId right = NodeId.ofBytes(HEX.parseHex(inputs.text("rightOctets")));
                int comparison = left.compareTo(right);
                String answer = comparison < 0 ? "less" : comparison > 0 ? "greater" : "equal";
                assertThat(answer).isEqualTo(testCase.get("expect").asText());
            }
            case "virtualNodeCount" -> {
                long product = (long) inputs.get("weight").asInt()
                        * (long) inputs.get("perWeightUnit").asInt();
                long count = Math.min(product, inputs.get("cap").asInt());
                assertThat(count).isEqualTo(testCase.get("expect").asLong());
            }
            case "shardIsHot", "keySkew" -> assertThat(
                    CoreVectors.skewFormula(testCase.text("formula"), inputs))
                    .isEqualTo(testCase.get("expect").asBoolean());
            case "retryPermitted" -> assertThat(RetryBudget.permitted(
                    inputs.get("retries").asLong(), inputs.get("firstAttempts").asLong(),
                    inputs.get("retryBudgetPercent").asLong(),
                    inputs.get("retryBudgetMinimum").asLong()))
                    .isEqualTo(testCase.get("expect").asBoolean());
            case "defaultAttemptLimit" -> assertThat(Math.min(
                    inputs.get("factor").asInt() + 2,
                    inputs.get("attemptSequenceLength").asInt()))
                    .isEqualTo(testCase.get("expect").asInt());
            case "resolvedAttemptLimit" -> {
                // CORE-048 resolves, FAIL-022 clamps to the length of the attempt sequence.
                JsonValue supplied = inputs.get("routeOptionsAttemptLimit");
                JsonValue configured = inputs.get("configuredAttemptLimit");
                int resolved = !supplied.isNull() ? supplied.asInt()
                        : !configured.isNull() ? configured.asInt()
                        : inputs.get("factor").asInt() + 2;
                assertThat(Math.min(resolved, inputs.get("attemptSequenceLength").asInt()))
                        .isEqualTo(testCase.get("expect").asInt());
            }
            case "failurePercent" -> {
                long successes = inputs.get("successes").asLong();
                long failures = inputs.get("failures").asLong();
                long total = successes + failures;
                assertThat(total == 0 ? 0L : failures * 100 / total)
                        .isEqualTo(testCase.get("expect").asLong());
            }
            case "ejectionRefused" -> assertThat(HealthView.refused(
                    inputs.get("ejected").asLong(),
                    inputs.get("maxEjectionPercent").asLong(),
                    inputs.get("placementSetSize").asLong()))
                    .isEqualTo(testCase.get("expect").asBoolean());
            case "ejectionMillis" -> {
                HealthSettings settings = HealthSettings.defaults();
                long base = inputs.get("baseEjectionMillis").asLong();
                long cap = inputs.get("maxEjectionMillis").asLong();
                HealthSettings tuned = new HealthSettings(settings.windowMillis(),
                        settings.bucketCount(), settings.minimumSamples(),
                        settings.failureRatePercent(), settings.consecutiveFailureThreshold(),
                        base, cap, settings.probationMillis(), settings.probationDivisor(),
                        settings.outlierMarginPercent(), settings.outlierMinimumNodes(),
                        settings.maxEjectionPercent(), settings.ejectionResetMillis(),
                        settings.resetOnPlacementReentry());
                assertThat(tuned.ejectionMillis(inputs.get("ejectionCount").asInt()))
                        .isEqualTo(testCase.get("expect").asLong());
            }
            case "probeAdmitted" -> assertThat(
                    inputs.get("probeCounterAfterIncrement").asInt()
                            % inputs.get("probationDivisor").asInt() == 1)
                    .isEqualTo(testCase.get("expect").asBoolean());
            case "peerMedian" -> {
                List<Integer> values = new java.util.ArrayList<>(
                        inputs.array("values").elements().stream().map(JsonValue::asInt).toList());
                values.sort(java.util.Comparator.naturalOrder());
                assertThat(values.get((values.size() - 1) / 2))
                        .isEqualTo(testCase.get("expect").asInt());
            }
            case "isOutlier" -> assertThat(
                    inputs.get("failurePercent").asInt()
                            >= inputs.get("peerMedian").asInt()
                            + inputs.get("outlierMarginPercent").asInt())
                    .isEqualTo(testCase.get("expect").asBoolean());
            default -> throw new AssertionError(
                    "the driver implements no formula " + testCase.text("formula"));
        }
    }
}
