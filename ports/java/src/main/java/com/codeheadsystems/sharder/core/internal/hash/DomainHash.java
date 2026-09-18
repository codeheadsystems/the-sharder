package com.codeheadsystems.sharder.core.internal.hash;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * The three domain-tagged hash functions of {@code HASH-030}, over one snapshot's key.
 *
 * <p>The key is the sixteen octets {@code hash.seed} decodes to, used directly and never derived
 * further, under {@code HASH-010}. It is constant for the lifetime of a snapshot and does not vary
 * by strategy, by domain tag, by node, by shard, or by call, under {@code HASH-012}. A document
 * carrying no seed takes sixteen zero octets, under {@code HASH-011}.
 *
 * <p>The tag is the first field of every frame, under {@code HASH-023}, and no tag is reused for a
 * second purpose. The value each function answers is the output of {@code H} over its frame, with
 * no folding, masking, rotating, or truncating, under {@code HASH-032}.
 */
public final class DomainHash {

    /** The octet count of the key, under {@code HASH-010}. */
    public static final int KEY_LENGTH = 16;

    private static final byte[] KEY_TAG = tag("sharder/key/v1");
    private static final byte[] RING_TOKEN_TAG = tag("sharder/ring-token/v1");
    private static final byte[] RENDEZVOUS_TAG = tag("sharder/rendezvous/v1");

    private final long k0;
    private final long k1;

    private DomainHash(byte[] key) {
        this.k0 = SipHash24.keyLow(key);
        this.k1 = SipHash24.keyHigh(key);
    }

    /** The functions under the sixteen octets of {@code key}. */
    public static DomainHash ofKey(byte[] key) {
        if (key.length != KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "the hash key is " + KEY_LENGTH + " octets, not " + key.length);
        }
        return new DomainHash(key);
    }

    /** The functions under the key the thirty-two hexadecimal digits of {@code seed} spell. */
    public static DomainHash ofSeed(CharSequence seed) {
        return ofKey(HexFormat.of().parseHex(seed));
    }

    /** The functions under the sixteen zero octets {@code HASH-011} gives a document with no
     * seed. */
    public static DomainHash withoutSeed() {
        return ofKey(new byte[KEY_LENGTH]);
    }

    /** {@code keyHash(rk)}: the hash of a routing key. */
    public long keyHash(byte[] routingKey) {
        return hash(Frame.of(KEY_TAG, routingKey));
    }

    /** {@code ringToken(id, i)}: the position of a node's token {@code i} on the ring. */
    public long ringToken(byte[] nodeId, int index) {
        return hash(Frame.of(RING_TOKEN_TAG, nodeId, Frame.u32be(index)));
    }

    /** {@code rvScore(rk, id, i)}: a node's virtual node score for a routing key. */
    public long rvScore(byte[] routingKey, byte[] nodeId, int index) {
        return hash(Frame.of(RENDEZVOUS_TAG, routingKey, nodeId, Frame.u32be(index)));
    }

    /** The output of {@code H} over an already framed message. */
    public long hash(byte[] framed) {
        return SipHash24.hash(k0, k1, framed, 0, framed.length);
    }

    private static byte[] tag(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
