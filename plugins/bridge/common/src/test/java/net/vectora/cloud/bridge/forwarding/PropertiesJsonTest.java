package net.vectora.cloud.bridge.forwarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class PropertiesJsonTest {

    @Test
    void readsWhatVelocityWrites() throws MalformedHandshakeException {
        assertEquals(List.of(), PropertiesJson.parse("[]"));
        assertEquals(List.of(), PropertiesJson.parse(" [ ] "));
        assertEquals(List.of(new GameProperty("textures", "abc", "sig"), new GameProperty("bungeeguard-token", "x", null)),
                PropertiesJson.parse("[{\"name\":\"textures\",\"value\":\"abc\",\"signature\":\"sig\"},"
                        + " {\"value\":\"x\", \"name\":\"bungeeguard-token\", \"signature\":null}]"));
    }

    @Test
    void decodesEscapesAndWritesThemBack() throws MalformedHandshakeException {
        List<GameProperty> parsed = PropertiesJson.parse(
                "[{\"name\":\"a\\\"b\\\\c\\/d\",\"value\":\"\\u00e4\\n\\t\\b\\f\\r\\ud83d\\ude00\"}]");
        assertEquals(List.of(new GameProperty("a\"b\\c/d", "\u00e4\n\t\b\f\r\ud83d\ude00", null)), parsed);
        assertEquals(parsed, PropertiesJson.parse(PropertiesJson.write(parsed)));
        // A lone surrogate survives the round trip as an escape.
        List<GameProperty> lone = List.of(new GameProperty("n", "\ud800", null));
        assertEquals(lone, PropertiesJson.parse(PropertiesJson.write(lone)));
    }

    @Test
    void rejectsEverythingElse() {
        for (String json : List.of("", "{}", "[", "[{}]", "[{\"name\":\"a\"}]", "[{\"value\":\"a\"}]",
                "[{\"name\":\"a\",\"value\":\"b\",\"extra\":\"c\"}]",
                "[{\"name\":\"a\",\"name\":\"b\",\"value\":\"c\"}]",
                "[{\"name\":\"a\",\"value\":\"b\",\"signature\":\"c\",\"signature\":\"d\"}]",
                "[{\"name\":null,\"value\":\"b\"}]", "[{\"name\":\"a\",\"value\":1}]",
                "[{\"name\":\"a\",\"value\":\"b\"}] x", "[{\"name\":\"a\",\"value\":\"b\"},]",
                "[{\"name\":\"a\u0001\",\"value\":\"b\"}]", "[{\"name\":\"\\x\",\"value\":\"b\"}]",
                "[{\"name\":\"\\u12g4\",\"value\":\"b\"}]", "[{\"name\":\"\\u12\"}]", "[{\"name\":\"a",
                "[{\"name\":\"a\",\"value\":\"b\"}{\"name\":\"a\",\"value\":\"b\"}]", "[nul]",
                "[{\"name\":\"a\",\"value\":\"b\"} ; ]")) {
            assertThrows(MalformedHandshakeException.class, () -> PropertiesJson.parse(json), json);
        }
    }

    @Test
    void limitsTheNumberOfProperties() throws MalformedHandshakeException {
        StringBuilder ok = new StringBuilder("[");
        for (int i = 0; i < PropertiesJson.MAX_PROPERTIES; i++) {
            ok.append(i == 0 ? "" : ",").append("{\"name\":\"p\",\"value\":\"v\"}");
        }
        assertEquals(PropertiesJson.MAX_PROPERTIES, PropertiesJson.parse(ok + "]").size());
        assertThrows(MalformedHandshakeException.class,
                () -> PropertiesJson.parse(ok + ",{\"name\":\"p\",\"value\":\"v\"}]"));
    }

    @Test
    void writesControlCharactersAsEscapesAndHidesValuesInToString() {
        String json = PropertiesJson.write(List.of(new GameProperty("n", "a\u0000b\"", "s")));
        assertEquals("[{\"name\":\"n\",\"value\":\"a\\u0000b\\\"\",\"signature\":\"s\"}]", json);
        assertFalse(new GameProperty("bungeeguard-token", "topsecretvalue", null).toString().contains("topsecretvalue"));
    }
}
