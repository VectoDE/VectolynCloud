package net.vectora.cloud.monitor.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResidentMemoryProbeTest {

    @Test
    void parsesProcStatus() {
        String status = "Name:\tjava\nVmPeak:\t 9000 kB\nVmRSS:\t  123456 kB\nThreads:\t40\n";
        assertEquals(OptionalLong.of(123456L * 1024), ResidentMemoryProbe.parseProcStatus(status));
        assertEquals(OptionalLong.empty(), ResidentMemoryProbe.parseProcStatus("Name:\tkthreadd\n"), "kernel threads");
        assertEquals(OptionalLong.empty(), ResidentMemoryProbe.parseProcStatus("VmRSS:\t12 MB\n"));
        assertEquals(OptionalLong.empty(), ResidentMemoryProbe.parseProcStatus("VmRSS:\t-12 kB\n"));
        assertEquals(OptionalLong.empty(), ResidentMemoryProbe.parseProcStatus("VmRSS:\t9999999999999999 kB\n"));
    }

    @Test
    void readsProcfsAndSkipsGoneProcesses(@TempDir Path proc) throws Exception {
        Files.createDirectories(proc.resolve("42"));
        Files.writeString(proc.resolve("42").resolve("status"), "VmRSS:\t2048 kB\n");
        Map<Long, Long> result = ResidentMemoryProbe.procfs(proc).measure(Set.of(42L, 43L, -1L));
        assertEquals(Map.of(42L, 2048L * 1024), result);
    }

    @Test
    void parsesPsOutput() {
        assertEquals(Map.of(101L, 2048L * 1024, 7L, 0L),
                ResidentMemoryProbe.parsePs("  101  2048\n    7     0\ngarbage\n 9 x\n\n -3 5\n"));
    }

    @Test
    void psIsCalledInBatchesAndAnEmptyResultIsNoError() throws Exception {
        List<List<String>> calls = new ArrayList<>();
        ResidentMemoryProbe probe = ResidentMemoryProbe.ps(Path.of("/bin/ps"), command -> {
            calls.add(command);
            if (calls.size() == 2) {
                throw new ResidentMemoryProbe.ExitCodeException(1);
            }
            return "1 10\n";
        });
        Set<Long> pids = new java.util.HashSet<>();
        for (long pid = 1; pid <= 150; pid++) {
            pids.add(pid);
        }
        assertEquals(Map.of(1L, 10240L), probe.measure(pids));
        assertEquals(2, calls.size(), "100 pids per call");
        assertEquals(List.of("/bin/ps", "-o", "pid=,rss=", "-p"), calls.get(0).subList(0, 4).stream()
                .map(s -> s.replace('\\', '/')).toList());
        ResidentMemoryProbe broken = ResidentMemoryProbe.ps(Path.of("/bin/ps"), command -> {
            throw new IOException("cannot start");
        });
        assertThrows(IOException.class, () -> broken.measure(Set.of(1L)));
    }

    @Test
    void parsesTasklistInEveryLocale() {
        String english = "\"System Idle Process\",\"0\",\"Services\",\"0\",\"8 K\"\r\n"
                + "\"java.exe\",\"4242\",\"Console\",\"1\",\"1,234,567 K\"\r\n";
        String german = "\"java.exe\",\"4242\",\"Console\",\"1\",\"1.234.567 K\"\r\n";
        String french = "\"java.exe\",\"4242\",\"Console\",\"1\",\"1\u00a0234\u00a0567 Ko\"\r\n";
        String quoted = "\"my \"\"odd\"\", app.exe\",\"77\",\"Console\",\"1\",\"10 K\"\r\n";
        assertEquals(1_234_567L * 1024, ResidentMemoryProbe.parseTasklistCsv(english).get(4242L));
        assertEquals(null, ResidentMemoryProbe.parseTasklistCsv(english).get(0L), "pid 0 is skipped");
        assertEquals(1_234_567L * 1024, ResidentMemoryProbe.parseTasklistCsv(german).get(4242L));
        assertEquals(1_234_567L * 1024, ResidentMemoryProbe.parseTasklistCsv(french).get(4242L));
        assertEquals(10L * 1024, ResidentMemoryProbe.parseTasklistCsv(quoted).get(77L));
        assertTrue(ResidentMemoryProbe.parseTasklistCsv("INFO: No tasks are running.\r\n\"a\",\"1\"\r\n\"x\r\n").isEmpty());
    }

    @Test
    void tasklistOnlyReturnsTheAskedPids() throws Exception {
        ResidentMemoryProbe probe = ResidentMemoryProbe.tasklist(Path.of("C:\\Windows\\System32\\tasklist.exe"),
                command -> "\"a.exe\",\"1\",\"C\",\"1\",\"1 K\"\n\"b.exe\",\"2\",\"C\",\"1\",\"2 K\"\n");
        assertEquals(Map.of(2L, 2048L), probe.measure(Set.of(2L, 3L)));
        assertTrue(probe.measure(Set.of()).isEmpty());
    }

    @Test
    void theProbeOfThisSystemMeasuresThisProcessOrReportsNothing() throws Exception {
        ResidentMemoryProbe probe = ResidentMemoryProbe.forCurrentSystem();
        long self = ProcessHandle.current().pid();
        Map<Long, Long> result = probe.measure(Set.of(self));
        if (probe.description().equals("none")) {
            assertTrue(result.isEmpty());
        } else {
            assertTrue(result.getOrDefault(self, 0L) > 1024 * 1024, probe.description() + ": " + result);
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("linux")) {
            assertEquals("procfs", probe.description());
        } else if (os.contains("windows")) {
            assertEquals("tasklist", probe.description());
        }
    }

    @Test
    void helperProgramsThatFailOrDoNotExistGiveIoExceptions() {
        assertThrows(IOException.class, () -> ResidentMemoryProbe.runCommand(List.of(
                Path.of(System.getProperty("java.home"), "bin", "no-such-program").toString())));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        assertThrows(ResidentMemoryProbe.ExitCodeException.class,
                () -> ResidentMemoryProbe.runCommand(List.of(java, "-XX:+NoSuchOption")));
    }

    @Test
    void aHelperProgramThatRunsOutOfTimeIsATimeOutAndNoExitCode(@TempDir Path dir) throws Exception {
        // Killed by the time limit, the program has an exit code (1 on Windows, 137 on Unix). It must
        // not pass for a program that failed on its own: the ps probe skips those as "pid gone".
        Path sleeper = dir.resolve("Sleeper.java");
        Files.writeString(sleeper, "class Sleeper { public static void main(String[] a) throws Exception {"
                + " Thread.sleep(120_000); } }");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        IOException e = assertThrows(IOException.class, () -> ResidentMemoryProbe.runCommand(
                List.of(java, sleeper.toString()), Duration.ofSeconds(1)));
        assertFalse(e instanceof ResidentMemoryProbe.ExitCodeException, e.toString());
        assertTrue(e.getMessage().contains("took longer than 1 s"), e.getMessage());
    }
}
