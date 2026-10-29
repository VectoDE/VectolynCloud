package net.vectora.cloud.monitor.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.console.Console;
import net.vectora.cloud.console.command.CommandDispatcher.Outcome;
import net.vectora.cloud.console.terminal.ConsoleTerminal;
import net.vectora.cloud.console.terminal.ReadResult;
import net.vectora.cloud.monitor.Fakes;
import net.vectora.cloud.monitor.Monitor;
import net.vectora.cloud.monitor.ServicePhase;
import net.vectora.cloud.monitor.config.MonitorConfigLoader;
import net.vectora.cloud.monitor.metric.MetricSource;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.ServiceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MonitorCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-29T12:00:00Z");

    /** Records what the console prints. */
    private static final class Screen implements ConsoleTerminal {
        final List<String> printed = new CopyOnWriteArrayList<>();

        @Override
        public ReadResult readLine(String prompt, LineKind kind) {
            return new ReadResult.EndOfInput();
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
            return false;
        }

        @Override
        public boolean hidesSecrets() {
            return false;
        }

        @Override
        public void clearScreen() {
        }

        @Override
        public String description() {
            return "screen";
        }

        @Override
        public void close() {
        }

        String text() {
            return String.join("\n", printed);
        }
    }

    private final Fakes.MutableClock clock = new Fakes.MutableClock(T0);
    private final Fakes.Directory directory = new Fakes.Directory();
    private final Fakes.Source source = new Fakes.Source();
    private final AtomicReference<Language> language = new AtomicReference<>(Language.EN);
    private Screen screen;
    private Console console;
    private Monitor monitor;

    @BeforeEach
    void setUp() throws Exception {
        directory.running("lobby-1", "lobby", 100).put("proxy-1", "proxy", true, ServicePhase.RUNNING, 200, 0)
                .put("survival-1", "survival", false, ServicePhase.STOPPED, 0, 2);
        source.processes.put(100L, new MetricSource.ProcessMetrics(4, 800L << 20));
        monitor = Monitor.builder(directory, source, MonitorConfigLoader.parse(
                        "schema-version: 1\nrules:\n  - name: low-tps\n    when: tps < 18\n".getBytes(StandardCharsets.UTF_8)))
                .clock(clock).build();
        screen = new Screen();
        console = Console.builder().terminal(screen).language(language::get).build();
        MonitorCommands commands = new MonitorCommands(monitor, ZoneOffset.UTC);
        console.commands().register(commands.monitor());
        console.commands().register(commands.alerts());
    }

    @AfterEach
    void tearDown() {
        monitor.close();
        console.close();
    }

    private Outcome run(String line) {
        screen.printed.clear();
        return console.dispatcher().execute(line);
    }

    private void samples(double... tps) {
        for (double value : tps) {
            monitor.statusReported(new ServiceStatus(new ServiceId("lobby-1"), 3, 100, value, 12.5, 512L << 20, 1L << 30));
            monitor.statusReported(new ServiceStatus(new ServiceId("proxy-1"), 3, 500, 20, 0, 100L << 20, 1L << 30));
            monitor.sampleNow();
            clock.advanceSeconds(10);
        }
    }

    @Test
    void overviewShowsNodeServicesAndAlerts() {
        samples(20, 19.5);
        assertEquals(Outcome.DONE, run("monitor"));
        String out = screen.text();
        assertTrue(out.contains("Node: CPU 10%, memory 4.0 GiB / 16.0 GiB (25%), free in home 100.0 GiB"), out);
        assertTrue(out.matches("(?s).*lobby-1\\s+running\\s+3/100\\s+19\\.5\\s+12\\.5\\s+512\\.0 MiB / 1\\.0 GiB\\s+4%\\s+800\\.0 MiB\\s+0\\s+0.*"), out);
        assertTrue(out.matches("(?s).*proxy-1\\s+running\\s+3/500\\s+-\\s+-\\s+.*"), "no TPS for proxies: " + out);
        assertTrue(out.matches("(?s).*survival-1\\s+stopped\\s+-\\s+-\\s+-\\s+-\\s+-\\s+-\\s+2\\s+0.*"), out);
        assertTrue(out.contains("No active alerts."), out);
    }

    @Test
    void overviewInGerman() {
        samples(20);
        language.set(Language.DE);
        run("monitor");
        assertTrue(screen.text().contains("Node: CPU 10%, Speicher"), screen.text());
        assertTrue(screen.text().contains("Keine aktiven Warnungen."), screen.text());
    }

    @Test
    void chartOfOneService() {
        samples(20, 20, 10, 10, 20);
        assertEquals(Outcome.DONE, run("monitor lobby-1"));
        String out = screen.text();
        assertTrue(out.contains("lobby-1 tps (2026-10-29 12:00:00 - 2026-10-29 12:00:40)"), out);
        assertTrue(out.contains("20.0 |"), out);
        assertTrue(out.contains("now 20.0  min 10.0  avg 16.0  max 20.0"), out);
        assertEquals(Outcome.DONE, run("monitor lobby-1 rss"));
        assertTrue(screen.text().contains("800.0 MiB"), screen.text());
        assertEquals(Outcome.DONE, run("monitor proxy-1"));
        assertTrue(screen.text().contains("proxy-1 players"), "proxies chart players by default: " + screen.text());
        assertEquals(Outcome.DONE, run("monitor proxy-1 tps"));
        assertTrue(screen.text().contains("is a proxy"), screen.text());
        assertEquals(Outcome.DONE, run("monitor survival-1"));
        assertTrue(screen.text().contains("No tps values for survival-1 yet."), screen.text());
    }

    @Test
    void unknownServiceOrMetricFailsWithoutEchoingIt() {
        assertEquals(Outcome.FAILED, run("monitor nope-1"));
        assertTrue(screen.text().contains("Unknown service."), screen.text());
        assertFalse(screen.text().contains("nope-1"));
        assertEquals(Outcome.FAILED, run("monitor lobby-1 crashes"));
        assertTrue(screen.text().contains("Unknown metric. Choose one of tps, mspt, heap, heap-used, players, cpu, rss."),
                screen.text());
    }

    @Test
    void alertsListAndAcknowledge() {
        assertEquals(Outcome.DONE, run("alerts list"));
        assertTrue(screen.text().contains("No alerts."));
        samples(12);
        assertEquals(Outcome.DONE, run("alerts list"));
        String out = screen.text();
        assertTrue(out.matches("(?s).*#1\\s+warning\\s+low-tps\\s+lobby-1\\s+2026-10-29 12:00:00\\s+tps 12 \\(tps < 18\\).*"), out);
        assertEquals(Outcome.FAILED, run("alerts ack 2"));
        assertTrue(screen.text().contains("No active, unacknowledged alert #2."), screen.text());
        assertEquals(Outcome.FAILED, run("alerts ack x1"));
        assertEquals(Outcome.DONE, run("alerts ack #1"));
        assertTrue(screen.text().contains("Alert #1 acknowledged."));
        run("alerts list");
        assertTrue(screen.text().contains("acknowledged"), screen.text());
        assertEquals(Outcome.DONE, run("alerts ack all"));
        assertTrue(screen.text().contains("0 alert(s) acknowledged."), screen.text());
        samples(20);
        run("alerts list");
        assertTrue(screen.text().matches("(?s).*Recently ended.*#1.*cleared.*"), screen.text());
        run("monitor");
        assertTrue(screen.text().contains("No active alerts."));
    }

    @Test
    void alertsWithoutSubcommandIsAUsageError() {
        assertEquals(Outcome.USAGE_ERROR, run("alerts"));
    }
}
