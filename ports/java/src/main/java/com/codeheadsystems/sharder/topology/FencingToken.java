package com.codeheadsystems.sharder.topology;

import com.codeheadsystems.sharder.Digest;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * The token a caller passes to a recipient, under {@code FENCE-010}.
 *
 * <p>The token is the topology identifier and the epoch. {@code FENCE-011} permits a third,
 * diagnostic component carrying the topology digest, and forbids a recipient from reading it in any
 * ordering or acceptance decision, so it is absent from {@link #equals} and from the encoding.
 */
public record FencingToken(String topologyId, long epoch, Optional<Digest> digest) {

    /** A token over {@code topologyId} and {@code epoch}, carrying no diagnostic digest. */
    public FencingToken {
        Objects.requireNonNull(topologyId, "topologyId");
        Objects.requireNonNull(digest, "digest");
        if (epoch < 0) {
            throw new IllegalArgumentException("an epoch is not negative: " + epoch);
        }
    }

    /** A token carrying no diagnostic digest. */
    public static FencingToken of(String topologyId, long epoch) {
        return new FencingToken(topologyId, epoch, Optional.empty());
    }

    /**
     * The canonical octet encoding of {@code FENCE-021}.
     *
     * <p>{@code u32be(len(topologyId)) || topologyId || u64be(epoch)}, where the identifier is its
     * UTF-8 octets. A delimited textual form is forbidden there, because an identifier carrying the
     * delimiter would make two distinct tokens encode alike.
     */
    public byte[] toByteArray() {
        byte[] identifier = topologyId.getBytes(StandardCharsets.UTF_8);
        byte[] encoded = new byte[4 + identifier.length + 8];
        writeU32be(encoded, 0, identifier.length);
        System.arraycopy(identifier, 0, encoded, 4, identifier.length);
        int offset = 4 + identifier.length;
        for (int index = 0; index < 8; index++) {
            encoded[offset + index] = (byte) (epoch >>> (56 - 8 * index));
        }
        return encoded;
    }

    private static void writeU32be(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    /** Two tokens are equal on the identifier and the epoch, and never on the digest. */
    @Override
    public boolean equals(Object other) {
        return other instanceof FencingToken token
                && topologyId.equals(token.topologyId) && epoch == token.epoch;
    }

    @Override
    public int hashCode() {
        return topologyId.hashCode() * 31 + Long.hashCode(epoch);
    }

    @Override
    public String toString() {
        return topologyId + "@" + epoch;
    }
}
