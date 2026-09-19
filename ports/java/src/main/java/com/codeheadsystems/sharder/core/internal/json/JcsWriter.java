package com.codeheadsystems.sharder.core.internal.json;

import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonArray;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonBoolean;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonNumber;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonString;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The canonical form of a topology document: its RFC 8785 encoding, in UTF-8.
 *
 * <p>Object members are written in ascending order of their names compared as UTF-16 code units,
 * which is what {@link String#compareTo} compares, arrays keep their order, and no whitespace
 * separates anything. The topology format restricts a document's numbers to the exactly
 * representable integer range, so a number is written as its integer and no floating-point
 * formatting reaches the digest.
 *
 * <p>The validation rules refuse a duplicate member name and an unpaired surrogate before a
 * document reaches this class, so every document canonicalised here has exactly one UTF-8 encoding.
 */
public final class JcsWriter {

    private JcsWriter() {
    }

    /** The canonical form of a value, as UTF-8 octets. */
    public static byte[] canonicalise(JsonValue value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(JsonValue value, StringBuilder out) {
        switch (value) {
            case JsonObject object -> writeObject(object, out);
            case JsonArray array -> writeArray(array, out);
            case JsonString string -> writeString(string.value(), out);
            case JsonNumber number -> out.append(writeNumber(number));
            case JsonBoolean bool -> out.append(bool.value());
            case JsonValue.JsonNull ignored -> out.append("null");
        }
    }

    private static void writeObject(JsonObject object, StringBuilder out) {
        List<String> names = new ArrayList<>(object.members().keySet());
        names.sort(JcsWriter::compareNames);
        out.append('{');
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            writeString(names.get(index), out);
            out.append(':');
            write(object.members().get(names.get(index)), out);
        }
        out.append('}');
    }

    /**
     * RFC 8785 orders member names by their UTF-16 code units, which is the order Java's own string
     * comparison gives, so a name outside the basic multilingual plane sorts by its surrogate pair
     * exactly as the scheme requires.
     */
    private static int compareNames(String left, String right) {
        return left.compareTo(right);
    }

    private static void writeArray(JsonArray array, StringBuilder out) {
        out.append('[');
        for (int index = 0; index < array.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            write(array.get(index), out);
        }
        out.append(']');
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char next = value.charAt(index);
            switch (next) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (next < 0x20) {
                        out.append(String.format("\\u%04x", (int) next));
                    } else {
                        out.append(next);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String writeNumber(JsonNumber number) {
        String literal = number.literal();
        if (literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0) {
            return Long.toString(Long.parseLong(literal));
        }
        throw new IllegalArgumentException(
                "a topology document carries integers alone, not " + literal);
    }
}
