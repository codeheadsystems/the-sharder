package com.codeheadsystems.sharder.conformance;

import com.codeheadsystems.sharder.core.internal.json.JsonReader;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The conformance suite tree a driver reads.
 *
 * <p>The tree is the repository's own {@code conformance/} directory. {@code
 * sharder.conformance.dir} names another, so a maintainer runs the suite against an edited tree
 * without touching the build.
 */
final class VectorSource {

    /** The system property that names the suite root. */
    static final String ROOT_PROPERTY = "sharder.conformance.dir";

    private final Path root;

    private VectorSource(Path root) {
        this.root = root;
    }

    /** The suite the {@code sharder.conformance.dir} property names. */
    static VectorSource fromProperty() {
        String configured = System.getProperty(ROOT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(ROOT_PROPERTY + " names no directory");
        }
        Path root = Path.of(configured);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(ROOT_PROPERTY + " names " + root
                    + ", which is not a directory");
        }
        if (!Files.isRegularFile(root.resolve("manifest.json"))) {
            throw new IllegalStateException(root + " carries no manifest.json");
        }
        return new VectorSource(root);
    }

    /** The octets of one file of the suite, by the path the manifest names it by. */
    byte[] readBytes(String path) {
        try {
            return Files.readAllBytes(root.resolve(path));
        } catch (IOException cause) {
            throw new UncheckedIOException("the suite carries no " + path, cause);
        }
    }

    /** One file of the suite, read as a JSON object. */
    JsonObject readObject(String path) {
        return JsonReader.read(readBytes(path)).asObject();
    }

    /** The SHA-256 of one file of the suite, as lowercase hexadecimal. */
    String digestOf(String path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(readBytes(path)));
        } catch (NoSuchAlgorithmException cause) {
            throw new IllegalStateException("SHA-256 is absent from this runtime", cause);
        }
    }

    /** The suite root. */
    Path root() {
        return root;
    }
}
