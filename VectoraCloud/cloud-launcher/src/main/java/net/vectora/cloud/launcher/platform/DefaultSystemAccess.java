package net.vectora.cloud.launcher.platform;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** {@link SystemAccess} backed by the real JVM, file system and processes. */
public final class DefaultSystemAccess implements SystemAccess {

    private static final int MAX_FILE_BYTES = 64 * 1024;
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;
    private static final long COMMAND_TIMEOUT_SECONDS = 10;

    /** Creates the accessor. */
    public DefaultSystemAccess() {
    }

    @Override
    public Optional<String> env(String name) {
        return Optional.ofNullable(System.getenv(name));
    }

    @Override
    public Optional<String> property(String name) {
        return Optional.ofNullable(System.getProperty(name));
    }

    @Override
    public boolean exists(Path path) {
        try {
            return Files.exists(path);
        } catch (SecurityException e) {
            return false;
        }
    }

    @Override
    public Optional<String> readFile(Path path) {
        try {
            try (InputStream in = Files.newInputStream(path)) {
                byte[] data = in.readNBytes(MAX_FILE_BYTES);
                return Optional.of(new String(data, StandardCharsets.UTF_8));
            }
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> run(List<String> command) {
        // The output goes to a temporary file rather than a pipe, so a command that hangs without
        // closing its output cannot block the launcher: waitFor() with the timeout always runs.
        Path output;
        try {
            output = Files.createTempFile("vectora-launcher-", ".out");
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return Optional.empty();
        }
        try {
            return runInto(command, output);
        } finally {
            try {
                Files.deleteIfExists(output);
            } catch (IOException | SecurityException e) {
                output.toFile().deleteOnExit();
            }
        }
    }

    private static Optional<String> runInto(List<String> command, Path output) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return Optional.empty();
        }
        try {
            process.getOutputStream().close();
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            byte[] data;
            try (InputStream in = Files.newInputStream(output)) {
                data = in.readNBytes(MAX_OUTPUT_BYTES + 1);
            }
            if (data.length > MAX_OUTPUT_BYTES) {
                return Optional.empty();
            }
            return Optional.of(new String(data, nativeCharset()));
        } catch (IOException | SecurityException e) {
            process.destroyForcibly();
            return Optional.empty();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @Override
    public Optional<Long> unixUid() {
        try {
            Class<?> type = Class.forName("com.sun.security.auth.module.UnixSystem");
            Object system = type.getConstructor().newInstance();
            Object uid = type.getMethod("getUid").invoke(system);
            return uid instanceof Long value ? Optional.of(value) : Optional.empty();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<List<String>> windowsGroupSids() {
        try {
            Class<?> type = Class.forName("com.sun.security.auth.module.NTSystem");
            Object system = type.getConstructor().newInstance();
            Object groups = type.getMethod("getGroupIDs").invoke(system);
            if (groups instanceof String[] sids) {
                return Optional.of(Arrays.asList(sids));
            }
            return Optional.empty();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean interactiveTerminal() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        // From Java 22 on, System.console() may return a console even without a terminal;
        // Console.isTerminal() (Java 22+) tells the difference. On Java 21 a non-null console
        // already means a terminal.
        try {
            Method isTerminal = Console.class.getMethod("isTerminal");
            return Boolean.TRUE.equals(isTerminal.invoke(console));
        } catch (NoSuchMethodException e) {
            return true;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            return false;
        }
    }

    private static Charset nativeCharset() {
        String name = System.getProperty("native.encoding");
        try {
            return name == null ? Charset.defaultCharset() : Charset.forName(name);
        } catch (RuntimeException e) {
            return Charset.defaultCharset();
        }
    }
}
