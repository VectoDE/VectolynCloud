package net.vectora.cloud.console.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.console.Console;
import net.vectora.cloud.console.Text;
import net.vectora.cloud.console.command.Argument;
import net.vectora.cloud.console.command.Command;
import net.vectora.cloud.console.prompt.Question;
import org.jline.terminal.Attributes;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JLine console on an in-memory terminal: history file, secrets, tab completion source,
 * Ctrl+C and Ctrl+D. JLine's dumb terminal reads the bytes as if typed.
 */
@org.junit.jupiter.api.Timeout(20)
class JLineTerminalTest {

    @TempDir
    Path home;

    private static DumbTerminal terminal(String typed, ByteArrayOutputStream out) throws Exception {
        return new DumbTerminal("test", "dumb", new ByteArrayInputStream(typed.getBytes(StandardCharsets.UTF_8)),
                out, StandardCharsets.UTF_8);
    }

    private static DumbTerminal terminalWithSignals(String typed, ByteArrayOutputStream out) throws Exception {
        DumbTerminal terminal = new DumbTerminal("test", "dumb", new ByteArrayInputStream(typed.getBytes(StandardCharsets.UTF_8)),
                out, StandardCharsets.UTF_8);
        // As on a real terminal in cooked mode: Ctrl+C raises SIGINT, which JLine turns into an interrupt.
        Attributes attributes = terminal.getAttributes();
        attributes.setLocalFlag(Attributes.LocalFlag.ISIG, true);
        attributes.setControlChar(Attributes.ControlChar.VINTR, 3);
        terminal.setAttributes(attributes);
        return terminal;
    }

    @Test
    void secretsAnswersAndSensitiveCommandsNeverReachTheHistoryFile() throws Exception {
        Path historyFile = TerminalFactory.prepareHistory(CloudHome.of(home));
        assertEquals(home.resolve(TerminalFactory.HISTORY_FILE), historyFile);
        String secret = "Sup3r-Secret-Value";
        String typed = String.join("\n", "config get proxy.port", "token set " + secret, " leading-space hidden",
                "wizard", secret, "answer-text", "config list", "") ;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Console[] holder = new Console[1];
        JLineTerminal jline = new JLineTerminal(terminal(typed, out), line -> List.of(), historyFile,
                line -> !holder[0].commands().isSensitive(line), "test");
        Console console = Console.builder().terminal(jline).build();
        holder[0] = console;
        Secret[] asked = new Secret[1];
        String[] answered = new String[1];
        console.commands().register(Command.builder("token")
                .description(Text.of("t", "t"))
                .subcommand(Command.builder("set").sensitive().description(Text.of("t", "t"))
                        .argument(Argument.required("value", Text.of("v", "v"))).executes(c -> { }))
                .build());
        console.commands().register(Command.builder("wizard").description(Text.of("w", "w")).executes(c -> {
            asked[0] = c.prompter().ask(Question.secret(Text.of("Password", "Passwort")));
            answered[0] = c.prompter().ask(Question.text(Text.of("Name", "Name")));
        }).build());
        console.commands().register(Command.builder("config").description(Text.of("c", "c"))
                .subcommand(Command.builder("get").description(Text.of("g", "g"))
                        .argument(Argument.required("key", Text.of("k", "k"))).executes(c -> { }))
                .subcommand(Command.builder("list").description(Text.of("l", "l")).executes(c -> { }))
                .build());

        console.run(); // ends with Ctrl+D (end of the typed bytes), which runs nothing: no shutdown command
        console.close();

        assertEquals(secret, asked[0].reveal());
        assertEquals("answer-text", answered[0]);
        assertEquals(List.of("config get proxy.port", "wizard", "config list"), jline.historyLines());
        String file = Files.readString(historyFile, StandardCharsets.UTF_8);
        assertFalse(file.contains(secret), "the secret is not in the history file");
        assertFalse(file.contains("answer-text"), "answers are not in the history file");
        assertFalse(file.contains("token set"), "sensitive commands are not in the history file");
        assertFalse(file.contains("leading-space"), "a leading space keeps a line out of the history");
        assertTrue(file.contains("config get proxy.port"), "ordinary commands are");
        assertFalse(out.toString(StandardCharsets.UTF_8).contains(secret), "the secret is not echoed: " + out);
    }

    @Test
    void counterCheckWithoutFilterTheSensitiveLineWouldBeStored() throws Exception {
        // Shows that the assertions above test the filter, not an accident of the setup.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JLineTerminal jline = new JLineTerminal(terminal("token set abc\nsecret-text\n", out), line -> List.of(), null, line -> true, "test");
        assertInstanceOf(ReadResult.Line.class, jline.readLine("> ", ConsoleTerminal.LineKind.COMMAND));
        assertEquals(List.of("token set abc"), jline.historyLines());
        assertInstanceOf(ReadResult.Line.class, jline.readLine("> ", ConsoleTerminal.LineKind.SECRET));
        assertEquals(List.of("token set abc"), jline.historyLines());
        jline.close();
    }

    @Test
    void ctrlCAndCtrlDRunShutdown() throws Exception {
        for (String key : List.of("\u0003", "\u0004")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String typed = key + "never\n";
            JLineTerminal jline = new JLineTerminal(key.equals("\u0003") ? terminalWithSignals(typed, out) : terminal(typed, out),
                    line -> List.of(), null, line -> true, "test");
            Console console = Console.builder().terminal(jline).build();
            int[] shutdowns = new int[1];
            console.commands().register(Command.builder("never").description(Text.of("n", "n")).executes(c -> {
                throw new AssertionError("the key must stop the loop before the next line");
            }).build());
            console.commands().register(Command.builder("shutdown").description(Text.of("s", "s")).executes(c -> {
                shutdowns[0]++;
                console.stop();
            }).build());
            console.run();
            assertEquals(1, shutdowns[0], "key " + (int) key.charAt(0) + " runs shutdown once");
            assertFalse(console.isRunning());
            console.close();
        }
    }

    @Test
    void ctrlCMapsToInterruptedAndPrintGoesAboveThePrompt() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JLineTerminal jline = new JLineTerminal(terminalWithSignals("\u0003", out), line -> List.of(), null, line -> true, "test");
        jline.print("log line");
        assertInstanceOf(ReadResult.Interrupted.class, jline.readLine("> ", ConsoleTerminal.LineKind.COMMAND));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("log line"));
        assertFalse(jline.ansi(), "a dumb terminal gets no colors");
        assertTrue(jline.interactive());
        jline.close();
    }

    @Test
    void historyFileThatIsALinkIsNotUsed() throws Exception {
        Path target = Files.writeString(home.resolve("elsewhere.txt"), "");
        Files.createDirectories(home.resolve("state"));
        try {
            Files.createSymbolicLink(home.resolve(TerminalFactory.HISTORY_FILE), target);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links cannot be created here: " + e);
        }
        assertEquals(null, TerminalFactory.prepareHistory(CloudHome.of(home)));
    }
}
