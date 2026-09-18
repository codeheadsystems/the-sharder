package com.codeheadsystems.sharder.core.internal.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonNumber;
import com.codeheadsystems.sharder.core.internal.json.JsonValue.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The reader is strict where a topology document reader has to be strict. */
class JsonReaderTest {

    @Test
    @DisplayName("an object keeps the order its members are spelled in")
    void objectKeepsOrder() {
        JsonObject object = JsonReader.read("{\"b\":1,\"a\":2,\"c\":3}").asObject();
        assertThat(object.members().keySet()).containsExactly("b", "a", "c");
    }

    @Test
    @DisplayName("TOPO-002: a duplicate member name is a failure rather than a last one wins")
    void duplicateMemberRefused() {
        assertThatThrownBy(() -> JsonReader.read("{\"a\":1,\"a\":2}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appears twice");
    }

    @Test
    @DisplayName("a trailing comma, a trailing value, and an unterminated string are failures")
    void malformedRefused() {
        assertThatThrownBy(() -> JsonReader.read("[1,2,]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonReader.read("{\"a\":1} {}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonReader.read("\"unterminated"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unescaped control octet inside a string is a failure")
    void controlOctetRefused() {
        String withControl = "{\"a\":\"" + (char) 1 + "\"}";
        assertThatThrownBy(() -> JsonReader.read(withControl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control octet");
    }

    @Test
    @DisplayName("HASH-043: a number keeps its literal rather than becoming a floating point value")
    void numberKeepsItsLiteral() {
        JsonObject object = JsonReader.read("{\"epoch\":9007199254740991}").asObject();
        assertThat(object.get("epoch").asLong()).isEqualTo(9007199254740991L);
        assertThat(JsonReader.read("{\"n\":1.5}").asObject().get("n"))
                .isEqualTo(new JsonNumber("1.5"));
    }

    @Test
    @DisplayName("escapes resolve, including a unicode escape")
    void escapesResolve() {
        assertThat(JsonReader.read("\"a\\u0062\\n\\\"\"").asText()).isEqualTo("ab\n\"");
    }
}
