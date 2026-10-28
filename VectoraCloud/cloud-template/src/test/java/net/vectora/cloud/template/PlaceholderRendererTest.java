package net.vectora.cloud.template;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaceholderRendererTest {

    private static final PlaceholderValues VALUES = PlaceholderValues.of(Map.of(
            "VECTORA_SERVER_PORT", "25566",
            "VECTORA_DB_PASSWORD", "hunter2",
            "VECTORA_PROXY_SERVERS", "lobby = \"127.0.0.1:25566\"\nend = \"127.0.0.1:25570\""));

    private static String render(String text) throws TemplateException {
        return PlaceholderRenderer.render(text, "server.properties", VALUES);
    }

    @Test
    void replacesEveryPlaceholder() throws TemplateException {
        assertEquals("server-port=25566\npw=hunter2 25566\n",
                render("server-port={{VECTORA_SERVER_PORT}}\npw={{VECTORA_DB_PASSWORD}} {{VECTORA_SERVER_PORT}}\n"));
    }

    @Test
    void textWithoutPlaceholdersStaysTheSame() throws TemplateException {
        String text = "a: { b: 1 }\nmotd: \"<gray>{x}</gray> }}\"\n";
        assertSame(text, render(text));
    }

    @Test
    void unknownPlaceholderFailsWithFileAndLine() {
        TemplateException e = assertThrows(TemplateException.class,
                () -> render("a=1\nb=2\nc={{VECTORA_NOT_THERE}}\n"));
        assertEquals("server.properties", e.file());
        assertEquals(3, e.line());
        assertTrue(e.getMessage().startsWith("server.properties:3: "), e.getMessage());
        assertTrue(e.getMessage().contains("VECTORA_NOT_THERE"), e.getMessage());
    }

    @Test
    void placeholderOutsideTheVectoraNamespaceIsAnError() {
        TemplateException e = assertThrows(TemplateException.class, () -> render("x\r\ny={{name}}"));
        assertEquals(2, e.line());
    }

    @Test
    void unclosedPlaceholderIsAnError() {
        TemplateException e = assertThrows(TemplateException.class, () -> render("\r\r{{VECTORA_SERVER_PORT"));
        assertEquals(3, e.line());
    }

    @Test
    void placeholderWithSpacesIsAnError() {
        assertThrows(TemplateException.class, () -> render("{{ VECTORA_SERVER_PORT }}"));
    }

    @Test
    void emptyPlaceholderIsAnError() {
        assertThrows(TemplateException.class, () -> render("{{}}"));
        assertThrows(TemplateException.class, () -> render("{{VECTORA_}}"));
    }

    @Test
    void fourBracesAreALiteralDoubleBrace() throws TemplateException {
        assertEquals("a {{ b", render("a {{{{ b"));
        assertEquals("{{VECTORA_SERVER_PORT}}", render("{{{{VECTORA_SERVER_PORT}}"));
        assertEquals("{{25566", render("{{{{{{VECTORA_SERVER_PORT}}"));
    }

    @Test
    void errorMessagesNeverContainValues() {
        TemplateException e = assertThrows(TemplateException.class,
                () -> render("pw={{VECTORA_DB_PASSWORD}}\n{{VECTORA_MISSING}}"));
        assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
    }

    @Test
    void multiLineValuesTakeTheLineEndingOfTheFile() throws TemplateException {
        assertEquals("[servers]\r\nlobby = \"127.0.0.1:25566\"\r\nend = \"127.0.0.1:25570\"\r\n",
                render("[servers]\r\n{{VECTORA_PROXY_SERVERS}}\r\n"));
        assertEquals("[servers]\nlobby = \"127.0.0.1:25566\"\nend = \"127.0.0.1:25570\"\n",
                render("[servers]\n{{VECTORA_PROXY_SERVERS}}\n"));
        assertEquals("lobby = \"127.0.0.1:25566\"\nend = \"127.0.0.1:25570\"",
                render("{{VECTORA_PROXY_SERVERS}}"));
    }

    @Test
    void utf8RenderingKeepsBomAndNonAsciiText() throws TemplateException {
        byte[] source = "﻿motd=Dein Netzwerk ◆ {{VECTORA_SERVER_PORT}} ✦\r\n".getBytes(UTF_8);
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
