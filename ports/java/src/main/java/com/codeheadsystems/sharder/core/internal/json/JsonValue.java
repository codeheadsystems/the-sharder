package com.codeheadsystems.sharder.core.internal.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One JSON value, as {@link JsonReader} parses it.
 *
 * <p>A number keeps the octets the document spells rather than a parsed quantity, because
 * {@code TOPO-004} restricts a document's numbers to the exactly representable integer range and
 * {@code HASH-043} forbids a floating-point value reaching placement. A caller asks for the width
 * it needs and the ask fails where the literal does not carry it.
 */
public sealed interface JsonValue {

    /** An object, whose members keep the order the document spells them in. */
    record JsonObject(Map<String, JsonValue> members) implements JsonValue {

        /** An empty object. */
        public JsonObject() {
            this(new LinkedHashMap<>());
        }

        /** The member {@code name} holds, where the object carries one. */
        public Optional<JsonValue> find(String name) {
            return Optional.ofNullable(members.get(name));
        }

        /** The member {@code name} holds. */
        public JsonValue get(String name) {
            JsonValue value = members.get(name);
            if (value == null) {
                throw new IllegalArgumentException("no member named " + name);
            }
            return value;
        }

        /** The text of the member {@code name} holds. */
        public String text(String name) {
            return get(name).asText();
        }

        /** The object the member {@code name} holds. */
        public JsonObject object(String name) {
            return get(name).asObject();
        }

        /** The array the member {@code name} holds. */
        public JsonArray array(String name) {
            return get(name).asArray();
        }
    }

    /** An array, in document order. */
    record JsonArray(List<JsonValue> elements) implements JsonValue {

        /** An empty array. */
        public JsonArray() {
            this(new ArrayList<>());
        }

        /** The element at {@code index}. */
        public JsonValue get(int index) {
            return elements.get(index);
        }

        /** The element count. */
        public int size() {
            return elements.size();
        }

        /** The text of every element. */
        public List<String> texts() {
            return elements.stream().map(JsonValue::asText).toList();
        }
    }

    /** A string, with every escape already resolved. */
    record JsonString(String value) implements JsonValue {
    }

    /** A number, as the literal octets the document spells. */
    record JsonNumber(String literal) implements JsonValue {

        /** The value as a signed 64-bit integer, where the literal spells one. */
        public long asLong() {
            return Long.parseLong(literal);
        }

        /** The value as a signed 32-bit integer, where the literal spells one. */
        public int asInt() {
            return Integer.parseInt(literal);
        }

        /** The value as an unsigned 64-bit integer, whose bit pattern a {@code long} holds. */
        public long asUnsignedLong() {
            return Long.parseUnsignedLong(literal);
        }
    }

    /** A boolean. */
    record JsonBoolean(boolean value) implements JsonValue {
    }

    /** The null literal. */
    record JsonNull() implements JsonValue {
    }

    /** The value as an object, or a failure where it is another kind. */
    default JsonObject asObject() {
        if (this instanceof JsonObject object) {
            return object;
        }
        throw new IllegalStateException("not an object: " + this);
    }

    /** The value as an array, or a failure where it is another kind. */
    default JsonArray asArray() {
        if (this instanceof JsonArray array) {
            return array;
        }
        throw new IllegalStateException("not an array: " + this);
    }

    /** The value as text, or a failure where it is another kind. */
    default String asText() {
        if (this instanceof JsonString string) {
            return string.value();
        }
        throw new IllegalStateException("not a string: " + this);
    }

    /** The value as a signed 64-bit integer, or a failure where it is another kind. */
    default long asLong() {
        if (this instanceof JsonNumber number) {
            return number.asLong();
        }
        throw new IllegalStateException("not a number: " + this);
    }

    /** The value as a signed 32-bit integer, or a failure where it is another kind. */
    default int asInt() {
        if (this instanceof JsonNumber number) {
            return number.asInt();
        }
        throw new IllegalStateException("not a number: " + this);
    }

    /** The value as a boolean, or a failure where it is another kind. */
    default boolean asBoolean() {
        if (this instanceof JsonBoolean value) {
            return value.value();
        }
        throw new IllegalStateException("not a boolean: " + this);
    }

    /** The value as an unsigned 64-bit integer, or a failure where it is another kind. */
    default long asUnsignedLong() {
        if (this instanceof JsonNumber number) {
            return number.asUnsignedLong();
        }
        throw new IllegalStateException("not a number: " + this);
    }

    /** Whether the value is the null literal. */
    default boolean isNull() {
        return this instanceof JsonNull;
    }
}
