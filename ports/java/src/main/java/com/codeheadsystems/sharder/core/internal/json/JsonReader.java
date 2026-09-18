package com.codeheadsystems.sharder.core.internal.json;

import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonArray;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonBoolean;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonNull;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonNumber;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonString;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * A strict JSON reader, written here rather than taken as a dependency.
 *
 * <p>The published artifact requires {@code java.base} and nothing else, so the library reads its
 * own JSON. Strict means what {@code TOPO-002} and {@code TOPO-004} ask of a topology document
 * reader: a duplicate member name is a failure rather than a last-one-wins, a trailing comma is a
 * failure, a control octet inside a string is a failure, and a number keeps its literal rather than
 * becoming a double.
 */
public final class JsonReader {

    private final String text;
    private int at;

    private JsonReader(String text) {
        this.text = text;
    }

    /** The value the octets of {@code utf8} spell. */
    public static JsonValue read(byte[] utf8) {
        return read(new String(utf8, StandardCharsets.UTF_8));
    }

    /** The value {@code text} spells. */
    public static JsonValue read(String text) {
        JsonReader reader = new JsonReader(text);
        reader.skipWhitespace();
        JsonValue value = reader.readValue();
        reader.skipWhitespace();
        if (reader.at != text.length()) {
            throw reader.failure("trailing octets after the value");
        }
        return value;
    }

    private JsonValue readValue() {
        if (at >= text.length()) {
            throw failure("a value was expected");
        }
        char next = text.charAt(at);
        return switch (next) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> new JsonString(readString());
            case 't' -> readLiteral("true", new JsonBoolean(true));
            case 'f' -> readLiteral("false", new JsonBoolean(false));
            case 'n' -> readLiteral("null", new JsonNull());
            default -> readNumber();
        };
    }

    private JsonValue readObject() {
        expect('{');
        Map<String, JsonValue> members = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            at++;
            return new JsonObject(members);
        }
        while (true) {
            skipWhitespace();
            String name = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            JsonValue value = readValue();
            if (members.put(name, value) != null) {
                throw failure("the member " + name + " appears twice");
            }
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                at++;
                continue;
            }
            if (next == '}') {
                at++;
                return new JsonObject(members);
            }
            throw failure("a comma or a closing brace was expected");
        }
    }

    private JsonValue readArray() {
        expect('[');
        List<JsonValue> elements = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            at++;
            return new JsonArray(elements);
        }
        while (true) {
            skipWhitespace();
            elements.add(readValue());
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                at++;
                continue;
            }
            if (next == ']') {
                at++;
                return new JsonArray(elements);
            }
            throw failure("a comma or a closing bracket was expected");
        }
    }

    private String readString() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw failure("the string does not end");
            }
            char next = text.charAt(at++);
            if (next == '"') {
                return value.toString();
            }
            if (next == '\\') {
                value.append(readEscape());
                continue;
            }
            if (next < 0x20) {
                throw failure("an unescaped control octet inside a string");
            }
            value.append(next);
        }
    }

    private char readEscape() {
        if (at >= text.length()) {
            throw failure("the escape does not end");
        }
        char next = text.charAt(at++);
        return switch (next) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            default -> throw failure("an unknown escape: \\" + next);
        };
    }

    private char readUnicodeEscape() {
        if (at + 4 > text.length()) {
            throw failure("a short unicode escape");
        }
        String digits = text.substring(at, at + 4);
        at += 4;
        try {
            return (char) Integer.parseInt(digits, 16);
        } catch (NumberFormatException cause) {
            throw failure("a unicode escape that is not four hexadecimal digits");
        }
    }

    private JsonValue readNumber() {
        int start = at;
        if (peek() == '-') {
            at++;
        }
        readDigits();
        if (at < text.length() && text.charAt(at) == '.') {
            at++;
            readDigits();
        }
        if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
            at++;
            if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
                at++;
            }
            readDigits();
        }
        String literal = text.substring(start, at);
        if (literal.isEmpty() || literal.equals("-")) {
            throw failure("a number was expected");
        }
        return new JsonNumber(literal);
    }

    private void readDigits() {
        int start = at;
        while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
            at++;
        }
        if (at == start) {
            throw failure("a digit was expected");
        }
    }

    private JsonValue readLiteral(String literal, JsonValue value) {
        if (!text.startsWith(literal, at)) {
            throw failure("the literal " + literal + " was expected");
        }
        at += literal.length();
        return value;
    }

    private char peek() {
        if (at >= text.length()) {
            throw failure("the value does not end");
        }
        return text.charAt(at);
    }

    private void expect(char expected) {
        if (at >= text.length() || text.charAt(at) != expected) {
            throw failure("the octet " + expected + " was expected");
        }
        at++;
    }

    private void skipWhitespace() {
        while (at < text.length()) {
            char next = text.charAt(at);
            if (next == ' ' || next == '\t' || next == '\n' || next == '\r') {
                at++;
                continue;
            }
            return;
        }
    }

    private IllegalArgumentException failure(String detail) {
        return new IllegalArgumentException(detail + ", at offset " + at);
    }
}
