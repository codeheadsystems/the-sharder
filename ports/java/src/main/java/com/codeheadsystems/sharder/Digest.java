package com.codeheadsystems.sharder;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * A SHA-256 digest of the canonical form of a topology document, under {@code TOPO-030}.
 *
 * <p>A digest is exactly thirty-two octets. A value of any other length is a caller's mistake
 * rather than a routing condition, so the constructor refuses it outright.
 */
public final class Digest implements Comparable<Digest> {

    /** The octet count of SHA-256 output. */
    public static final int LENGTH = 32;

    private final byte[] octets;
    private int hash;

    private Digest(byte[] octets) {
        this.octets = octets;
    }

    /** The digest holding a copy of {@code octets}, which is thirty-two octets long. */
    public static Digest ofBytes(byte[] octets) {
        if (octets.length != LENGTH) {
            throw new IllegalArgumentException(
                    "a digest is " + LENGTH + " octets, not " + octets.length);
        }
        return new Digest(Octets.copyOf(octets));
    }

    /** The digest the sixty-four hexadecimal digits of {@code hex} spell. */
    public static Digest ofHex(CharSequence hex) {
        return ofBytes(HexFormat.of().parseHex(hex));
    }

    /** A copy of the octets the digest holds. */
    public byte[] toBytes() {
        return Octets.copyOf(octets);
    }

    /** The digest as sixty-four lowercase hexadecimal digits. */
    public String toHex() {
        return HexFormat.of().formatHex(octets);
    }

    @Override
    public int compareTo(Digest other) {
        return Octets.compare(octets, other.octets);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Digest value && Arrays.equals(octets, value.octets);
    }

    @Override
    public int hashCode() {
        int cached = hash;
        if (cached == 0) {
            cached = Arrays.hashCode(octets);
            hash = cached;
        }
        return cached;
    }

    @Override
    public String toString() {
        return toHex();
    }
}
