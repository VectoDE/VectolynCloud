package net.vectora.cloud.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import net.vectora.cloud.console.command.Argument;
import net.vectora.cloud.console.command.Command;
import net.vectora.cloud.console.terminal.ConsoleTerminal;
import net.vectora.cloud.console.terminal.PlainTerminal;
import net.vectora.cloud.console.terminal.ReadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The read loop, with and without a terminal. */
@Timeout(30)
class ConsoleTest {

    private final List<String> calls = new ArrayList<>();

    private Console withEcho(ConsoleTerminal terminal) {
        Console console = Console.builder().terminal(terminal).build();
        console.commands().register(Command.builder("echo").description(Text.of("e", "e"))
                .argument(Argument.required("text", Text.of("t", "t")).takingRest())
                .executes(c -> calls.add(c.argument("text"))).build());
        console.commands().register(Command.builder("shutdown").description(Text.of("s", "s"))
                .executes(c -> {
                    calls.add("shutdown");
                    console.stop();
                }).build());
        return console;
    }

    @Test
    void withoutTerminalStdinIsReadLineByLineAndItsEndDoesNotShutDown() {
        String input = "echo one\r\necho 'two words'\n\necho three";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlainTerminal plain = new PlainTerminal(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
        Console console = withEcho(plain);
        console.run();
        assertEquals(List.of("one", "two words", "three"), calls, "CRLF, quotes and a last line without break");
        assertFalse(console.isRunning());
        assertFalse(calls.contains("shutdown"), "the end of stdin (systemd, docker without -i) must not stop the cloud");
        assertEquals("", out.toString(StandardCharsets.UTF_8), "no prompt is printed into a log viewer");
        assertTrue(new PlainTerminal(new ByteArrayInputStream(new byte[0]), new PrintStream(out)).readLine("", ConsoleTerminal.LineKind.COMMAND)
                instanceof ReadResult.EndOfInput);
    }

    @Test
    void plainTerminalCutsOverlongLinesAndPrintsQuestionPrompts() {
        String longLine = "echo " + "x".repeat(10_000) + "\necho ok\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlainTerminal plain = new PlainTerminal(new ByteArrayInputStream(longLine.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
        Console console = withEcho(plain);
        console.run();
        assertEquals(List.of("ok"), calls, "the overlong line is refused, the next one still runs");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("longer than 4096"));
        assertFalse(plain.ansi());
        assertFalse(plain.interactive());
        assertFalse(plain.hidesSecrets(), "stdin cannot be masked");

        ByteArrayOutputStream asked = new ByteArrayOutputStream();
        PlainTerminal questions = new PlainTerminal(new ByteArrayInputStream("x\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(asked, true, StandardCharsets.UTF_8));
        questions.readLine("Name: ", ConsoleTerminal.LineKind.ANSWER);
        assertTrue(asked.toString(StandardCharsets.UTF_8).startsWith("Name: "));
    }

    @Test
    void onATerminalCtrlDAndCtrlCRunTheShutdownCommand() {
        FakeTerminal terminal = new FakeTerminal().type("echo a").endOfInput().type("echo never");
        withEcho(terminal).run();
        assertEquals(List.of("a", "shutdown"), calls);

        calls.clear();
        FakeTerminal interrupted = new FakeTerminal().interrupt();
        withEcho(interrupted).run();
        assertEquals(List.of("shutdown"), calls);
    }

    @Test
    void withoutShutdownCommandCtrlCJustStopsReading() {
        FakeTerminal terminal = new FakeTerminal().interrupt().type("never");
        Console console = Console.builder().terminal(terminal).build();
        console.run();
        assertFalse(console.isRunning());
        assertEquals(1, terminal.pending());
    }

    @Test
    void promptIsColoredOnlyOnAnsiTerminals() {
        FakeTerminal plain = new FakeTerminal(false, true).endOfInput();
        Console.builder().terminal(plain).prompt(() -> "vectora@node-1> ").build().run();
        assertEquals("vectora@node-1> ", plain.reads().getFirst().prompt());

        FakeTerminal ansi = new FakeTerminal(true, true).endOfInput();
        Console.builder().terminal(ansi).build().run();
        assertEquals("\u001b[36mvectora> \u001b[0m", ansi.reads().getFirst().prompt());
    }

    @Test
    void startRunsOnANamedDaemonThread() throws Exception {
        FakeTerminal terminal = new FakeTerminal().type("echo threaded");
        Console console = withEcho(terminal);
        Thread thread = console.start();
        assertEquals("vectora-console", thread.getName());
        assertTrue(thread.isDaemon());
        thread.join(10_000);
        assertEquals(List.of("threaded", "shutdown"), calls);
        console.close();
        assertTrue(terminal.closed());
    }

    @Test
    void logHandlerPrintsAboveThePromptAndRedacts() {
        FakeTerminal terminal = new FakeTerminal(true, true);
        Console console = Console.builder().terminal(terminal).build();
        ConsoleLogHandler handler = console.logHandler(line -> line.replace("s3cret", "***"));
        LogRecord info = new LogRecord(Level.INFO, "Service {0} started with s3cret");
        info.setParameters(new Object[] {"lobby-1"});
        handler.publish(info);
        LogRecord warning = new LogRecord(Level.WARNING, "Disk almost full");
        warning.setThrown(new IllegalStateException("only 1 GB left"));
        handler.publish(warning);
        assertTrue(terminal.printed().get(0).endsWith("INFO Service lobby-1 started with ***"), terminal.printed().get(0));
        assertTrue(terminal.printed().get(1).contains("WARNING Disk almost full (IllegalStateException: only 1 GB left)"));
        assertTrue(terminal.printed().get(1).startsWith("\u001b[33m"), "warnings are yellow on an ANSI terminal");

        handler.publish(new LogRecord(Level.INFO, "file \u001b[2Jname\rforged\u009b line\r\nnext\tcolumn"));
        assertTrue(terminal.printed().get(2).endsWith("INFO file ?[2Jname?forged? line\nnext\tcolumn"), terminal.printed().get(2));
    }

    @Test
    void styledOutputCannotCarryForeignEscapeSequences() {
        FakeTerminal terminal = new FakeTerminal(true, true);
        ConsoleOutput out = Console.builder().terminal(terminal).build().output();
        out.error("bad \u001b]0;title\u0007 name");
        assertEquals("\u001b[31mbad ?]0;title? name\u001b[0m", terminal.printed().getFirst());
        out.warning("a\r\nb");
        assertEquals("\u001b[33ma\nb\u001b[0m", terminal.printed().getLast());
    }
}
