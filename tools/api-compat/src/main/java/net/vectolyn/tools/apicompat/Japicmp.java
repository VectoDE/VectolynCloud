// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.tools.apicompat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Runs the japicmp command line ({@code japicmp-<version>-jar-with-dependencies.jar}, Apache-2.0)
 * in its own JVM and reports whether the new jar is binary and source compatible with the old one.
 * Only public and protected API counts (japicmp's default access level is protected).
 */
public final class Japicmp {

    /** What japicmp prints when it found an incompatible change and was asked to fail on it. */
    static final String INCOMPATIBLE_MARKER = "There is at least one incompatibility";

    private static final long TIMEOUT_MINUTES = 5;

    private final Path japicmpJar;
    private final Path java;

    /**
     * @param japicmpJar the japicmp jar with its dependencies
     * @param java       the {@code java} executable that runs it
     */
    public Japicmp(Path japicmpJar, Path java) {
        this.japicmpJar = Objects.requireNonNull(japicmpJar, "japicmpJar");
        this.java = Objects.requireNonNull(java, "java");
    }

    /** Uses the {@code java} of the running JVM. */
    public static Japicmp withCurrentJava(Path japicmpJar) {
        String exe = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return new Japicmp(japicmpJar, Path.of(System.getProperty("java.home"), "bin", exe));
    }

    /**
     * The result for one module.
     *
     * @param compatible whether no binary or source incompatible change was found
     * @param output     what japicmp printed, only the incompatible changes
     */
    public record Result(boolean compatible, String output) {
    }

    /**
     * Compares two versions of a jar.
     *
     * @param oldJar       the released jar
     * @param newJar       the jar built now
     * @param oldClasspath jars the old one needs (may be empty)
     * @param newClasspath jars the new one needs (may be empty)
     * @return the result
     * @throws IOException if japicmp cannot run or fails for another reason than an incompatibility
     */
    public Result compare(Path oldJar, Path newJar, List<Path> oldClasspath, List<Path> newClasspath)
            throws IOException {
        requireFile(oldJar);
        requireFile(newJar);
        List<String> command = new ArrayList<>(List.of(java.toString(), "-jar", japicmpJar.toString(),
                "--old", oldJar.toString(), "--new", newJar.toString(),
                "--only-incompatible", "--error-on-binary-incompatibility", "--error-on-source-incompatibility"));
        if (!oldClasspath.isEmpty()) {
            command.add("--old-classpath");
            command.add(join(oldClasspath));
        }
        if (!newClasspath.isEmpty()) {
            command.add("--new-classpath");
            command.add(join(newClasspath));
        }
        Path log = Files.createTempFile("japicmp", ".txt");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("japicmp did not finish within " + TIMEOUT_MINUTES + " minutes");
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (process.exitValue() == 0) {
                return new Result(true, output);
            }
            if (output.contains(INCOMPATIBLE_MARKER)) {
                return new Result(false, output);
            }
            throw new IOException("japicmp failed (exit " + process.exitValue() + "):\n" + output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while japicmp ran", e);
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static void requireFile(Path jar) throws IOException {
        if (!Files.isRegularFile(jar)) {
            throw new IOException("jar not found: " + jar);
        }
    }

    private static String join(List<Path> paths) {
        return String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
    }
}
