package com.codeheadsystems.sharder.core.internal.hash;

/**
 * The framed hash input of {@code HASH-021}.
 *
 * <p>A frame is the concatenation, in order, of each field's length as {@code u32be} followed by
 * the field's octets. A field of zero octets is framed as its length and no further octets, under
 * {@code HASH-024}. Nothing separates two fields, and no field is hashed outside a frame, under
 * {@code HASH-022}: the framing is what stops two different field lists producing one message.
 */
public final class Frame {

    /** The octet count of a length prefix, which {@code HASH-020} fixes at four. */
    public static final int PREFIX = 4;

    /** The greatest octet count a field may carry, under {@code HASH-024}. */
    public static final long MAX_FIELD = 4294967295L;

    private Frame() {
    }

    /** The framed message over {@code fields}, in the order given. */
    public static byte[] of(byte[]... fields) {
        int size = 0;
        for (byte[] field : fields) {
            if (field.length > MAX_FIELD) {
                throw new IllegalArgumentException("a framed field carries at most " + MAX_FIELD
                        + " octets, not " + field.length);
            }
            size += PREFIX + field.length;
        }
        byte[] framed = new byte[size];
        int offset = 0;
        for (byte[] field : fields) {
            writeU32be(framed, offset, field.length);
            offset += PREFIX;
            System.arraycopy(field, 0, framed, offset, field.length);
            offset += field.length;
        }
        return framed;
    }

    /** The four octets of {@code value}, most significant first, written at {@code offset}. */
    public static void writeU32be(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    /** The four octets of {@code value}, most significant first, as a field's own octets. */
    public static byte[] u32be(int value) {
        byte[] octets = new byte[PREFIX];
        writeU32be(octets, 0, value);
        return octets;
    }
}
