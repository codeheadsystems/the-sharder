package com.codeheadsystems.sharder;

import java.util.Arrays;

/**
 * The octets a placement strategy hashes, which a key transform produces from a key under
 * {@code KEY-001}.
 *
 * <p>The type is a value rather than a {@code byte[]}: an array has identity equality, no useful
 * hash code, and no protection against a caller mutating it after the library has retained it.
 * Octets are copied on the way in and on the way out, and two values are ordered as unsigned octet
 * sequences under {@code CORE-003}.
 */
public final class RoutingKey implements Comparable<RoutingKey> {

    private final byte[] octets;
    private int hash;

    private RoutingKey(byte[] octets) {
        this.octets = octets;
    }

    /** The value holding the UTF-8 octets of {@code text}. */
    public static RoutingKey of(String text) {
        return new RoutingKey(Octets.encode(text));
    }

    /** The value holding a copy of {@code octets}. */
    public static RoutingKey ofBytes(byte[] octets) {
        return new RoutingKey(Octets.copyOf(octets));
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
    public int compareTo(RoutingKey other) {
        return Octets.compare(octets, other.octets);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RoutingKey value && Arrays.equals(octets, value.octets);
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
