package com.codeheadsystems.sharder.core.internal.hash;

import java.util.HexFormat;

/**
 * The unsigned 64-bit arithmetic of {@code HASH-040}.
 *
 * <p>Java has no unsigned long. Every hash value, ring token, and rendezvous score is a
 * {@code long} whose bit pattern is the value, and this class is the only place the sanctioned
 * forms are written. A signed comparison of such a value produces an ordering that is wrong for
 * every operand at or above 2^63, which is half of them, and the result is a placement that is
 * internally consistent and disagrees with every other port.
 */
public final class U64 {

    private U64() {
    }

    /** Unsigned comparison over the whole 64-bit range, under {@code HASH-040}. */
    public static int compare(long left, long right) {
        return Long.compareUnsigned(left, right);
    }

    /** The greater of two unsigned values, as {@code RV-003} needs. */
    public static long max(long left, long right) {
        return Long.compareUnsigned(left, right) >= 0 ? left : right;
    }

    /** The unsigned remainder of {@code HASH-042}, the only division placement performs. */
    public static long mod(long value, long divisor) {
        return Long.remainderUnsigned(value, divisor);
    }

    /** The value as sixteen lowercase hexadecimal digits, under {@code HASH-044}. */
    public static String toHex(long value) {
        return HexFormat.of().toHexDigits(value);
    }

    /** The value sixteen hexadecimal digits spell. */
    public static long parseHex(CharSequence text) {
        return Long.parseUnsignedLong(text, 0, text.length(), 16);
    }

    /**
     * The comparison of {@code a * b} against {@code c * d} over the exact products, under
     * {@code CORE-005}.
     *
     * <p>The operands are unsigned and each product may exceed 64 bits, so the comparison is made
     * over the 128-bit products, high half first.
     */
    public static int compareProducts(long a, long b, long c, long d) {
        long leftHigh = Math.unsignedMultiplyHigh(a, b);
        long rightHigh = Math.unsignedMultiplyHigh(c, d);
        int high = Long.compareUnsigned(leftHigh, rightHigh);
        return high != 0 ? high : Long.compareUnsigned(a * b, c * d);
    }

    /**
     * The comparison of {@code a * b} against {@code c * d + e} over the exact values, under
     * {@code CORE-005}, which the right side of {@code FAIL-031} needs.
     */
    public static int compareProductToSum(long a, long b, long c, long d, long e) {
        long leftHigh = Math.unsignedMultiplyHigh(a, b);
        long leftLow = a * b;
        long rightHigh = Math.unsignedMultiplyHigh(c, d);
        long rightLow = c * d;
        long summed = rightLow + e;
        if (Long.compareUnsigned(summed, rightLow) < 0) {
            rightHigh += 1;
        }
        int high = Long.compareUnsigned(leftHigh, rightHigh);
        return high != 0 ? high : Long.compareUnsigned(leftLow, summed);
    }
}
