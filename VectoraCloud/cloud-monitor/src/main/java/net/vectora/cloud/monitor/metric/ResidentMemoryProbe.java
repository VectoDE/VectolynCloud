package net.vectora.cloud.monitor.metric;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Reads the resident memory of other processes. Java has no portable API for it
 * ({@link ProcessHandle} only knows CPU time), so each operating system gets its own way:
 *
 * <table>
 *   <caption>Sources</caption>
 *   <tr><td>Linux</td><td>{@code /proc/<pid>/status}, line {@code VmRSS}</td></tr>
 *   <tr><td>Windows</td><td>{@code %SystemRoot%\System32\tasklist.exe /FO CSV /NH}, column "Mem Usage"
 *       (the working set), one call for all processes</td></tr>
 *   <tr><td>macOS, BSD, other Unix</td><td>{@code /bin/ps -o pid=,rss= -p <pids>}, one call per 100 pids</td></tr>
 *   <tr><td>anything else</td><td>nothing: the value stays unknown</td></tr>
 * </table>
 *
 * <p>Programs are started by absolute path, without a shell, with a time limit and a cap on the
 * output they may print. No operating system is refused; where nothing works the monitor shows
 * {@code -} for RSS and every other metric keeps working.
 */
public interface ResidentMemoryProbe {

    /** Time a helper program may take before it is killed. */
    Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);

    /** Most output read from a helper program: 4 MiB. */
    int MAX_OUTPUT = 4 * 1024 * 1024;

    /**
     * Measures the processes.
     *
     * @param pids process ids
     * @return resident bytes per pid that could be measured
     * @throws IOException if the source failed as a whole (the caller logs it once and carries on)
     */
    Map<Long, Long> measure(Set<Long> pids) throws IOException;

    /** Short name for the log, for example {@code procfs}. */
    String description();

    /** Runs a helper program and returns its standard output. */
    @FunctionalInterface
    interface CommandRunner {
        /**
         * Runs the command.
         *
         * @param command program (absolute path) and arguments
         * @return standard output as text
         * @throws IOException if it cannot start, fails, takes too long or prints too much
         */
        String run(List<String> command) throws IOException;
    }

    /**
     * Picks the probe for the running operating system.
     *
     * @return the probe; {@link #none()} where no source exists
     */
    static ResidentMemoryProbe forCurrentSystem() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path proc = Path.of("/proc/self/status");
        if (os.contains("linux") && Files.isReadable(proc)) {
            return procfs(Path.of("/proc"));
        }
        if (os.contains("windows")) {
            String root = System.getenv("SystemRoot");
            Path base = root != null && !root.isBlank() && Path.of(root).isAbsolute() ? Path.of(root) : Path.of("C:\\Windows");
            Path tasklist = base.resolve("System32").resolve("tasklist.exe");
            return Files.isRegularFile(tasklist) ? tasklist(tasklist, ResidentMemoryProbe::runCommand) : none();
        }
        for (String candidate : List.of("/bin/ps", "/usr/bin/ps")) {
            Path ps = Path.of(candidate);
            if (Files.isExecutable(ps)) {
                return ps(ps, ResidentMemoryProbe::runCommand);
            }
        }
        return none();
    }

    /** A probe that measures nothing. */
    static ResidentMemoryProbe none() {
        return new ResidentMemoryProbe() {
            @Override
            public Map<Long, Long> measure(Set<Long> pids) {
                return Map.of();
            }

            @Override
            public String description() {
                return "none";
            }
        };
    }

    /**
     * Linux: reads {@code <proc>/<pid>/status}.
     *
     * @param proc mount point of procfs, normally {@code /proc}
     * @return the probe
     */
    static ResidentMemoryProbe procfs(Path proc) {
        Objects.requireNonNull(proc, "proc");
        return new ResidentMemoryProbe() {
            @Override
            public Map<Long, Long> measure(Set<Long> pids) {
                Map<Long, Long> result = new HashMap<>();
                for (long pid : pids) {
                    if (pid <= 0) {
                        continue;
                    }
                    try {
                        byte[] raw;
                        try (InputStream in = Files.newInputStream(proc.resolve(Long.toString(pid)).resolve("status"))) {
                            raw = in.readNBytes(64 * 1024);
                        }
                        parseProcStatus(new String(raw, StandardCharsets.US_ASCII)).ifPresent(rss -> result.put(pid, rss));
                    } catch (IOException gone) {
                        // The process ended between listing and reading; no value for it.
                    }
                }
                return result;
            }

            @Override
            public String description() {
                return "procfs";
            }
        };
    }

    /**
     * macOS, BSD and other Unix: {@code ps -o pid=,rss= -p <pids>}.
     *
     * @param ps     absolute path of {@code ps}
     * @param runner runs the program
     * @return the probe
     */
    static ResidentMemoryProbe ps(Path ps, CommandRunner runner) {
        Objects.requireNonNull(ps, "ps");
        Objects.requireNonNull(runner, "runner");
        return new ResidentMemoryProbe() {
            @Override
            public Map<Long, Long> measure(Set<Long> pids) throws IOException {
                List<Long> valid = pids.stream().filter(p -> p > 0).sorted().toList();
                Map<Long, Long> result = new HashMap<>();
                for (int from = 0; from < valid.size(); from += 100) {
                    String list = valid.subList(from, Math.min(valid.size(), from + 100)).stream()
                            .map(String::valueOf).collect(Collectors.joining(","));
                    String output;
                    try {
                        output = runner.run(List.of(ps.toString(), "-o", "pid=,rss=", "-p", list));
                    } catch (IOException e) {
                        // ps exits with 1 when none of the pids exists any more.
                        if (e instanceof ExitCodeException) {
                            continue;
                        }
                        throw e;
                    }
                    parsePs(output).forEach((pid, rss) -> {
                        if (pids.contains(pid)) {
                            result.put(pid, rss);
                        }
                    });
                }
                return result;
            }

            @Override
            public String description() {
                return "ps";
            }
        };
    }

    /**
     * Windows: {@code tasklist /FO CSV /NH}, once for all processes.
     *
     * @param tasklist absolute path of {@code tasklist.exe}
     * @param runner   runs the program
     * @return the probe
     */
    static ResidentMemoryProbe tasklist(Path tasklist, CommandRunner runner) {
        Objects.requireNonNull(tasklist, "tasklist");
        Objects.requireNonNull(runner, "runner");
        return new ResidentMemoryProbe() {
            @Override
            public Map<Long, Long> measure(Set<Long> pids) throws IOException {
                if (pids.isEmpty()) {
                    return Map.of();
                }
                Map<Long, Long> all = parseTasklistCsv(runner.run(List.of(tasklist.toString(), "/FO", "CSV", "/NH")));
                Map<Long, Long> result = new HashMap<>();
                for (long pid : pids) {
                    Long rss = all.get(pid);
                    if (rss != null) {
                        result.put(pid, rss);
                    }
                }
                return result;
            }

            @Override
            public String description() {
                return "tasklist";
            }
        };
    }

    /**
     * Reads {@code VmRSS} from the text of {@code /proc/<pid>/status}.
     *
     * @param status file content
     * @return resident bytes, or empty if the line is missing or malformed (kernel threads have none)
     */
    static OptionalLong parseProcStatus(String status) {
        for (String line : status.split("\n", -1)) {
            if (!line.startsWith("VmRSS:")) {
                continue;
            }
            String[] parts = line.substring("VmRSS:".length()).trim().split("\\s+");
            if (parts.length != 2 || !parts[1].equals("kB")) {
                return OptionalLong.empty();
            }
            long kilobytes = parseCount(parts[0]);
            return kilobytes < 0 ? OptionalLong.empty() : OptionalLong.of(kilobytes * 1024);
        }
        return OptionalLong.empty();
    }

    /**
     * Reads the output of {@code ps -o pid=,rss=}: one {@code <pid> <kilobytes>} pair per line.
     * Malformed lines are skipped.
     *
     * @param output the program's output
     * @return resident bytes per pid
     */
    static Map<Long, Long> parsePs(String output) {
        Map<Long, Long> result = new HashMap<>();
        for (String line : output.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 2) {
                continue;
            }
            long pid = parseCount(parts[0]);
            long kilobytes = parseCount(parts[1]);
            if (pid > 0 && kilobytes >= 0) {
                result.put(pid, kilobytes * 1024);
            }
        }
        return result;
    }

    /**
     * Reads the CSV output of {@code tasklist /FO CSV /NH}:
     * {@code "image","pid","session","session#","12.345 K"}. The memory column is formatted in
     * the user's locale ({@code 12.345 K}, {@code 12,345 K}, {@code 12 345 K}); all non-digits are
     * dropped, which leaves the kilobytes in every locale. Malformed lines are skipped.
     *
     * @param output the program's output
     * @return resident bytes per pid
     */
    static Map<Long, Long> parseTasklistCsv(String output) {
        Map<Long, Long> result = new HashMap<>();
        for (String line : output.split("\\R")) {
            List<String> fields = csvFields(line);
            if (fields.size() < 5) {
                continue;
            }
            long pid = parseCount(fields.get(1));
            String memory = fields.get(fields.size() - 1);
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < memory.length(); i++) {
                char c = memory.charAt(i);
                if (c >= '0' && c <= '9') {
                    digits.append(c);
                }
            }
            long kilobytes = parseCount(digits.toString());
            if (pid > 0 && kilobytes >= 0) {
                result.put(pid, kilobytes * 1024);
            }
        }
        return result;
    }

    private static List<String> csvFields(String line) {
        List<String> fields = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            if (line.charAt(i) != '"') {
                return List.of();
            }
            StringBuilder field = new StringBuilder();
            i++;
            while (true) {
                if (i >= line.length()) {
                    return List.of();
                }
                char c = line.charAt(i++);
                if (c == '"') {
                    if (i < line.length() && line.charAt(i) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        break;
                    }
                } else {
                    field.append(c);
                }
            }
            fields.add(field.toString());
            if (i < line.length()) {
                if (line.charAt(i) != ',') {
                    return List.of();
                }
                i++;
            }
        }
        return fields;
    }

    /** Parses 1 to 15 decimal digits; anything else gives -1. */
    private static long parseCount(String text) {
        if (text.isEmpty() || text.length() > 15) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        return Long.parseLong(text);
    }

    /** A helper program ended with an exit code other than 0. */
    final class ExitCodeException extends IOException {

        private static final long serialVersionUID = 1L;

        ExitCodeException(int code) {
            super("exit code " + code);
        }
    }

    /**
     * Runs a program without a shell: no input, error output discarded, standard output capped at
     * {@link #MAX_OUTPUT}, killed after {@link #COMMAND_TIMEOUT}. {@code VECTORA_*} variables of the
     * node are not passed on.
     *
     * @param command program and arguments
     * @return standard output (UTF-8; the parsed parts are ASCII)
     * @throws IOException on start failure, time-out, too much output or an exit code other than 0
     */
    static String runCommand(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(key -> key.startsWith("VECTORA_"));
        Process process = builder.start();
        CompletableFuture<Void> watchdog = CompletableFuture.runAsync(() -> {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }, CompletableFuture.delayedExecutor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        try {
            byte[] out;
            try (InputStream in = process.getInputStream()) {
                out = in.readNBytes(MAX_OUTPUT + 1);
            }
            if (out.length > MAX_OUTPUT) {
                process.destroyForcibly();
                throw new IOException("helper program printed more than " + MAX_OUTPUT + " bytes");
            }
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("helper program took longer than " + COMMAND_TIMEOUT.toSeconds() + " s");
            }
            if (process.exitValue() != 0) {
                throw new ExitCodeException(process.exitValue());
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for a helper program", e);
        } finally {
            watchdog.cancel(false);
        }
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                ? "NUL" : "/dev/null");
    }
}
