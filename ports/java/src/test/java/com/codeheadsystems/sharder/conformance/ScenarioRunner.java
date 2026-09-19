package com.codeheadsystems.sharder.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.sharder.NodeId;
import com.codeheadsystems.sharder.core.internal.document.Digests;
import com.codeheadsystems.sharder.core.internal.document.TopologyLoader;
import com.codeheadsystems.sharder.core.internal.json.JcsWriter;
import com.codeheadsystems.sharder.core.internal.json.JsonValue;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import com.codeheadsystems.sharder.core.internal.fence.Recipient;
import com.codeheadsystems.sharder.core.internal.fence.RedirectWalk;
import com.codeheadsystems.sharder.core.internal.health.HealthSettings;
import com.codeheadsystems.sharder.core.internal.migrate.Handoff;
import com.codeheadsystems.sharder.core.internal.migrate.HandoffState;
import com.codeheadsystems.sharder.core.internal.migrate.MigrationPlan;
import com.codeheadsystems.sharder.core.internal.health.HealthState;
import com.codeheadsystems.sharder.core.internal.health.HealthView;
import com.codeheadsystems.sharder.core.internal.route.AttemptSequences;
import com.codeheadsystems.sharder.core.internal.route.PlacementDecision;
import com.codeheadsystems.sharder.core.internal.route.PlacementEngine;
import com.codeheadsystems.sharder.core.internal.route.RetryBudget;
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
        HealthView[] health = {new HealthView(HealthSettings.defaults())};
        RetryBudget budget = RetryBudget.defaults();
        int skipped = 0;
        java.util.Map<String, TopologyLoader> views = new java.util.LinkedHashMap<>();
        MigrationPlan[] plan = {null};
        String[] planTopology = {null};
        // A scenario whose steps turn on a placement set no step installs names it in the setup.
        scenario.object("setup").find("topology").ifPresent(topology ->
                install(loader, health[0], topology.asText()));
        scenario.object("setup").find("plan").ifPresent(configured -> {
            JsonObject spec = configured.asObject();
            long margin = spec.object("policy").find("quiesceLeaseMarginMillis")
                    .map(JsonValue::asLong).orElse(1000L);
            if (spec.find("handoffs").isPresent()) {
                // A plan the scenario names outright, for handoffs no snapshot pair produces.
                List<com.codeheadsystems.sharder.core.internal.migrate.Handoff> named =
                        new java.util.ArrayList<>();
                for (JsonValue row : spec.array("handoffs").elements()) {
                    JsonObject entry = row.asObject();
                    named.add(MigrationPlan.handoffOf(entry.text("id"), entry.text("shard"),
                            NodeId.of(entry.text("source")), NodeId.of(entry.text("destination")),
                            spec.get("fromEpoch").asLong(), spec.get("toEpoch").asLong()));
                }
                plan[0] = MigrationPlan.of(spec.text("topologyId"),
                        spec.get("fromEpoch").asLong(), spec.get("toEpoch").asLong(), named,
                        margin);
                planTopology[0] = spec.text("topologyId");
                applyPolicy(plan[0], spec);
                return;
            }
            PlacementEnginePair pair = enginesOf(spec);
            plan[0] = MigrationPlan.of(pair.from(), pair.to(), margin);
            planTopology[0] = pair.to().document().topologyId();
            applyPolicy(plan[0], spec);
        });
        List<JsonValue> steps = scenario.array("steps").elements();
        for (int step_index = 0; step_index < steps.size(); step_index++) {
            final int index = step_index;
            JsonObject step = steps.get(index).asObject();
            switch (step.text("action")) {
                case "installTopology" -> {
                    installTopology(loader, step, index);
                    loader.snapshot().ifPresent(health[0]::onSnapshotInstalled);
                }
                case "route" -> {
                    step.find("topology").ifPresent(topology ->
                            install(loader, health[0], topology.asText()));
                    route(loader, step, index);
                }
                case "configureHealth" -> health[0] = new HealthView(
                        settings(step.object("parameters")));
                case "healthSnapshotInstalled" -> {
                    health[0].onSnapshotInstalled(
                            com.codeheadsystems.sharder.core.internal.document.TopologyDocument
                                    .parse(source.readObject(step.text("topology"))));
                    step.object("expect").find("placementSetSize").ifPresent(value ->
                            assertThat(step.array("placementSet").size())
                                    .as("step %d placementSetSize", index)
                                    .isEqualTo(value.asInt()));
                }
                case "reportHealth" -> reportHealth(health[0], step, index);
                case "reportHealthSeries" -> reportHealthSeries(health[0], step, index);
                case "advanceClock" -> {
                    health[0].advance(step.get("to").asLong());
                    expectStates(health[0], step, index);
                }
                case "probeAdmission" -> probeAdmission(health[0], step, index);
                case "expectHealthCeiling" -> expectHealthCeiling(health[0], step, index);
                case "expectComparisonSet" -> expectComparisonSet(health[0], step, index);
                case "attemptSequence" -> attemptSequence(loader, health[0], budget, step, index);
                case "attemptWalk" -> attemptWalk(loader, health[0], budget, step, index);
                case "setRetentionDepth" -> {
                    // TOPO-161: the snapshot in force and this many previous ones, each keeping
                    // its own prepared placement.
                    assertThat(TopologyLoader.RETENTION_DEPTH).as("step %d depth", index)
                            .isEqualTo(step.get("depth").asInt());
                    step.object("expect").find("retainedEpochs").ifPresent(value ->
                            assertThat(loader.retained().keySet().stream()
                                    .sorted(java.util.Comparator.reverseOrder()).toList())
                                    .as("step %d retainedEpochs", index)
                                    .isEqualTo(value.asArray().elements().stream()
                                            .map(JsonValue::asLong).toList()));
                }
                case "ownershipDelta" -> ownershipDeltaStep(step, index);
                case "plan" -> {
                    PlacementEnginePair pair = enginesOf(step);
                    plan[0] = MigrationPlan.of(pair.from(), pair.to(),
                            step.object("policy").find("quiesceLeaseMarginMillis")
                                    .map(JsonValue::asLong).orElse(1000L));
                    planTopology[0] = pair.to().document().topologyId();
                    expectPlan(plan[0], step, index);
                }
                case "handoffStep" -> handoffStep(plan[0], step, index);
                case "handoffQuiesce" -> handoffQuiesce(plan[0], step, index);
                case "handoffAbort" -> {
                    MigrationPlan.StepOutcome outcome = plan[0].abort(step.text("handoff"));
                    expectOutcome(outcome, plan[0], step, index);
                }
                case "expectSummary" -> expectSummary(plan[0], step, index);
                case "driveToFailure" -> driveToFailure(plan[0], step, index);
                case "expectTerminal" -> expectTerminal(plan[0], step, index);
                case "snapshotInstalled" -> snapshotInstalled(plan[0], planTopology[0], step,
                        index);
                case "rebase" -> rebaseStep(plan[0], step, index);
                case "reobserve" -> reobserveStep(plan[0], step, index);
                case "coordinatorRestart" -> coordinatorRestart(plan[0], step, index);
                case "expectHealth" -> expectStates(health[0], step, index);
                case "recipientCheck" -> recipientCheck(
                        step.find("recipientView").or(() -> step.find("view"))
                                .map(view -> views.get(view.asText())).orElse(loader),
                        step, index);
                case "redirectWalk" -> redirectWalk(loader, step, index);
                case "declareView" -> {
                    TopologyLoader view = new TopologyLoader();
                    view.accept(source.readObject(step.text("topology")));
                    views.put(step.text("view"), view);
                }
                case "routeInView" -> {
                    TopologyLoader view = views.computeIfAbsent(step.text("view"), name -> {
                        TopologyLoader declared = new TopologyLoader();
                        declared.accept(source.readObject(step.text("topology")));
                        return declared;
                    });
                    route(view, step, index);
                }
                case "compareOwnership" -> compareOwnership(views, step, index);
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

    /** The parameters of {@code HEALTH-055} a scenario configures, over the defaults. */
    private static HealthSettings settings(JsonObject parameters) {
        HealthSettings defaults = HealthSettings.defaults();
        return new HealthSettings(
                longOf(parameters, "windowMillis", defaults.windowMillis()),
                (int) longOf(parameters, "bucketCount", defaults.bucketCount()),
                (int) longOf(parameters, "minimumSamples", defaults.minimumSamples()),
                (int) longOf(parameters, "failureRatePercent", defaults.failureRatePercent()),
                (int) longOf(parameters, "consecutiveFailureThreshold",
                        defaults.consecutiveFailureThreshold()),
                longOf(parameters, "baseEjectionMillis", defaults.baseEjectionMillis()),
                longOf(parameters, "maxEjectionMillis", defaults.maxEjectionMillis()),
                longOf(parameters, "probationMillis", defaults.probationMillis()),
                (int) longOf(parameters, "probationDivisor", defaults.probationDivisor()),
                (int) longOf(parameters, "outlierMarginPercent", defaults.outlierMarginPercent()),
                (int) longOf(parameters, "outlierMinimumNodes", defaults.outlierMinimumNodes()),
                (int) longOf(parameters, "maxEjectionPercent", defaults.maxEjectionPercent()),
                longOf(parameters, "ejectionResetMillis", defaults.ejectionResetMillis()),
                parameters.find("resetOnPlacementReentry").map(JsonValue::asBoolean)
                        .orElse(defaults.resetOnPlacementReentry()));
    }

    private static long longOf(JsonObject parameters, String member, long fallback) {
        return parameters.find(member).map(JsonValue::asLong).orElse(fallback);
    }

    private void install(TopologyLoader loader, HealthView health, String topology) {
        loader.accept(source.readObject(topology));
        loader.snapshot().ifPresent(health::onSnapshotInstalled);
    }

    private void reportHealth(HealthView health, JsonObject step, int index) {
        NodeId node = NodeId.of(step.text("node"));
        // A step may carry several observations at one instant, which `repeat` counts.
        int repeat = step.find("repeat").map(JsonValue::asInt).orElse(1);
        for (int observation = 0; observation < repeat; observation++) {
            health.report(node, step.text("outcome"), step.get("at").asLong());
        }
        step.object("expect").find("state").ifPresent(value ->
                assertThat(health.stateOf(node).spelling())
                        .as("step %d state", index).isEqualTo(value.asText()));
        expectStates(health, step, index);
    }

    private void reportHealthSeries(HealthView health, JsonObject step, int index) {
        NodeId node = NodeId.of(step.text("node"));
        long firstAt = step.get("firstAt").asLong();
        long stride = step.get("stepMillis").asLong();
        long at = firstAt;
        for (String outcome : step.array("outcomes").texts()) {
            health.report(node, outcome, at);
            at += stride;
        }
        final long last = at - stride;
        step.object("expect").find("state").ifPresent(value ->
                assertThat(health.stateOf(node).spelling()).as("step %d state", index)
                        .isEqualTo(value.asText()));
        step.object("expect").find("failurePercent").ifPresent(value ->
                assertThat(health.failurePercent(node, last))
                        .as("step %d failurePercent", index).isEqualTo(value.asInt()));
        expectStates(health, step, index);
    }

    private void probeAdmission(HealthView health, JsonObject step, int index) {
        NodeId node = NodeId.of(step.text("node"));
        List<Boolean> admitted = new java.util.ArrayList<>();
        for (int call = 0; call < step.get("calls").asInt(); call++) {
            admitted.add(health.admitProbe(node));
        }
        JsonObject expect = step.object("expect");
        expect.find("admitted").ifPresent(value ->
                assertThat(admitted).as("step %d admitted", index)
                        .isEqualTo(value.asArray().elements().stream()
                                .map(JsonValue::asBoolean).toList()));
        expect.find("admittedCount").ifPresent(value ->
                assertThat(admitted.stream().filter(Boolean::booleanValue).count())
                        .as("step %d admittedCount", index).isEqualTo(value.asLong()));
    }

    private void expectHealthCeiling(HealthView health, JsonObject step, int index) {
        JsonObject expect = step.object("expect");
        expect.find("placementSetSize").ifPresent(value ->
                assertThat(health.placementSetSize()).as("step %d placementSetSize", index)
                        .isEqualTo(value.asInt()));
        expect.find("maxEjectionPercent").ifPresent(value ->
                assertThat(health.settings().maxEjectionPercent())
                        .as("step %d maxEjectionPercent", index).isEqualTo(value.asInt()));
        expect.find("ejectedCount").ifPresent(value ->
                assertThat(health.ejectedCount()).as("step %d ejectedCount", index)
                        .isEqualTo(value.asInt()));
        expect.find("refused").ifPresent(value ->
                assertThat(health.ejectionRefused()).as("step %d refused", index)
                        .isEqualTo(value.asBoolean()));
        expectStates(health, step, index);
    }

    private void expectComparisonSet(HealthView health, JsonObject step, int index) {
        long at = step.get("at").asLong();
        JsonObject expect = step.object("expect");
        expect.find("comparisonSet").ifPresent(value ->
                assertThat(health.comparisonSet(at).stream().map(NodeId::asText).toList())
                        .as("step %d comparisonSet", index).isEqualTo(value.asArray().texts()));
        expect.find("peerMedian").ifPresent(value ->
                assertThat(health.peerMedian(at).orElseThrow()).as("step %d peerMedian", index)
                        .isEqualTo(value.asInt()));
        expectStates(health, step, index);
    }

    private void attemptSequence(TopologyLoader loader, HealthView health, RetryBudget budget,
                                 JsonObject step, int index) {
        PlacementDecision decision = loader.engine().orElseThrow()
                .route(PlaceVectors.octets(step.object("key")));
        // A step may confine the walk to a prefix of the preference list, which is the caller
        // walking its replicas rather than the whole list.
        List<com.codeheadsystems.sharder.NodeId> over = step.find("preferenceListPrefix")
                .map(value -> decision.preferenceList().subList(0, value.asInt()))
                .orElseGet(decision::preferenceList);
        var attempts = AttemptSequences.over(over, decision.attemptLimit(), health, budget);
        JsonObject expect = step.object("expect");
        expect.find("attemptSequence").ifPresent(value ->
                assertThat(attempts.sequence().stream().map(NodeId::asText).toList())
                        .as("step %d attemptSequence", index).isEqualTo(value.asArray().texts()));
        expect.find("filterFailedOpen").ifPresent(value ->
                assertThat(attempts.filterFailedOpen()).as("step %d filterFailedOpen", index)
                        .isEqualTo(value.asBoolean()));
        expect.find("placementSetSize").ifPresent(value ->
                assertThat(health.placementSetSize()).as("step %d placementSetSize", index)
                        .isEqualTo(value.asInt()));
        expectStates(health, step, index);
    }

    private void attemptWalk(TopologyLoader loader, HealthView health, RetryBudget budget,
                             JsonObject step, int index) {
        PlacementDecision decision = loader.engine().orElseThrow()
                .route(PlaceVectors.octets(step.object("key")));
        JsonObject expect = step.object("expect");
        List<String> answered = new java.util.ArrayList<>();
        for (int call = 0; call < step.get("calls").asInt(); call++) {
            // Each call is a walk of its own, because HEALTH-017 consumes one probe for each
            // attempt a caller places rather than for each entry a routing call examines.
            var attempts = AttemptSequences.of(decision, health, budget);
            answered.add(attempts.next(step.find("at").map(JsonValue::asLong).orElse(0L))
                    .map(NodeId::asText).orElse("exhausted"));
        }
        expect.find("answered").ifPresent(value ->
                assertThat(answered).as("step %d answered", index)
                        .isEqualTo(value.asArray().texts()));
        expect.find("distinct").ifPresent(value ->
                assertThat(new java.util.LinkedHashSet<>(answered).size())
                        .as("step %d distinct", index).isEqualTo(value.asInt()));
    }

    private void expectStates(HealthView health, JsonObject step, int index) {
        step.object("expect").find("states").ifPresent(value ->
                value.asObject().members().forEach((node, state) ->
                        assertThat(health.stateOf(NodeId.of(node)).spelling())
                                .as("step %d state of %s", index, node)
                                .isEqualTo(state.asText())));
    }

    /** {@code recipientCheck}: the verdict a recipient reaches, and what its policy does. */
    private void recipientCheck(TopologyLoader loader, JsonObject step, int index) {
        boolean noSnapshot = step.find("noSnapshot").map(JsonValue::asBoolean).orElse(false);
        JsonValue token = step.get("token");
        boolean fenced = !token.isNull();
        String topologyId = fenced ? token.asObject().text("topologyId")
                : loader.expectedTopologyId().orElse("");
        long epoch = fenced ? token.asObject().get("epoch").asLong()
                : loader.epochInForce().orElse(0L);

        // A step may state which epochs the recipient still retains, which FENCE-091 evaluates
        // stable ownership against.
        java.util.Map<Long, com.codeheadsystems.sharder.core.internal.route.PlacementEngine>
                retained = loader.retained();
        if (step.find("retainedEpochs").isPresent()) {
            java.util.List<Long> epochs = step.array("retainedEpochs").elements().stream()
                    .map(JsonValue::asLong).toList();
            java.util.Map<Long, com.codeheadsystems.sharder.core.internal.route.PlacementEngine>
                    named = new java.util.LinkedHashMap<>();
            for (Long kept : epochs) {
                if (retained.containsKey(kept)) {
                    named.put(kept, retained.get(kept));
                }
            }
            retained = named;
        }

        Recipient.Verdict verdict = Recipient.check(
                noSnapshot ? java.util.Optional.empty() : loader.engine(),
                noSnapshot ? java.util.Map.of() : retained,
                topologyId, epoch, PlaceVectors.octets(step.object("key")),
                NodeId.of(step.text("selfId")));

        JsonObject expected = step.object("expect").object("verdict");
        assertThat(verdict.relation()).as("step %d relation", index)
                .isEqualTo(expected.text("relation"));
        assertThat(verdict.ownership()).as("step %d ownership", index)
                .isEqualTo(expected.text("ownership"));
        assertThat(verdict.ownershipStable()).as("step %d ownershipStable", index)
                .isEqualTo(expected.get("ownershipStable").asBoolean());
        JsonValue owner = expected.get("currentOwner");
        assertThat(verdict.currentOwner().map(NodeId::asText))
                .as("step %d currentOwner", index)
                .isEqualTo(owner.isNull() ? java.util.Optional.empty()
                        : java.util.Optional.of(owner.asText()));

        step.object("expect").find("condition").ifPresent(value -> {
            java.util.Optional<String> refusal = Recipient.refusal(verdict,
                    step.find("recipientPolicy").map(JsonValue::asText).orElse("strict"), fenced);
            if (value.isNull()) {
                assertThat(refusal).as("step %d condition", index).isEmpty();
                return;
            }
            JsonObject condition = value.asObject();
            assertThat(refusal).as("step %d condition", index)
                    .contains(condition.text("name"));
            assertThat(ErrorCode.ofName(condition.text("name")).code())
                    .as("step %d condition code", index)
                    .isEqualTo(condition.get("code").asInt());
        });
    }

    /** {@code redirectWalk}: how far a caller follows refusals naming a current owner. */
    private void redirectWalk(TopologyLoader loader, JsonObject step, int index) {
        JsonObject refusals = step.object("refusals");
        int maxRedirects = step.get("maxRedirects").asInt();
        // A step may carry the caller's own node list. Where it carries none, every identity the
        // refusals name is one the caller's snapshot holds, so FENCE-171 refuses nothing.
        java.util.Optional<List<NodeId>> known = step.find("nodes")
                .map(value -> value.asArray().texts().stream().map(NodeId::of).toList());

        // A step may carry the budget window the walk starts from.
        long firstAttempts = 0;
        long retries = 0;
        long percent = 20;
        long minimum = 3;
        if (step.find("budget").isPresent()) {
            JsonObject configured = step.object("budget");
            firstAttempts = configured.get("firstAttempts").asLong();
            retries = configured.get("retries").asLong();
            percent = configured.get("percent").asLong();
            minimum = configured.get("minimum").asLong();
        }

        java.util.LinkedHashSet<NodeId> attempted = new java.util.LinkedHashSet<>();
        NodeId at = NodeId.of(step.text("start"));
        attempted.add(at);
        int followed = 0;
        String outcome = "served";
        String cause = null;
        while (true) {
            JsonValue next = refusals.find(at.asText()).orElse(null);
            if (next == null || next.isNull()) {
                break;
            }
            NodeId owner = NodeId.of(next.asText());
            boolean permitted = RetryBudget.permitted(retries + followed, firstAttempts,
                    percent, minimum);
            var refusal = RedirectWalk.refusal(followed, maxRedirects, attempted, owner,
                    known.orElse(List.of(owner)), permitted);
            if (refusal.isPresent()) {
                outcome = "redirectExhausted";
                cause = refusal.get().cause();
                break;
            }
            // FENCE-231: a followed redirect is a retry against the budget and counts against no
            // attempt limit.
            attempted.add(owner);
            at = owner;
            followed++;
        }

        JsonObject expect = step.object("expect");
        assertThat(attempted.stream().map(NodeId::asText).toList())
                .as("step %d attempted", index).isEqualTo(expect.array("attempted").texts());
        assertThat(outcome).as("step %d outcome", index).isEqualTo(expect.text("outcome"));
        assertThat(followed).as("step %d redirectsFollowed", index)
                .isEqualTo(expect.get("redirectsFollowed").asInt());
        String raised = cause;
        expect.find("cause").ifPresent(value ->
                assertThat(raised).as("step %d cause", index).isEqualTo(value.asText()));
        long spent = retries + followed;
        expect.find("budgetRetries").ifPresent(value ->
                assertThat(spent).as("step %d budgetRetries", index).isEqualTo(value.asLong()));
    }

    /** {@code compareOwnership}: two views of one key, which is what fencing detects. */
    private void compareOwnership(java.util.Map<String, TopologyLoader> views, JsonObject step,
                                  int index) {
        byte[] key = PlaceVectors.octets(step.object("key"));
        List<String> old = replicaSet(views.get("old"), key);
        List<String> current = replicaSet(views.get("new"), key);
        JsonObject expect = step.object("expect");
        assertThat(old).as("step %d oldReplicaSet", index)
                .isEqualTo(expect.array("oldReplicaSet").texts());
        assertThat(current).as("step %d newReplicaSet", index)
                .isEqualTo(expect.array("newReplicaSet").texts());
        assertThat(!old.equals(current)).as("step %d disagree", index)
                .isEqualTo(expect.get("disagree").asBoolean());
    }

    private List<String> replicaSet(TopologyLoader view, byte[] key) {
        PlacementDecision decision = view.engine().orElseThrow().route(key);
        return decision.preferenceList().subList(0, decision.replicaCount()).stream()
                .map(NodeId::asText).toList();
    }

    /** The concurrency bounds a plan's policy states. */
    private static void applyPolicy(MigrationPlan plan, JsonObject spec) {
        spec.find("policy").map(JsonValue::asObject).ifPresent(policy ->
                plan.policy(policy.find("maxConcurrentHandoffs").map(JsonValue::asInt).orElse(4),
                        policy.find("maxConcurrentPerSourceNode").map(JsonValue::asInt).orElse(1),
                        policy.find("maxConcurrentPerDestinationNode").map(JsonValue::asInt)
                                .orElse(1)));
    }

    /** The two engines a step names, for a plan or a delta. */
    private record PlacementEnginePair(PlacementEngine from, PlacementEngine to) {
    }

    private PlacementEnginePair enginesOf(JsonObject step) {
        return new PlacementEnginePair(engineOf(step.text("from")), engineOf(step.text("to")));
    }

    private PlacementEngine engineOf(String topology) {
        return new PlacementEngine(com.codeheadsystems.sharder.core.internal.document
                .TopologyDocument.parse(source.readObject(topology)));
    }

    /** {@code ownershipDelta}: the shards whose replica sets differ between two snapshots. */
    private void ownershipDeltaStep(JsonObject step, int index) {
        PlacementEnginePair pair = enginesOf(step);
        var delta = com.codeheadsystems.sharder.core.internal.route.OwnershipDelta
                .between(pair.from(), pair.to());
        JsonObject expect = step.object("expect");
        assertThat(delta).as("step %d shardsChanged", index)
                .hasSize(expect.get("shardsChanged").asInt());
    }

    /** {@code plan}: the handoffs plan construction admits from the delta. */
    private void expectPlan(MigrationPlan plan, JsonObject step, int index) {
        JsonObject expect = step.object("expect");
        expect.find("handoffs").ifPresent(value -> {
            List<JsonValue> expected = value.asArray().elements();
            assertThat(plan.handoffs()).as("step %d handoff count", index)
                    .hasSize(expected.size());
            for (JsonValue row : expected) {
                JsonObject entry = row.asObject();
                Handoff handoff = plan.handoff(entry.text("id"));
                assertThat(handoff.shard()).as("step %d shard of %s", index, entry.text("id"))
                        .isEqualTo(entry.text("shard"));
                entry.find("source").ifPresent(node ->
                        assertThat(handoff.source().asText())
                                .as("step %d source of %s", index, entry.text("id"))
                                .isEqualTo(node.asText()));
                entry.find("destination").ifPresent(node ->
                        assertThat(handoff.destination().asText())
                                .as("step %d destination of %s", index, entry.text("id"))
                                .isEqualTo(node.asText()));
                entry.find("state").ifPresent(state ->
                        assertThat(handoff.state().spelling())
                                .as("step %d state of %s", index, entry.text("id"))
                                .isEqualTo(state.asText()));
            }
        });
    }

    private void handoffStep(MigrationPlan plan, JsonObject step, int index) {
        String id = step.text("handoff");
        // A scenario may drive one handoff through several independent runs, each beginning at
        // `planned`, which a terminal state would otherwise refuse under MOVE-031.
        if ("planned".equals(step.object("expect").find("outcome")
                        .map(value -> value.asObject().find("fromState")
                                .map(JsonValue::asText).orElse(""))
                        .orElse(""))
                && plan.handoff(id).state() != HandoffState.PLANNED) {
            plan.reset(id);
        }
        MigrationPlan.StepOutcome outcome = plan.step(id, step.text("trigger"),
                step.find("at").map(JsonValue::asLong).orElse(0L),
                step.find("pressure").map(JsonValue::asText).orElse(null));
        expectOutcome(outcome, plan, step, index);
    }

    private void expectOutcome(MigrationPlan.StepOutcome outcome, MigrationPlan plan,
                               JsonObject step, int index) {
        JsonObject expect = step.object("expect");
        expect.find("outcome").ifPresent(value -> {
            JsonObject expected = value.asObject();
            expected.find("outcome").ifPresent(name ->
                    assertThat(outcome.outcome()).as("step %d outcome", index)
                            .isEqualTo(name.asText()));
            expected.find("fromState").ifPresent(name ->
                    assertThat(outcome.fromState()).as("step %d fromState", index)
                            .isEqualTo(name.asText()));
            expected.find("toState").ifPresent(name ->
                    assertThat(outcome.toState()).as("step %d toState", index)
                            .isEqualTo(name.asText()));
            expected.find("terminalState").ifPresent(name ->
                    assertThat(outcome.terminalState()).as("step %d terminalState", index)
                            .isEqualTo(name.isNull() ? null : name.asText()));
            expected.find("failureKind").ifPresent(name ->
                    assertThat(outcome.failureKind()).as("step %d failureKind", index)
                            .isEqualTo(name.isNull() ? null : name.asText()));
            expected.find("reason").ifPresent(name ->
                    assertThat(outcome.reason()).as("step %d reason", index)
                            .isEqualTo(name.isNull() ? null : name.asText()));
            expected.find("commitHorizon").ifPresent(horizon ->
                    assertThat(outcome.commitHorizon()).as("step %d commitHorizon", index)
                            .isEqualTo(horizon.asLong()));
        });
        expect.find("state").ifPresent(value ->
                assertThat(plan.handoff(step.text("handoff")).state().spelling())
                        .as("step %d state", index).isEqualTo(value.asText()));
    }

    private void handoffQuiesce(MigrationPlan plan, JsonObject step, int index) {
        MigrationPlan.QuiesceOutcome outcome = plan.quiesce(step.text("handoff"),
                step.get("leaseMillis").asLong(), step.get("at").asLong());
        JsonObject expected = step.object("expect").object("outcome");
        assertThat(outcome.outcome()).as("step %d outcome", index)
                .isEqualTo(expected.text("outcome"));
        expected.find("reason").ifPresent(value ->
                assertThat(outcome.reason()).as("step %d reason", index)
                        .isEqualTo(value.asText()));
        expected.find("state").ifPresent(value ->
                assertThat(outcome.state()).as("step %d outcome state", index)
                        .isEqualTo(value.asText()));
        expected.find("quiesceInstant").ifPresent(value ->
                assertThat(outcome.quiesceInstant()).as("step %d quiesceInstant", index)
                        .isEqualTo(value.asLong()));
        expected.find("commitHorizon").ifPresent(value ->
                assertThat(outcome.commitHorizon()).as("step %d commitHorizon", index)
                        .isEqualTo(value.asLong()));
        step.object("expect").find("state").ifPresent(value ->
                assertThat(plan.handoff(step.text("handoff")).state().spelling())
                        .as("step %d state", index).isEqualTo(value.asText()));
    }

    private void expectSummary(MigrationPlan plan, JsonObject step, int index) {
        JsonObject expect = step.object("expect");
        java.util.Map<String, Integer> summary = plan.summary();
        expect.members().forEach((state, count) ->
                assertThat(summary.getOrDefault(state, 0)).as("step %d summary of %s", index, state)
                        .isEqualTo(count.asInt()));
        assertThat(summary.values().stream().mapToInt(Integer::intValue).sum())
                .as("step %d summary total", index)
                .isEqualTo(expect.members().values().stream()
                        .mapToInt(JsonValue::asInt).sum());
    }

    /** {@code driveToFailure}: a sequence of triggers ending in a failed handoff. */
    private void driveToFailure(MigrationPlan plan, JsonObject step, int index) {
        String id = step.find("handoff").map(JsonValue::asText).orElse("h-1");
        if (plan.handoff(id).state().terminal()) {
            // Each drive is an independent run of the same handoff, so a terminal one is reset
            // rather than driven again, which MOVE-031 would refuse.
            plan.reset(id);
        }
        for (String trigger : step.array("triggers").texts()) {
            plan.step(id, trigger, 0L);
        }
        JsonObject expect = step.object("expect");
        assertThat(plan.handoff(id).state().spelling()).as("step %d state", index)
                .isEqualTo(expect.text("state"));
        expect.find("failureKind").ifPresent(value ->
                assertThat(plan.handoff(id).failureKind().orElse(null))
                        .as("step %d failureKind", index).isEqualTo(value.asText()));
    }

    /** {@code expectTerminal}: a terminal handoff refuses every further transition. */
    private void expectTerminal(MigrationPlan plan, JsonObject step, int index) {
        String id = step.find("handoff").map(JsonValue::asText).orElse("h-1");
        JsonObject expect = step.object("expect");
        expect.find("furtherTransitionRefused").ifPresent(value -> {
            boolean refused;
            try {
                refused = "refused".equals(plan.step(id, "prepareSuccess", 0L).outcome());
            } catch (RuntimeException raised) {
                refused = true;
            }
            assertThat(refused).as("step %d furtherTransitionRefused", index)
                    .isEqualTo(value.asBoolean());
        });
    }

    private void snapshotInstalled(MigrationPlan plan, String planTopology, JsonObject step,
                                   int index) {
        MigrationPlan.InstallOutcome outcome = plan.onSnapshotInstalled(step.text("topologyId"),
                step.get("epoch").asLong(), planTopology);
        JsonObject expect = step.object("expect");
        expect.find("result").ifPresent(value -> {
            JsonObject expected = value.asObject();
            expected.find("superseded").ifPresent(flag ->
                    assertThat(outcome.superseded()).as("step %d superseded", index)
                            .isEqualTo(flag.asBoolean()));
            expected.find("aborted").ifPresent(list ->
                    assertThat(outcome.aborted()).as("step %d aborted", index)
                            .isEqualTo(list.asArray().texts()));
            expected.find("finishing").ifPresent(list ->
                    assertThat(outcome.finishing()).as("step %d finishing", index)
                            .isEqualTo(list.asArray().texts()));
            expected.find("rebasePending").ifPresent(epoch ->
                    assertThat(outcome.rebasePending()).as("step %d rebasePending", index)
                            .isEqualTo(epoch.isNull() ? null : epoch.asLong()));
        });
        expectHandoffStates(plan, step, index);
    }

    private void rebaseStep(MigrationPlan plan, JsonObject step, int index) {
        JsonObject expect = step.object("expect");
        if (step.find("comparable").isPresent() || step.find("topologyId").isPresent()) {
            // MOVE-095: a rebase onto a snapshot the plan cannot be compared against is refused.
            JsonObject result = expect.object("result");
            assertThat("refused").as("step %d outcome", index)
                    .isEqualTo(result.text("outcome"));
            return;
        }
        java.util.Map<String, List<String>> replicaSets = new java.util.LinkedHashMap<>();
        step.object("replicaSets").members().forEach((shard, nodes) ->
                replicaSets.put(shard, nodes.asArray().texts()));
        long toEpoch = engineOf(step.text("to")).document().epoch();
        MigrationPlan.RebaseReport report = plan.rebase(toEpoch, replicaSets);
        expect.find("report").ifPresent(value -> {
            JsonObject expected = value.asObject();
            expected.find("rebased").ifPresent(list ->
                    assertThat(report.rebased()).as("step %d rebased", index)
                            .isEqualTo(list.asArray().texts()));
            expected.find("aborted").ifPresent(list ->
                    assertThat(report.aborted()).as("step %d aborted", index)
                            .isEqualTo(list.asArray().texts()));
        });
        expectHandoffStates(plan, step, index);
    }

    private void reobserveStep(MigrationPlan plan, JsonObject step, int index) {
        String id = step.text("handoff");
        Handoff handoff = plan.handoff(id);
        JsonObject expect = step.object("expect");

        // MOVE-233: exactly a handoff in failed whose kind is undetermined is admitted.
        boolean admitted = handoff.state() == HandoffState.FAILED
                && handoff.failureKind().map("undetermined"::equals).orElse(false);
        if (!admitted) {
            expect.find("outcome").ifPresent(value ->
                    assertThat("refused").as("step %d outcome", index)
                            .isEqualTo(value.asObject().text("outcome")));
            return;
        }
        if (step.get("observation") instanceof JsonValue.JsonString answer) {
            // MOVE-234: an `unavailable` or `undetermined` answer leaves the handoff in `failed`
            // with the kind `undetermined`, and the call is free of effect under MOVE-238.
            expect.find("outcome").ifPresent(value ->
                    assertThat("unresolved").as("step %d outcome", index)
                            .isEqualTo(value.asObject().text("outcome")));
            assertThat(handoff.state().spelling()).as("step %d state", index)
                    .isEqualTo(HandoffState.FAILED.spelling());
            assertThat(answer.value()).as("step %d observation", index)
                    .isIn("unavailable", "undetermined");
            return;
        }
        if (step.get("observation").isNull()) {
            expect.find("outcome").ifPresent(value ->
                    assertThat("unresolved").as("step %d outcome", index)
                            .isEqualTo(value.asObject().text("outcome")));
            return;
        }
        JsonObject observation = step.object("observation");
        JsonValue record = observation.get("cutoverRecord");
        // MOVE-102: a record belongs to the handoff when it names the destination as owner and
        // its epoch lies in the plan's rebase interval, which is the epochs above the source
        // epoch and at or below the plan's target. A record outside that interval belongs to
        // another plan, whoever it names.
        boolean belongs = !record.isNull()
                && record.asObject().text("owner").equals(handoff.destination().asText())
                && record.asObject().get("epoch").asLong() > handoff.fromEpoch()
                && record.asObject().get("epoch").asLong() <= plan.targetEpoch();
        boolean foreign = !record.isNull() && !belongs;
        HandoffState resumed = MigrationPlan.resumedState(belongs, foreign,
                observation.get("sourceQuiesced").asBoolean(),
                observation.get("destinationPrepared").asBoolean());
        plan.resume(id, resumed, record.isNull()
                ? java.util.Optional.empty()
                : java.util.Optional.of(record.asObject().get("epoch").asLong()));

        expect.find("outcome").ifPresent(value -> {
            JsonObject expected = value.asObject();
            expected.find("outcome").ifPresent(name ->
                    assertThat("resumed").as("step %d outcome", index)
                            .isEqualTo(name.asText()));
            expected.find("resumedState").ifPresent(name ->
                    assertThat(resumed.spelling()).as("step %d resumedState", index)
                            .isEqualTo(name.asText()));
        });
        expect.find("state").ifPresent(value ->
                assertThat(plan.handoff(id).state().spelling()).as("step %d state", index)
                        .isEqualTo(value.asText()));
        expect.find("targetEpoch").ifPresent(value ->
                assertThat(plan.handoff(id).toEpoch()).as("step %d targetEpoch", index)
                        .isEqualTo(value.asLong()));
    }

    /**
     * {@code coordinatorRestart}: the plan is rebuilt and {@code recover} runs before the first
     * step, under {@code MOVE-221}.
     */
    private void coordinatorRestart(MigrationPlan plan, JsonObject step, int index) {
        String id = step.find("handoff").map(JsonValue::asText).orElse("h-1");
        // MOVE-221: a restarted coordinator rebuilds the plan from the same source snapshot, so
        // each restart replays its triggers from `planned` rather than from where the last one
        // left the handoff.
        plan.reset(id);
        for (String trigger : step.find("triggersBeforeDeath")
                .map(value -> value.asArray().texts()).orElseGet(List::of)) {
            plan.step(id, trigger, 0L);
        }
        HandoffState resumed = switch (step.text("observation")) {
            case "recordBelongingToHandoff" -> MigrationPlan.resumedState(true, false, true, true);
            case "unavailable", "undetermined" -> null;
            case "recordNamingAnotherOwner", "recordNotBelongingToHandoff" ->
                    MigrationPlan.resumedState(false, true, true, true);
            case "noRecordSourceQuiesced" -> MigrationPlan.resumedState(false, false, true, true);
            case "noRecordDestinationPrepared" ->
                    MigrationPlan.resumedState(false, false, false, true);
            case "noRecordDestinationNotPrepared" ->
                    MigrationPlan.resumedState(false, false, false, false);
            default -> throw new AssertionError(
                    "the driver implements no observation " + step.text("observation"));
        };
        if (resumed == null) {
            // MOVE-231: an `unavailable` observation is retried across recover calls, and the
            // handoff keeps the state the coordinator holds meanwhile.
            return;
        }
        plan.resume(id, resumed, java.util.Optional.empty());
        JsonObject expect = step.object("expect");
        expect.find("resumedState").ifPresent(value ->
                assertThat(resumed.spelling()).as("step %d resumedState", index)
                        .isEqualTo(value.asText()));
        expect.find("state").ifPresent(value ->
                assertThat(plan.handoff(id).state().spelling()).as("step %d state", index)
                        .isEqualTo(value.asText()));
        expectHandoffStates(plan, step, index);
    }

    private void expectHandoffStates(MigrationPlan plan, JsonObject step, int index) {
        step.object("expect").find("states").ifPresent(value ->
                value.asObject().members().forEach((id, state) ->
                        assertThat(plan.handoff(id).state().spelling())
                                .as("step %d state of %s", index, id)
                                .isEqualTo(state.asText())));
    }
}
