package com.codeheadsystems.sharder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The octet handling the opaque identifier types share.
 *
 * <p>{@code NodeId}, {@code ShardId}, {@code RoutingKey}, and {@code Digest} are separate final
 * types rather than one type with a role, because the specification gives each its own meaning and
 * a caller that passes one where another belongs is making a mistake the compiler can catch. What
 * they share is copying on the way in and out, unsigned comparison under {@code CORE-003}, and a
 * cached hash code.
 */
final class Octets {

    private Octets() {
    }

    static byte[] copyOf(byte[] octets) {
        return Arrays.copyOf(octets, octets.length);
    }

    static byte[] encode(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static String decode(byte[] octets) {
        return new String(octets, StandardCharsets.UTF_8);
    }

    /** Unsigned octet comparison, which is {@code PLACE-020} including its proper prefix rule. */
    static int compare(byte[] left, byte[] right) {
        return Arrays.compareUnsigned(left, right);
    }
}
