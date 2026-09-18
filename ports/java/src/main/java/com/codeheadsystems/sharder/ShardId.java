package com.codeheadsystems.sharder;

import java.util.Arrays;

/**
 * A shard identifier as {@code PLACE-031} renders it.
 *
 * <p>The type is a value rather than a {@code byte[]}: an array has identity equality, no useful
 * hash code, and no protection against a caller mutating it after the library has retained it.
 * Octets are copied on the way in and on the way out, and two values are ordered as unsigned octet
 * sequences under {@code CORE-003}.
 */
public final class ShardId implements Comparable<ShardId> {

    private final byte[] octets;
    private int hash;

    private ShardId(byte[] octets) {
        this.octets = octets;
    }

    /** The value holding the UTF-8 octets of {@code text}. */
    public static ShardId of(String text) {
        return new ShardId(Octets.encode(text));
    }

    /** The value holding a copy of {@code octets}. */
    public static ShardId ofBytes(byte[] octets) {
        return new ShardId(Octets.copyOf(octets));
    }

    /** The number of octets the value holds. */
    public int length() {
        return octets.length;
    }

    /** A copy of the octets the value holds. */
    public byte[] toBytes() {
        return Octets.copyOf(octets);
    }

    /** The octets decoded as UTF-8. */
    public String asText() {
        return Octets.decode(octets);
    }

    @Override
    public int compareTo(ShardId other) {
        return Octets.compare(octets, other.octets);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ShardId value && Arrays.equals(octets, value.octets);
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
        return asText();
    }
}
