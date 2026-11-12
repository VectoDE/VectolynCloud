// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.tools.apicompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the real japicmp (path from Gradle) on jars compiled here. */
class ApiCompatTest {

    private static Japicmp japicmp() {
        String jar = System.getProperty("vectolyn.japicmp");
        assertTrue(jar != null && !jar.isEmpty(), "run through Gradle, which passes -Dvectolyn.japicmp");
        return Japicmp.withCurrentJava(Path.of(jar));
    }

    /** Compiles one class {@code api.Greeter} and packs it into a jar. */
    private static Path jar(Path dir, String name, String source) throws IOException {
        Path src = dir.resolve(name + "-src/api/Greeter.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source, StandardCharsets.UTF_8);
        Path classes = dir.resolve(name + "-classes");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, OutputStream.nullOutputStream(), System.err, "--release", "21", "-d",
                classes.toString(), src.toString());
        assertEquals(0, status, "compile " + name);
        Path jar = dir.resolve(name + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static final String V1 = "package api; public class Greeter { public String hello() { return \"hi\"; } }";
    private static final String V2_ADDED = "package api; public class Greeter { public String hello() { return \"hi\"; }"
            + " public String bye() { return \"bye\"; } }";
    private static final String V2_REMOVED = "package api; public class Greeter { }";

    private static ApiCompat.Options options(Path dir, Path old, Path now, String next) {
        return new ApiCompat.Options(Path.of("unused"), "v0.1.1-dev", next, dir.resolve("report/api.md"),
                List.of(new ApiCompat.Module("common/protocol", old, now, List.of(), List.of())));
    }

    private static int run(ApiCompat.Options options, StringBuilder printed) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = ApiCompat.run(options, japicmp(), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        printed.append(bytes.toString(StandardCharsets.UTF_8));
        return code;
    }

    @Test
    void anAddedMethodIsCompatible(@TempDir Path dir) throws IOException {
        StringBuilder out = new StringBuilder();
        assertEquals(0, run(options(dir, jar(dir, "old", V1), jar(dir, "new", V2_ADDED), "0.1.2-SNAPSHOT"), out));
        assertTrue(out.toString().startsWith("API compatible with v0.1.1-dev"), out.toString());
        assertTrue(Files.readString(dir.resolve("report/api.md")).contains("| `common/protocol` | compatible |"));
    }

    @Test
    void aRemovedMethodFailsUnlessTheVersionIsABreakingStep(@TempDir Path dir) throws IOException {
        Path old = jar(dir, "old", V1);
        Path now = jar(dir, "new", V2_REMOVED);

        StringBuilder out = new StringBuilder();
        assertEquals(1, run(options(dir, old, now, "0.1.2-SNAPSHOT"), out));
        assertTrue(out.toString().contains("raise version in gradle.properties to 0.2.0-SNAPSHOT"), out.toString());
        String report = Files.readString(dir.resolve("report/api.md"));
        assertTrue(report.contains("**incompatible**"), report);
        assertTrue(report.contains("hello"), "the report names the removed method: " + report);

        StringBuilder allowed = new StringBuilder();
        assertEquals(0, run(options(dir, old, now, "0.2.0-SNAPSHOT"), allowed));
        assertTrue(allowed.toString().contains("allowed"), allowed.toString());
    }

    @Test
    void aMissingJarIsAnErrorNotAnIncompatibility(@TempDir Path dir) throws IOException {
        Path now = jar(dir, "new", V1);
        assertThrows(IOException.class, () -> run(options(dir, dir.resolve("missing.jar"), now, "0.1.2"),
                new StringBuilder()));
    }

    @Test
    void argumentsAreChecked() {
        ApiCompat.Options parsed = ApiCompat.parse(new String[] {"--japicmp", "j.jar", "--baseline", "v1.0.0",
            "--next", "1.1.0-SNAPSHOT", "--report", "r.md", "--module", "common/client", "o.jar", "n.jar",
            "op.jar", "-"});
        assertEquals(1, parsed.modules().size());
        assertEquals(List.of(Path.of("op.jar")), parsed.modules().getFirst().oldClasspath());
        assertFalse(parsed.modules().getFirst().newClasspath().iterator().hasNext());
        assertThrows(IllegalArgumentException.class, () -> ApiCompat.parse(new String[] {"--baseline", "v1.0.0"}));
        assertThrows(IllegalArgumentException.class, () -> ApiCompat.parse(new String[] {"--japicmp", "j",
            "--baseline", "main", "--next", "1.0.0", "--report", "r", "--module", "m", "o", "n", "-", "-"}));
        assertThrows(IllegalArgumentException.class, () -> ApiCompat.parse(new String[] {"--module", "m"}));
        assertThrows(IllegalArgumentException.class, () -> ApiCompat.parse(new String[] {"--what"}));
    }
}
