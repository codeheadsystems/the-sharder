package com.codeheadsystems.sharder.core.internal.placement;

import com.codeheadsystems.sharder.core.internal.document.TopologyDocument.KeyTransformSpec;
import java.util.Arrays;

/**
 * The key transforms of {@code KEY-010} through {@code KEY-044}.
 *
 * <p>A transform is a pure function of the key octets and the transform's own fields, under
 * {@code KEY-011}, and it works on octets: it decodes nothing as text, so a delimiter octet inside
 * a multi-byte sequence is a delimiter like any other, under {@code KEY-012}.
 */
public final class KeyTransforms {

    private KeyTransforms() {
    }

    /** The routing key {@code spec} derives from {@code key}. */
    public static byte[] apply(KeyTransformSpec spec, byte[] key) {
        return switch (spec.kind()) {
            case "none" -> key;
            case "braceTag" -> braceTag(key, spec.open(), spec.close());
            case "prefixFields" -> prefixFields(key, spec.separator(), spec.count());
            default -> throw new IllegalArgumentException(
                    "no key transform named " + spec.kind());
        };
    }

    /**
     * {@code KEY-030} to {@code KEY-037}: the octets between the first {@code open} and the first
     * {@code close} after it.
     *
     * <p>The whole key is the answer where there is no {@code open}, where no {@code close} follows
     * it, or where the two are adjacent. Nothing nests: the extracted octets may hold further
     * {@code open} octets and are returned as they stand.
     */
    private static byte[] braceTag(byte[] key, byte open, byte close) {
        int start = -1;
        for (int index = 0; index < key.length; index++) {
            if (key[index] == open) {
                start = index;
                break;
            }
        }
        if (start < 0) {
            return key;
        }
        int end = -1;
        for (int index = start + 1; index < key.length; index++) {
            if (key[index] == close) {
                end = index;
                break;
            }
        }
        if (end < 0 || end == start + 1) {
            return key;
        }
        return Arrays.copyOfRange(key, start + 1, end);
    }

    /**
     * {@code KEY-040} to {@code KEY-044}: the octets preceding the {@code count}-th occurrence of
     * {@code separator}.
     *
     * <p>The whole key is the answer where the key holds fewer occurrences. Where the occurrence
     * sits at index zero the answer is the empty sequence rather than the whole key, which
     * {@code KEY-043} states because the two are easy to confuse.
     */
    private static byte[] prefixFields(byte[] key, byte separator, int count) {
        int seen = 0;
        for (int index = 0; index < key.length; index++) {
            if (key[index] == separator) {
                seen++;
                if (seen == count) {
                    return Arrays.copyOfRange(key, 0, index);
                }
            }
        }
        return key;
    }
}
