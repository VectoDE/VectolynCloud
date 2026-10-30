package net.vectolyn.cloud.template;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaceholderRendererTest {

    private static final String PASSWORD = "hunter2-hunter2-hunter2";
    private static final PlaceholderValues VALUES = PlaceholderValues.of(Map.of(
            "VECTOLYN_SERVER_PORT", "25566",
            "VECTOLYN_DB_PASSWORD", PASSWORD,
            "VECTOLYN_PROXY_SERVERS", "lobby = \"127.0.0.1:25566\"\nend = \"127.0.0.1:25570\""));

    private static String render(String text) throws TemplateException {
        return PlaceholderRenderer.render(text, "server.properties", VALUES);
    }

    @Test
    void replacesEveryPlaceholder() throws TemplateException {
        assertEquals("server-port=25566\npw=" + PASSWORD + " 25566\n",
                render("server-port={{VECTOLYN_SERVER_PORT}}\npw={{VECTOLYN_DB_PASSWORD}} {{VECTOLYN_SERVER_PORT}}\n"));
    }

    @Test
    void textWithoutPlaceholdersStaysTheSame() throws TemplateException {
        String text = "a: { b: 1 }\nmotd: \"<gray>{x}</gray> }}\"\n";
        assertSame(text, render(text));
    }

    @Test
    void unknownPlaceholderFailsWithFileAndLine() {
        TemplateException e = assertThrows(TemplateException.class,
                () -> render("a=1\nb=2\nc={{VECTOLYN_NOT_THERE}}\n"));
        assertEquals("server.properties", e.file());
        assertEquals(3, e.line());
        assertTrue(e.getMessage().startsWith("server.properties:3: "), e.getMessage());
        assertTrue(e.getMessage().contains("VECTOLYN_NOT_THERE"), e.getMessage());
    }

    @Test
    void placeholderOutsideTheVectolynNamespaceIsAnError() {
        TemplateException e = assertThrows(TemplateException.class, () -> render("x\r\ny={{name}}"));
        assertEquals(2, e.line());
    }

    @Test
    void unclosedPlaceholderIsAnError() {
        TemplateException e = assertThrows(TemplateException.class, () -> render("\r\r{{VECTOLYN_SERVER_PORT"));
        assertEquals(3, e.line());
    }

    @Test
    void placeholderWithSpacesIsAnError() {
        assertThrows(TemplateException.class, () -> render("{{ VECTOLYN_SERVER_PORT }}"));
    }

    @Test
    void emptyPlaceholderIsAnError() {
        assertThrows(TemplateException.class, () -> render("{{}}"));
        assertThrows(TemplateException.class, () -> render("{{VECTOLYN_}}"));
    }

    @Test
    void fourBracesAreALiteralDoubleBrace() throws TemplateException {
        assertEquals("a {{ b", render("a {{{{ b"));
        assertEquals("{{VECTOLYN_SERVER_PORT}}", render("{{{{VECTOLYN_SERVER_PORT}}"));
        assertEquals("{{25566", render("{{{{{{VECTOLYN_SERVER_PORT}}"));
    }

    @Test
    void errorMessagesNeverContainValues() {
        TemplateException e = assertThrows(TemplateException.class,
                () -> render("pw={{VECTOLYN_DB_PASSWORD}}\n{{VECTOLYN_MISSING}}"));
        assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
    }

    private static void rejected(String name, String value) {
        PlaceholderValues values = PlaceholderValues.of(Map.of(name, value));
        TemplateException e = assertThrows(TemplateException.class,
                () -> PlaceholderRenderer.render("a=1\nb={{" + name + "}}\n", "x.yml", values), name);
        assertEquals("x.yml", e.file());
        assertEquals(2, e.line());
        assertTrue(e.getMessage().contains(name), e.getMessage());
        if (!value.isEmpty()) {
            assertFalse(e.getMessage().contains(value), e.getMessage());
        }
    }

    @Test
    void lineBreaksAndControlCharactersInValuesAreRejected() {
        rejected("VECTOLYN_SERVER_HOST", "127.0.0.1\nonline-mode: false");
        rejected("VECTOLYN_DB_NAME", "ranks\r\n[evil]");
        rejected("VECTOLYN_DB_USER", "vectolyn'\r-- x");
        rejected("VECTOLYN_XMX", "768M\t");
        rejected("VECTOLYN_DB_HOST", "host\u0000");
        // The try order is a single line, unlike the server list and the forced hosts.
        rejected(ProxyServerList.TRY, "\"lobby\"\n[evil]");
    }

    @Test
    void onlyTheServerListAndTheForcedHostsMaySpanLines() throws TemplateException {
        PlaceholderValues values = PlaceholderValues.of(Map.of(
                ProxyServerList.SERVERS, "a = \"h:1\"\r\nb = \"h:2\"",
                ProxyServerList.FORCED_HOSTS, "\"x\" = [ \"a\" ]\n\"y\" = [ \"b\" ]"));
        assertEquals("a = \"h:1\"\nb = \"h:2\"\n\"x\" = [ \"a\" ]\n\"y\" = [ \"b\" ]\n", PlaceholderRenderer.render(
                "{{VECTOLYN_PROXY_SERVERS}}\n{{VECTOLYN_PROXY_FORCED_HOSTS}}\n", "v.toml", values));
        rejected(ProxyServerList.SERVERS, "a = \"h:1\"\tb");
    }

    @Test
    void secretsMustComeFromTheSafeCharacterSet() throws TemplateException {
        for (String name : List.of("VECTOLYN_DB_PASSWORD", "VECTOLYN_FORWARDING_SECRET", "VECTOLYN_MANAGEMENT_SECRET")) {
            rejected(name, "abcdefghijklmnop'");
            rejected(name, "abcdefghijklmnop#comment");
            rejected(name, "abcdefgh ijklmnop");
            rejected(name, "abcdefghijklmn\"op");
            rejected(name, "abcdefghijklmn\\op");
            rejected(name, "abc #comment\"'");
            rejected(name, "short-secret");
            rejected(name, "");
        }
        String base64 = "q83vEjRWeJCrze8SNFZ4kA==";
        String alphanumeric = "abcdefghijklmnopqrstuvwxyz0123456789ABCD";
        assertEquals(base64 + " " + alphanumeric, PlaceholderRenderer.render(
                "{{VECTOLYN_DB_PASSWORD}} {{VECTOLYN_MANAGEMENT_SECRET}}", "x",
                PlaceholderValues.of(Map.of("VECTOLYN_DB_PASSWORD", base64, "VECTOLYN_MANAGEMENT_SECRET", alphanumeric))));
    }

    @Test
    void portsMustBeNumbersFromOneTo65535() throws TemplateException {
        for (String bad : List.of("0", "65536", "99999", "-1", "+80", "80a", " 80", "", "080000")) {
            assertThrows(TemplateException.class, () -> PlaceholderRenderer.render("{{VECTOLYN_DB_PORT}}", "x",
                    PlaceholderValues.of(Map.of("VECTOLYN_DB_PORT", bad))), bad);
        }
        for (String good : List.of("1", "3307", "65535")) {
            assertEquals(good, PlaceholderRenderer.render("{{VECTOLYN_BEDROCK_PORT}}", "x",
                    PlaceholderValues.of(Map.of("VECTOLYN_BEDROCK_PORT", good))));
        }
    }

    @Test
    void multiLineValuesTakeTheLineEndingOfTheFile() throws TemplateException {
        assertEquals("[servers]\r\nlobby = \"127.0.0.1:25566\"\r\nend = \"127.0.0.1:25570\"\r\n",
                render("[servers]\r\n{{VECTOLYN_PROXY_SERVERS}}\r\n"));
        assertEquals("[servers]\nlobby = \"127.0.0.1:25566\"\nend = \"127.0.0.1:25570\"\n",
                render("[servers]\n{{VECTOLYN_PROXY_SERVERS}}\n"));
        assertEquals("lobby = \"127.0.0.1:25566\"\nend = \"127.0.0.1:25570\"",
                render("{{VECTOLYN_PROXY_SERVERS}}"));
    }

    @Test
    void utf8RenderingKeepsBomAndNonAsciiText() throws TemplateException {
        byte[] source = "﻿motd=Dein Netzwerk ◆ {{VECTOLYN_SERVER_PORT}} ✦\r\n".getBytes(UTF_8);
        byte[] rendered = PlaceholderRenderer.renderUtf8(source, "x.properties", VALUES);
        assertArrayEquals("﻿motd=Dein Netzwerk ◆ 25566 ✦\r\n".getBytes(UTF_8), rendered);
    }

    @Test
    void utf8RenderingWithoutPlaceholdersReturnsTheSameBytes() throws TemplateException {
        byte[] source = "a: ü\r\nb: 2\n".getBytes(UTF_8);
        assertArrayEquals(source, PlaceholderRenderer.renderUtf8(source, "x.yml", VALUES));
    }

    @Test
    void invalidUtf8IsAnErrorWithLine() {
        byte[] source = {'a', '\n', 'b', '\n', (byte) 0xC3, (byte) 0x28};
        TemplateException e = assertThrows(TemplateException.class,
                () -> PlaceholderRenderer.renderUtf8(source, "x.yml", VALUES));
        assertEquals("x.yml", e.file());
        assertEquals(3, e.line());
    }
}
