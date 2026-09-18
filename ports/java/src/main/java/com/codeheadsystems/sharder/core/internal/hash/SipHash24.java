package com.codeheadsystems.sharder.core.internal.hash;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * SipHash-2-4 as Aumasson and Bernstein publish it, under {@code HASH-001}.
 *
 * <p>A keyed pseudorandom function over a 128-bit key and a message of octets, with two compression
 * rounds per message word, four finalisation rounds, and 64 bits of output. The little-endian
 * loading of message words and the little-endian assembly of the output are part of the algorithm,
 * and neither is the big-endian framing of {@code HASH-020}.
 *
 * <p>The function is verified against the sixty-four reference vectors published with the algorithm
 * before any conformance vector runs, which {@code HASH-003} requires.
 *
 * <p>The rounds are written out rather than factored into a method, because the round state is four
 * values and a Java method answers one. The alternative allocates an array per round on the
 * routing path, and the evaluation count of a rendezvous placement is the summed virtual node
 * count of the topology.
 */
public final class SipHash24 {

    private static final VarHandle WORD =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static final long INIT_0 = 0x736f6d6570736575L;
    private static final long INIT_1 = 0x646f72616e646f6dL;
    private static final long INIT_2 = 0x6c7967656e657261L;
    private static final long INIT_3 = 0x7465646279746573L;

    private SipHash24() {
    }

    /**
     * The 64-bit output over a message, under the key {@code k0} and {@code k1} hold.
     *
     * @param k0 the first eight key octets, loaded little-endian
     * @param k1 the second eight key octets, loaded little-endian
     * @param message the array holding the message octets
     * @param offset the first octet of the message within the array
     * @param length the octet count of the message
     * @return the output as an unsigned 64-bit value in a {@code long}
     */
    public static long hash(long k0, long k1, byte[] message, int offset, int length) {
        long v0 = INIT_0 ^ k0;
        long v1 = INIT_1 ^ k1;
        long v2 = INIT_2 ^ k0;
        long v3 = INIT_3 ^ k1;

        int blocks = length & ~7;
        for (int index = 0; index < blocks; index += 8) {
            long word = (long) WORD.get(message, offset + index);
            v3 ^= word;

            v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
            v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
            v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
            v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);

            v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
            v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
            v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
            v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);

            v0 ^= word;
        }

        long last = ((long) length & 0xffL) << 56;
        for (int index = length - 1; index >= blocks; index--) {
            last |= (message[offset + index] & 0xffL) << (8 * (index - blocks));
        }

        v3 ^= last;

        v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
        v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
        v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
        v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);

        v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
        v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
        v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
        v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);

        v0 ^= last;
        v2 ^= 0xffL;

        for (int round = 0; round < 4; round++) {
            v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
            v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
            v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
            v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);
        }

        return v0 ^ v1 ^ v2 ^ v3;
    }

    /** The first eight octets of a sixteen-octet key, loaded little-endian. */
    public static long keyLow(byte[] key) {
        return (long) WORD.get(key, 0);
    }

    /** The second eight octets of a sixteen-octet key, loaded little-endian. */
    public static long keyHigh(byte[] key) {
        return (long) WORD.get(key, 8);
    }
}
