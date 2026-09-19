package com.codeheadsystems.sharder.topology;

import java.util.Arrays;
import java.util.Objects;

/**
 * The opaque version a provider attaches to a document, under {@code CORE-084}.
 *
 * <p>The octets are the provider's and the library never reads them: a file provider builds one
 * from the modification time and the size, an HTTP adapter from the entity tag it received, and an
 * etcd adapter from the revision. A provider with nothing to put in one answers
 * {@code Optional.empty()} and is passed {@code Optional.empty()} on every load, under
 * {@code CORE-087}.
 */
public final class SourceVersion {

    /** The greatest octet count a version carries, under {@code CORE-084}. */
    public static final int MAX_LENGTH = 4096;

    private final byte[] octets;

    private SourceVersion(byte[] octets) {
        this.octets = octets;
    }

    /** The version over {@code octets}, which are copied on the way in. */
    public static SourceVersion of(byte[] octets) {
        Objects.requireNonNull(octets, "octets");
        if (octets.length > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "a source version carries at most " + MAX_LENGTH + " octets, not "
                            + octets.length);
        }
        return new SourceVersion(octets.clone());
    }

    /** The octets, copied on the way out. */
    public byte[] toByteArray() {
        return octets.clone();
    }

    /** The octet count. */
    public int length() {
        return octets.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SourceVersion version && Arrays.equals(octets, version.octets);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(octets);
    }

    @Override
    public String toString() {
        return "SourceVersion(" + octets.length + " octets)";
    }
}
