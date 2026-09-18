package com.codeheadsystems.sharder.conformance;

import com.codeheadsystems.sharder.core.internal.json.JsonValue;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code manifest.json}, which is the one entry point a driver reads.
 *
 * <p>Every suite, level, vector file, and vector kind is discovered from it, so a vector added to
 * the tree and listed in the manifest runs without a change here.
 */
record VectorManifest(String revision, List<Level> levels, List<String> strategySurfaces,
                      List<FileEntry> files) {

    /** One conformance level, with the levels it requires and the artefacts it carries. */
    record Level(String name, List<String> requires, int vectorFiles, int vectorCases) {
    }

    /** One vector file, as the manifest indexes it. */
    record FileEntry(String file, String vectorSet, String kind, String level, int caseCount,
                     List<String> requirements, String sha256) {
    }

    /** The manifest the suite carries. */
    static VectorManifest read(VectorSource source) {
        JsonObject root = source.readObject("manifest.json");
        String revision = root.object("revision").text("id");
        List<Level> levels = root.array("levels").elements().stream()
                .map(JsonValue::asObject)
                .map(entry -> new Level(
                        entry.text("level"),
                        entry.array("requires").texts(),
                        entry.get("vectorFiles").asInt(),
                        entry.get("vectorCases").asInt()))
                .toList();
        List<String> surfaces = root.array("strategySurfaces").texts();
        List<FileEntry> files = root.array("vectorFiles").elements().stream()
                .map(JsonValue::asObject)
                .map(entry -> new FileEntry(
                        entry.text("file"),
                        entry.text("vectorSet"),
                        entry.text("kind"),
                        entry.text("level"),
                        entry.get("caseCount").asInt(),
                        entry.array("requirements").texts(),
                        entry.text("sha256")))
                .toList();
        return new VectorManifest(revision, levels, surfaces, files);
    }

    /** The level of that name. */
    Level level(String name) {
        return levels.stream()
                .filter(level -> level.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "the manifest carries no level " + name));
    }

    /** One level with every level it requires, transitively, which a declaration carries whole. */
    Set<String> withRequired(String name) {
        Set<String> reached = new LinkedHashSet<>();
        collect(name, reached);
        return reached;
    }

    private void collect(String name, Set<String> reached) {
        if (!reached.add(name)) {
            return;
        }
        for (String required : level(name).requires()) {
            collect(required, reached);
        }
    }

    /** Every file of the suite at one level, in manifest order. */
    List<FileEntry> filesAt(String level) {
        return files.stream().filter(entry -> entry.level().equals(level)).toList();
    }
}
