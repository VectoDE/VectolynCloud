package net.vectora.cloud.download.jre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.vectora.cloud.download.provider.TargetPlatform;
import net.vectora.cloud.download.provider.TargetPlatform.Arch;
import net.vectora.cloud.download.provider.TargetPlatform.Libc;
import net.vectora.cloud.download.provider.TargetPlatform.Os;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemJavaFinderTest {

    private static final TargetPlatform LINUX_X64 = new TargetPlatform(Os.LINUX, Arch.X64, Libc.GLIBC);

    @TempDir
    Path dir;

    private final List<Path> probed = new ArrayList<>();

    private JavaProbe noProbe() {
        return executable -> {
            probed.add(executable);
            return Optional.empty();
        };
    }

    /** A fake Java home with a release file and an (empty) bin/java. */
    private Path home(String name, String version, String arch) throws Exception {
        Path home = Files.createDirectories(dir.resolve(name));
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin/java"), "");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nOS_ARCH=\"" + arch + "\"\n");
        return home;
    }

    private SystemJavaFinder finder(Map<String, String> environment, JavaProbe probe) {
        return new SystemJavaFinder(environment, LINUX_X64, probe);
    }

    @Test
    void javaHomeWithTheRightVersionIsUsedWithoutStartingIt() throws Exception {
        Path home = home("temurin-25", "25.0.5", "x86_64");
        Optional<JavaRuntime> found = finder(Map.of("JAVA_HOME", home.toString()), noProbe()).find(25);
        assertTrue(found.isPresent());
        assertEquals(home.toRealPath(), found.get().home());
        assertEquals(home.toRealPath().resolve("bin/java"), found.get().executable());
        assertEquals("25.0.5", found.get().version());
        assertEquals(JavaRuntime.Origin.SYSTEM, found.get().origin());
        assertEquals(List.of(), probed, "the release file is enough");
    }

    @Test
    void aDifferentFeatureVersionDoesNotCount() throws Exception {
        Path home = home("temurin-25", "25.0.5", "x86_64");
        assertTrue(finder(Map.of("JAVA_HOME", home.toString()), noProbe()).find(21).isEmpty());
        // Java 26 is newer, but "fits" means the same feature version: the legacy groups need exactly 21.
        assertTrue(finder(Map.of("JAVA_HOME", home("temurin-26", "26", "x86_64").toString()), noProbe()).find(25)
                .isEmpty());
    }

    @Test
    void searchesThePathAfterJavaHome() throws Exception {
        Path java25 = home("temurin-25", "25.0.5", "x86_64");
        Path java21 = home("temurin-21", "21.0.13", "amd64");
        String path = String.join(File.pathSeparator, java25.resolve("bin").toString(), java21.resolve("bin").toString());
        SystemJavaFinder finder = finder(Map.of("PATH", path, "JAVA_HOME", java25.toString()), noProbe());
        assertEquals(java21.toRealPath(), finder.find(21).orElseThrow().home());
        assertEquals(2, finder.runtimes().size(), "each home once, although java25 is in JAVA_HOME and PATH");
    }

    @Test
    void aForeignArchitectureIsSkipped() throws Exception {
        Path arm = home("temurin-21-arm", "21.0.13", "aarch64");
        assertTrue(finder(Map.of("JAVA_HOME", arm.toString()), noProbe()).find(21).isEmpty());
    }

    @Test
    void relativePathEntriesAreNeverUsed() throws Exception {
        home("bin-parent", "21.0.13", "x86_64");
        SystemJavaFinder finder = finder(Map.of("PATH", "bin-parent/bin" + File.pathSeparator + "."), noProbe());
        assertEquals(List.of(), finder.candidates());
        assertTrue(finder.find(21).isEmpty());
    }

    @Test
    void withoutReleaseFileTheExecutableIsAsked() throws Exception {
        Path real = home("real-home", "21.0.13", "x86_64");
        Path stub = Files.createDirectories(dir.resolve("javapath"));
        Files.writeString(stub.resolve("java"), "");
        JavaProbe probe = executable -> {
            probed.add(executable);
            return Optional.of(JavaSettings.parse("    java.home = " + real + "\n    java.specification.version = 21\n"
                    + "    java.version = 21.0.13\n    os.arch = amd64\n"));
        };
        Optional<JavaRuntime> found = finder(Map.of("PATH", stub.toString()), probe).find(21);
        assertEquals(List.of(stub.resolve("java")), probed);
        assertEquals(real.toRealPath(), found.orElseThrow().home());
        assertEquals("21.0.13", found.get().version());
    }

    @Test
    void anExecutableThatDoesNotAnswerIsSkipped() throws Exception {
        Path stub = Files.createDirectories(dir.resolve("broken"));
        Files.writeString(stub.resolve("java"), "");
        assertTrue(finder(Map.of("PATH", stub.toString()), noProbe()).find(21).isEmpty());
        assertEquals(1, probed.size());
    }

    @Test
    void windowsLooksForJavaExeAndReadsPathCaseInsensitively() throws Exception {
        Path home = Files.createDirectories(dir.resolve("win"));
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin/java.exe"), "");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"25.0.5\"\nOS_ARCH=\"amd64\"\n");
        SystemJavaFinder finder = new SystemJavaFinder(Map.of("Path", "\"" + home.resolve("bin") + "\""),
                new TargetPlatform(Os.WINDOWS, Arch.X64, Libc.NONE), noProbe());
        assertEquals(home.toRealPath().resolve("bin/java.exe"), finder.find(25).orElseThrow().executable());
    }

    @Test
    void findsTheJavaRunningThisTest() throws Exception {
        // Real check on Java 21 and 25 in CI: the test JVM's own home has a release file.
        Path home = Path.of(System.getProperty("java.home"));
        int feature = Runtime.version().feature();
        JavaRuntime runtime = new SystemJavaFinder(Map.of("JAVA_HOME", home.toString()), TargetPlatform.current(),
                JavaProbe.process()).find(feature).orElseThrow();
        assertEquals(home.toRealPath(), runtime.home());
        assertTrue(Files.isExecutable(runtime.executable()), runtime.executable().toString());
    }

    @Test
    void theProcessProbeReadsARealJvm() throws Exception {
        Path home = Path.of(System.getProperty("java.home"));
        boolean windows = TargetPlatform.current().os() == Os.WINDOWS;
        JavaSettings settings = JavaProbe.process().probe(home.resolve("bin").resolve(JavaRuntime.executableName(windows)))
                .orElseThrow();
        assertEquals(Runtime.version().feature(), settings.feature().orElseThrow());
        assertEquals(home.toRealPath(), Path.of(settings.home().orElseThrow()).toRealPath());
        assertTrue(JavaProbe.process().probe(dir.resolve("no-such-java")).isEmpty());
    }
}
