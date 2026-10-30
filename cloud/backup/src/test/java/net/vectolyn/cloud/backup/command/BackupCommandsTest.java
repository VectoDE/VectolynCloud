package net.vectolyn.cloud.backup.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.vectolyn.cloud.backup.BackupInfo;
import net.vectolyn.cloud.backup.BackupReason;
import net.vectolyn.cloud.backup.BackupRequest;
import net.vectolyn.cloud.backup.BackupService;
import net.vectolyn.cloud.backup.ServiceControl.State;
import net.vectolyn.cloud.backup.TestHome;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.console.Console;
import net.vectolyn.cloud.console.command.CommandDispatcher.Outcome;
import net.vectolyn.cloud.console.terminal.ConsoleTerminal;
import net.vectolyn.cloud.console.terminal.ReadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code backup} command in a real console with scripted input. */
class BackupCommandsTest {

    @TempDir
    Path home;

    private TestHome test;
    private BackupService service;
    private ScriptedTerminal terminal;
    private Console console;
    private Language language = Language.EN;

    @BeforeEach
    void setUp() throws Exception {
        test = new TestHome(home);
        test.addServer("lobby-1", "lobby", "Lobby-Server", true);
        service = test.service();
        terminal = new ScriptedTerminal();
        console = Console.builder().terminal(terminal).language(() -> language).build();
        BackupCommands.register(console.commands(), service);
    }

    private Outcome run(String line) {
        return console.dispatcher().execute(line);
    }

    @Test
    void createAndList() throws Exception {
        assertEquals(Outcome.DONE, run("backup create"));
        assertTrue(terminal.output().contains("Backup 20261029-031500-"), terminal.output());
        assertEquals(1, service.list().size());

        terminal.printed.clear();
        assertEquals(Outcome.DONE, run("backup list"));
        assertTrue(terminal.output().contains("manual"), terminal.output());
        assertTrue(terminal.output().contains("lobby-1"), terminal.output());

        language = Language.DE;
        terminal.printed.clear();
        assertEquals(Outcome.DONE, run("backups list"));
        assertTrue(terminal.output().contains("Angelegt"), terminal.output());
    }

    @Test
    void createOfAnUnknownServiceFails() {
        assertEquals(Outcome.FAILED, run("backup create nope-1"));
        assertTrue(terminal.output().contains("no static service nope-1"), terminal.output());
        assertEquals(Outcome.FAILED, run("backup create ../x"));
    }

    @Test
    void deleteAsksAndEnterMeansNo() throws Exception {
        BackupInfo info = service.create(BackupRequest.all(BackupReason.MANUAL));

        terminal.type("");
        assertEquals(Outcome.DONE, run("backup delete " + info.id()));
        assertTrue(Files.exists(info.file()), "Enter keeps it");
        assertTrue(terminal.output().contains("Nothing was deleted."));

        terminal.type("yes");
        assertEquals(Outcome.DONE, run("backup delete " + info.id()));
        assertFalse(Files.exists(info.file()));
    }

    @Test
    void deleteWithYesFlagAndUnknownIds() throws Exception {
        BackupInfo info = service.create(BackupRequest.all(BackupReason.MANUAL));
        assertEquals(Outcome.FAILED, run("backup delete " + info.id() + " --force"));
        assertTrue(Files.exists(info.file()));
        assertEquals(Outcome.DONE, run("backup delete " + info.id() + " --yes"));
        assertFalse(Files.exists(info.file()));
        assertEquals(Outcome.FAILED, run("backup delete " + info.id() + " -y"), "already gone");
        assertTrue(terminal.reads.isEmpty(), "never asked");
    }

    @Test
    void restoreIsRefusedWhileRunningAndWorksWhenStopped() throws Exception {
        BackupInfo info = service.create(BackupRequest.all(BackupReason.MANUAL));
        test.control.state("lobby-1", State.RUNNING);
        assertEquals(Outcome.FAILED, run("backup restore " + info.id() + " lobby-1 --yes"));
        assertTrue(terminal.output().contains("must be stopped") || terminal.output().contains("not stopped"),
                terminal.output());

        test.control.state("lobby-1", State.STOPPED);
        terminal.type("no");
        assertEquals(Outcome.DONE, run("backup restore " + info.id() + " lobby-1"));
        assertTrue(terminal.output().contains("Nothing was restored."));

        terminal.type("y");
        assertEquals(Outcome.DONE, run("backup restore " + info.id() + " lobby-1"));
        assertTrue(terminal.output().contains("Restored lobby-1"), terminal.output());
        assertTrue(terminal.output().contains("The previous state is in backup"), terminal.output());
    }

    @Test
    void completionOffersBackupsAndServices() throws Exception {
        BackupInfo info = service.create(BackupRequest.all(BackupReason.MANUAL));
        List<String> backups = net.vectolyn.cloud.console.command.CommandDispatcher.complete(console.commands(),
                "backup restore ");
        assertTrue(backups.contains(info.id()), backups.toString());
        List<String> services = net.vectolyn.cloud.console.command.CommandDispatcher.complete(console.commands(),
                "backup restore " + info.id() + " ");
        assertTrue(services.contains("lobby-1"), services.toString());
    }

    @Test
    void sizesAreHumanReadable() {
        assertEquals("512 B", BackupCommands.size(512));
        assertEquals("1.5 KiB", BackupCommands.size(1536));
        assertEquals("2.0 GiB", BackupCommands.size(2L * 1024 * 1024 * 1024));
        assertEquals("?", BackupCommands.size(-1));
    }

    /** Hands out typed lines and records everything printed. */
    private static final class ScriptedTerminal implements ConsoleTerminal {

        final Deque<String> input = new ArrayDeque<>();
        final List<String> printed = new CopyOnWriteArrayList<>();
        final List<String> reads = new CopyOnWriteArrayList<>();

        void type(String line) {
            input.add(line);
        }

        String output() {
            return String.join("\n", printed);
        }

        @Override
        public ReadResult readLine(String prompt, LineKind kind) {
            reads.add(prompt);
            String next = input.poll();
            return next == null ? new ReadResult.EndOfInput() : new ReadResult.Line(next);
        }

        @Override
        public void print(String text) {
            printed.add(text);
        }

        @Override
        public boolean ansi() {
            return false;
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        public boolean hidesSecrets() {
            return true;
        }

        @Override
        public void clearScreen() {
        }

        @Override
        public String description() {
            return "scripted";
        }

        @Override
        public void close() {
        }
    }
}
