package net.vectolyn.cloud.console.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.vectolyn.cloud.config.model.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandLineParserTest {

    @Test
    void splitsOnSpacesAndTabs() throws Exception {
        assertEquals(List.of("config", "set", "proxy.port", "25566"),
                CommandLineParser.tokenize("  config set\tproxy.port   25566 "));
        assertEquals(List.of(), CommandLineParser.tokenize(""));
        assertEquals(List.of(), CommandLineParser.tokenize(" \t "));
    }

    @Test
    void quotesKeepSpacesAndSpecialCharacters() throws Exception {
        assertEquals(List.of("config", "set", "network-name", "My Network"),
                CommandLineParser.tokenize("config set network-name 'My Network'"));
        assertEquals(List.of("a b", "c\"d", "e\\f"), CommandLineParser.tokenize("\"a b\" \"c\\\"d\" \"e\\\\f\""));
        assertEquals(List.of("C:\\Vectolyn\\home"), CommandLineParser.tokenize("\"C:\\Vectolyn\\home\""),
                "a backslash before another character stays inside double quotes");
        assertEquals(List.of("it's"), CommandLineParser.tokenize("\"it's\""));
        assertEquals(List.of("say \"hi\""), CommandLineParser.tokenize("'say \"hi\"'"));
    }

    @Test
    void adjacentPartsFormOneWordAndEmptyQuotesAreAnEmptyWord() throws Exception {
        assertEquals(List.of("abc de"), CommandLineParser.tokenize("ab\"c d\"e"));
        assertEquals(List.of("config", "set", "database.docker-image", ""),
                CommandLineParser.tokenize("config set database.docker-image \"\""));
        assertEquals(List.of("a b"), CommandLineParser.tokenize("a\\ b"));
    }

    @Test
    void unclosedQuoteIsRefusedWithItsPosition() {
        CommandSyntaxException e = assertThrows(CommandSyntaxException.class, () -> CommandLineParser.tokenize("set 'abc"));
        assertEquals(CommandSyntaxException.Reason.UNCLOSED_QUOTE, e.reason());
        assertEquals(4, e.detail());
        CommandSyntaxException d = assertThrows(CommandSyntaxException.class, () -> CommandLineParser.tokenize("x \"a\\\""));
        assertEquals(CommandSyntaxException.Reason.UNCLOSED_QUOTE, d.reason());
    }

    @Test
    void danglingBackslashIsRefused() {
        CommandSyntaxException e = assertThrows(CommandSyntaxException.class, () -> CommandLineParser.tokenize("abc\\"));
        assertEquals(CommandSyntaxException.Reason.DANGLING_ESCAPE, e.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\nb", "a\rb", "a\u0000b", "'a\u001bb'", "\"a\u0007\"", "a\\\u0001", "a\u2028b", "a\u0085b"})
    void controlCharactersAreRefusedEverywhere(String line) {
        CommandSyntaxException e = assertThrows(CommandSyntaxException.class, () -> CommandLineParser.tokenize(line));
        assertEquals(CommandSyntaxException.Reason.CONTROL_CHARACTER, e.reason());
    }

    @Test
    void limitsAreEnforced() throws Exception {
        String longest = "a".repeat(CommandLineParser.MAX_LENGTH);
        assertEquals(List.of(longest), CommandLineParser.tokenize(longest));
        assertEquals(CommandSyntaxException.Reason.TOO_LONG,
                assertThrows(CommandSyntaxException.class, () -> CommandLineParser.tokenize(longest + "a")).reason());
        String words = "w ".repeat(CommandLineParser.MAX_WORDS);
        assertEquals(CommandLineParser.MAX_WORDS, CommandLineParser.tokenize(words).size());
        assertEquals(CommandSyntaxException.Reason.TOO_MANY_WORDS,
                assertThrows(CommandSyntaxException.class, () -> CommandLineParser.tokenize(words + "w")).reason());
    }

    @Test
    void messagesNameTheRuleNotTheInput() {
        String secret = "hunter2-very-secret";
        CommandSyntaxException e = assertThrows(CommandSyntaxException.class, () -> CommandLineParser.tokenize("'" + secret));
        assertFalse(e.getMessage().contains(secret));
        assertFalse(e.message(Language.DE).contains(secret));
        assertTrue(e.message(Language.DE).contains("Anführungszeichen"));
    }

    @Test
    void quoteRoundTrips() throws Exception {
        List<String> words = List.of("plain", "with space", "it's", "\"double\"", "back\\slash", "", "100%", "ä ö");
        assertEquals(words, CommandLineParser.tokenize(CommandLineParser.join(words)));
        assertEquals("plain", CommandLineParser.quote("plain"));
        assertEquals("'with space'", CommandLineParser.quote("with space"));
        assertThrows(IllegalArgumentException.class, () -> CommandLineParser.quote("a\nb"));
    }

    @Test
    void partialLinesForCompletion() {
        assertEquals(new CommandLineParser.Partial(List.of("con"), true), CommandLineParser.partial("con"));
        assertEquals(new CommandLineParser.Partial(List.of("config"), false), CommandLineParser.partial("config "));
        assertEquals(new CommandLineParser.Partial(List.of("set", "My Net"), true), CommandLineParser.partial("set 'My Net"),
                "an unclosed quote counts as closed while typing");
        assertEquals(new CommandLineParser.Partial(List.of(), false), CommandLineParser.partial(""));
        assertEquals(new CommandLineParser.Partial(List.of("a"), true), CommandLineParser.partial("a\\"));
    }
}
