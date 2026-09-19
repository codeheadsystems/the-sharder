package com.codeheadsystems.sharder.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.sharder.core.internal.document.Digests;
import com.codeheadsystems.sharder.core.internal.document.TopologyLoader;
import com.codeheadsystems.sharder.core.internal.json.JcsWriter;
import com.codeheadsystems.sharder.core.internal.json.JsonValue;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import com.codeheadsystems.sharder.core.internal.route.PlacementDecision;
import com.codeheadsystems.sharder.error.ErrorCode;
import java.util.List;

/**
 * One simulation scenario, replayed step by step.
 *
 * <p>A scenario is a sequence of actions against one library instance, and the state a step leaves
 * behind is what the next step reads. The driver replays the actions it implements and reports the
 * ones it does not, which is how a scenario covering several surfaces runs against a port that
 * exposes some of them.
 */
final class ScenarioRunner {

    private final VectorSource source;

    ScenarioRunner(VectorSource source) {
        this.source = source;
    }

    /** One scenario's steps, replayed in order, answering the count this driver skipped. */
    int run(String path) {
        JsonObject scenario = source.readObject(path);
        TopologyLoader loader = new TopologyLoader();
        int skipped = 0;
        List<JsonValue> steps = scenario.array("steps").elements();
        for (int index = 0; index < steps.size(); index++) {
            JsonObject step = steps.get(index).asObject();
            switch (step.text("action")) {
                case "installTopology" -> installTopology(loader, step, index);
                case "route" -> route(loader, step, index);
                default -> skipped++;
            }
        }
        return skipped;
    }

    private void installTopology(TopologyLoader loader, JsonObject step, int index) {
        JsonObject document = step.find("document").isPresent()
                ? step.object("document")
                : source.readObject(step.text("topology"));
        JsonObject expect = step.object("expect");

        expect.find("digest").ifPresent(value ->
                assertThat(Digests.of(JcsWriter.canonicalise(document)).toHex())
                        .as("step %d digest", index).isEqualTo(value.asText()));

        TopologyLoader.Arrival arrival = loader.accept(document);
        assertThat(arrival.outcome().spelling()).as("step %d outcome", index)
                .isEqualTo(expect.text("outcome"));

        JsonValue condition = expect.get("condition");
        if (condition.isNull()) {
            assertThat(arrival.condition()).as("step %d condition", index).isEmpty();
        } else {
            JsonObject expected = condition.asObject();
            ErrorCode raised = arrival.condition().orElseThrow();
            assertThat(raised.code()).as("step %d condition code", index)
                    .isEqualTo(expected.get("code").asInt());
            assertThat(raised.errorName()).as("step %d condition name", index)
                    .isEqualTo(expected.text("name"));
            expected.find("detail").ifPresent(value ->
                    assertThat(arrival.detail()).as("step %d condition detail", index)
                            .contains(value.asText()));
        }

        expect.find("epochInForce").ifPresent(value ->
                assertThat(loader.epochInForce().orElseThrow()).as("step %d epochInForce", index)
                        .isEqualTo(value.asLong()));
    }

    private void route(TopologyLoader loader, JsonObject step, int index) {
        JsonObject expect = step.object("expect");
        PlacementDecision decision = loader.engine().orElseThrow()
                .route(PlaceVectors.octets(step.object("key")));

        expect.find("shard").ifPresent(value ->
                assertThat(decision.shard()).as("step %d shard", index).contains(value.asText()));
        expect.find("token").ifPresent(value -> {
            JsonObject token = value.asObject();
            assertThat(decision.topologyId()).as("step %d token.topologyId", index)
                    .isEqualTo(token.text("topologyId"));
            assertThat(decision.epoch()).as("step %d token.epoch", index)
                    .isEqualTo(token.get("epoch").asLong());
        });
        expect.find("preferenceList").ifPresent(value ->
                assertThat(decision.preferenceList().stream()
                        .map(com.codeheadsystems.sharder.NodeId::asText).toList())
                        .as("step %d preferenceList", index)
                        .isEqualTo(value.asArray().texts()));
        expect.find("replicaCount").ifPresent(value ->
                assertThat(decision.replicaCount()).as("step %d replicaCount", index)
                        .isEqualTo(value.asInt()));
    }
}
