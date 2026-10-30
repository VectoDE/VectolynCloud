package net.vectolyn.cloud.api.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectolyn.cloud.api.auth.ApiTokens;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.console.Console;
import net.vectolyn.cloud.console.command.CommandDispatcher.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiCommandsTest {

    private static final Pattern TOKEN = Pattern.compile("vct_[A-Za-z0-9_-]{43}");

    @TempDir
    Path dir;

    private ApiTokens tokens;
    private ByteArrayOutputStream bytes;
    private Console console;

    @BeforeEach
    void setUp() throws Exception {
        tokens = ApiTokens.open(dir.resolve("secrets/api-tokens.json"),
                Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC), new SecureRandom());
        bytes = new ByteArrayOutputStream();
        console = Console.builder()
                .streams(new ByteArrayInputStream(new byte[0]), new PrintStream(bytes, true, StandardCharsets.UTF_8))
                .build();
        console.commands().register(new ApiCommands(tokens).command());
    }

    @AfterEach
    void tearDown() {
        console.close();
    }

    private String output() {
        String text = bytes.toString(StandardCharsets.UTF_8);
        bytes.reset();
        return text;
    }

    @Test
    void createShowsTheTokenOnceAndListNever() {
        assertEquals(Outcome.DONE, console.dispatcher().execute("api token create dashboard services:read,config:read"));
        String created = output();
        Matcher matcher = TOKEN.matcher(created);
        assertTrue(matcher.find(), created);
        String token = matcher.group();
        assertFalse(matcher.find(), "shown exactly once");
        assertTrue(tokens.authenticate(token).isPresent());
        assertEquals(java.util.Set.of(Scope.SERVICES_READ, Scope.CONFIG_READ),
                tokens.authenticate(token).orElseThrow().scopes());

        assertEquals(Outcome.DONE, console.dispatcher().execute("api token list"));
        String listed = output();
        assertTrue(listed.contains("dashboard"), listed);
        assertFalse(TOKEN.matcher(listed).find(), "list never shows a token");
        assertFalse(listed.contains(token.substring(4, 20)));

        assertEquals(Outcome.DONE, console.dispatcher().execute("api token revoke dashboard"));
        assertTrue(tokens.authenticate(token).isEmpty());
    }

    @Test
    void noApiLineReachesTheHistory() {
        assertTrue(console.commands().isSensitive("api token create x admin"));
        assertTrue(console.commands().isSensitive("api token list"));
        assertTrue(console.commands().isSensitive("api token delete x"));
        assertTrue(console.commands().isSensitive("api \"token\" create x 'admin"), "even with a syntax error");
        assertFalse(console.commands().isSensitive("help"), "counter-check");
    }

    @Test
    void badInputCreatesNothing() {
        for (String line : new String[] {"api token create Bad-Name admin", "api token create ok root",
                "api token create ok services:read,", "api token revoke ../x"}) {
            console.dispatcher().execute(line);
            assertFalse(TOKEN.matcher(output()).find(), line);
        }
        assertTrue(tokens.list().isEmpty());
        console.dispatcher().execute("api token create ok admin");
        output();
        console.dispatcher().execute("api token create ok admin");
        assertFalse(TOKEN.matcher(output()).find(), "a second token with the same name is refused");
        assertEquals(1, tokens.list().size());
    }

    @Test
    void aBrokenTokenFileIsReportedUntilItIsRepaired() throws Exception {
        Path file = dir.resolve("broken/api-tokens.json");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, "{ not json");
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        try (Console broken = Console.builder()
                .streams(new ByteArrayInputStream(new byte[0]), new PrintStream(screen, true, StandardCharsets.UTF_8))
                .build()) {
            broken.commands().register(new ApiCommands(() -> ApiTokens.open(file, Clock.systemUTC(),
                    new SecureRandom())).command());
            for (String line : new String[] {"api token list", "api token create ok admin", "api token revoke ok"}) {
                assertEquals(Outcome.FAILED, broken.dispatcher().execute(line), line);
                String text = screen.toString(StandardCharsets.UTF_8);
                screen.reset();
                assertTrue(text.contains("secrets/api-tokens.json cannot be read"), line + ": " + text);
                assertFalse(TOKEN.matcher(text).find(), line);
            }
            assertEquals(Outcome.DONE, broken.dispatcher().execute("api scopes"), "needs no token file");

            // Deleted: the next command opens the store and works from then on.
            java.nio.file.Files.delete(file);
            assertEquals(Outcome.DONE, broken.dispatcher().execute("api token create ok admin"));
            assertTrue(TOKEN.matcher(screen.toString(StandardCharsets.UTF_8)).find());
            assertEquals(Outcome.DONE, broken.dispatcher().execute("api token list"));
        }
    }
}
