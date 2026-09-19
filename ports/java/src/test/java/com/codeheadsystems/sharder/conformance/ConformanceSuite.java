package com.codeheadsystems.sharder.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.codeheadsystems.sharder.conformance.VectorManifest.FileEntry;
import com.codeheadsystems.sharder.core.internal.hash.DomainHash;
import com.codeheadsystems.sharder.core.internal.hash.Frame;
import com.codeheadsystems.sharder.core.internal.hash.SipHash24;
import com.codeheadsystems.sharder.core.internal.hash.U64;
import com.codeheadsystems.sharder.core.internal.json.JsonValue;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The conformance suite, driven from {@code manifest.json}.
 *
 * <p>The driver contract of {@code 30-conformance.md} is the whole of what this class does: read
 * the manifest, read its level table, dispatch on each vector file's {@code kind}, and compare each
 * case's result to its {@code expect} field by field. A file whose kind this driver does not
 * implement fails rather than being skipped, which is what keeps a level from passing on a subset
 * of itself.
 *
 * <p>{@link #DECLARED} is what the port reaches today. A level absent from it is not run, and the
 * port declares no conformance until it reaches {@code hash}, {@code place}, {@code core}, and
 * {@code scale}, which {@code CORE-110} makes mandatory.
 */
class ConformanceSuite {

    /** The levels this port runs, each carrying the levels it requires. */
    private static final Set<String> DECLARED = Set.of("core");

    private static final HexFormat HEX = HexFormat.of();

    private final VectorSource source = VectorSource.fromProperty();
    private final VectorManifest manifest = VectorManifest.read(source);

    @TestFactory
    Stream<DynamicNode> conformance() {
        Set<String> running = DECLARED.stream()
                .flatMap(level -> manifest.withRequired(level).stream())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return manifest.levels().stream()
                .filter(level -> running.contains(level.name()))
                .map(level -> dynamicContainer(
                        "level " + level.name(),
                        Stream.of(
                                        Stream.of(levelCounts(level.name())),
                                        manifest.filesAt(level.name()).stream()
                                                .map(this::vectorFile),
                                        manifest.scenariosAt(level.name()).stream()
                                                .map(this::scenario))
                                .flatMap(nodes -> nodes)));
    }

    private DynamicNode levelCounts(String level) {
        return dynamicTest("the level carries the files and cases the manifest counts", () -> {
            List<FileEntry> files = manifest.filesAt(level);
            int cases = files.stream().mapToInt(FileEntry::caseCount).sum();
            assertThat(files).hasSize(manifest.level(level).vectorFiles());
            assertThat(cases).isEqualTo(manifest.level(level).vectorCases());
        });
    }

    private DynamicNode scenario(VectorManifest.ScenarioEntry entry) {
        return dynamicTest("scenario " + entry.scenario(), () -> {
            int skipped = new ScenarioRunner(source).run(entry.file());
            // A scenario covering several surfaces reports the steps a port does not implement,
            // rather than failing on them as an unknown vector kind does.
            assertThat(skipped).as("steps this driver does not implement").isZero();
        });
    }

    private DynamicNode vectorFile(FileEntry entry) {
        JsonObject file = source.readObject(entry.file());
        PlaceVectors place = new PlaceVectors(file);
        CoreVectors core = new CoreVectors(source);
        List<JsonValue> cases = file.array("cases").elements();
        Stream<DynamicNode> integrity = Stream.of(
                dynamicTest("the file matches the digest the manifest holds",
                        () -> assertThat(source.digestOf(entry.file())).isEqualTo(entry.sha256())),
                dynamicTest("the file carries the case count the manifest holds",
                        () -> assertThat(cases).hasSize(entry.caseCount())));
        Stream<DynamicNode> vectors = cases.stream()
                .map(JsonValue::asObject)
                .map(testCase -> dynamicTest(
                        testCase.text("name") + " " + testCase.array("requirements").texts(),
                        () -> runCase(entry.kind(), testCase, place, core)));
        return dynamicContainer(entry.vectorSet() + " (" + entry.file() + ")",
                Stream.concat(integrity, vectors));
    }

    private void runCase(String kind, JsonObject testCase, PlaceVectors place,
                         CoreVectors core) {
        switch (kind) {
            case "siphash" -> sipHashCase(testCase);
            case "hash" -> hashConstructionCase(testCase);
            case "keyTransform" -> place.keyTransform(testCase);
            case "routing" -> place.routing(testCase);
            case "shards" -> place.shards(testCase);
            case "permutation" -> place.permutation(testCase);
            case "collidingKeys" -> place.collidingKeys(testCase);
            case "movement" -> place.movement(testCase);
            case "tieBreak" -> place.tieBreak(testCase);
            case "stages" -> place.stages(testCase);
            case "defaults" -> place.defaults(testCase);
            case "pinShard" -> place.pinShard(testCase);
            case "formula" -> PlaceVectors.formula(testCase);
            case "digest" -> core.digest(testCase);
            case "validation" -> core.validation(testCase);
            case "errorTaxonomy" -> core.errorTaxonomy(testCase);
            case "observabilityInventory" -> core.observabilityInventory(testCase);
            case "publicationEvents" -> core.publicationEvents(testCase);
            case "ownershipDelta" -> core.ownershipDelta(testCase);
            case "propertyWitness" -> core.propertyWitness(testCase);
            default -> throw new AssertionError(
                    "the driver implements no vector kind " + kind);
        }
    }

    /** {@code siphash}: a key and a message against the 64-bit output. */
    private void sipHashCase(JsonObject testCase) {
        byte[] key = HEX.parseHex(testCase.text("key"));
        byte[] message = HEX.parseHex(testCase.text("message"));
        long computed = SipHash24.hash(SipHash24.keyLow(key), SipHash24.keyHigh(key), message, 0,
                message.length);
        assertThat(U64.toHex(computed)).isEqualTo(testCase.text("expect"));
    }

    /** {@code hash}: a domain tag and framed fields against the framed octets and the result. */
    private void hashConstructionCase(JsonObject testCase) {
        List<String> fields = testCase.array("fields").texts();
        byte[][] octets = fields.stream().map(HEX::parseHex).toArray(byte[][]::new);
        byte[] framed = Frame.of(octets);
        assertThat(HEX.formatHex(framed))
                .as("the framed message of HASH-021")
                .isEqualTo(testCase.text("framedMessage"));

        DomainHash hash = DomainHash.ofSeed(testCase.text("seed"));
        assertThat(U64.toHex(hash.hash(framed)))
                .as("the output over the framed message")
                .isEqualTo(testCase.text("expect"));

        // The named function of HASH-030 builds that frame from its own arguments, tag included,
        // so a port whose tag or field order is wrong fails here while the framing above passes.
        String function = testCase.text("function");
        long viaFunction = switch (function) {
            case "keyHash" -> hash.keyHash(octets[1]);
            case "ringToken" -> hash.ringToken(octets[1], index(octets[2]));
            case "rvScore" -> hash.rvScore(octets[1], octets[2], index(octets[3]));
            default -> throw new AssertionError("the driver implements no function " + function);
        };
        assertThat(U64.toHex(viaFunction))
                .as("the output of " + function)
                .isEqualTo(testCase.text("expect"));
    }

    private static int index(byte[] u32be) {
        assertThat(u32be).as("a u32be field is four octets").hasSize(Frame.PREFIX);
        return ((u32be[0] & 0xff) << 24) | ((u32be[1] & 0xff) << 16)
                | ((u32be[2] & 0xff) << 8) | (u32be[3] & 0xff);
    }
}
