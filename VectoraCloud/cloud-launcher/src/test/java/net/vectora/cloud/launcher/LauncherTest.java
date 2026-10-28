package net.vectora.cloud.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import net.vectora.cloud.launcher.libs.LibraryException;
import org.junit.jupiter.api.Test;

class LauncherTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private Path launchedHome;
    private Map<String, String> launchedSettings;

    private int run(FakeSystemAccess system, String... args) {
        return run(system, (home, settings) -> {
            launchedHome = home;
            launchedSettings = settings;
        }, args);
    }

    private int run(FakeSystemAccess system, Launcher.NodeLauncher node, String... args) {
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        return new Launcher(system, out, err, node).run(args);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void rootWithoutSwitchExitsWithCode2AndExplains() {
        int code = run(FakeSystemAccess.linuxRoot());
        assertEquals(Launcher.EXIT_ELEVATED, code);
        assertNull(launchedSettings, "node must not start");
        assertTrue(err().contains("does not start with administrator or root rights"), err());
        assertTrue(err().contains("User=vectora"), "systemd hint");
        assertTrue(err().contains("--user 1000:1000"), "Docker hint");
        assertTrue(err().contains("--allow-root"), "override hint");
    }

    @Test
    void rootMessageInGermanWithLangArgument() {
        int code = run(FakeSystemAccess.linuxRoot(), "--lang", "de");
        assertEquals(Launcher.EXIT_ELEVATED, code);
        assertTrue(err().contains("startet nicht mit Administrator- bzw. root-Rechten"), err());
    }

    @Test
    void rootMessageInGermanFromUserLanguage() {
        run(FakeSystemAccess.linuxRoot().property("user.language", "de"));
        assertTrue(err().contains("Eigener Benutzer"), err());
    }

    @Test
    void elevatedWindowsGetsWindowsInstructions() {
        FakeSystemAccess system = new FakeSystemAccess()
                .property("os.name", "Windows 11")
                .windowsSids("S-1-16-12288");
        assertEquals(Launcher.EXIT_ELEVATED, run(system));
        assertTrue(err().contains("Run as administrator"), err());
        assertFalse(err().contains("useradd"), err());
    }

    @Test
    void rootIsCheckedBeforeArguments() {
        // An unknown argument does not matter while the root check refuses.
        assertEquals(Launcher.EXIT_ELEVATED, run(FakeSystemAccess.linuxRoot(), "--nonsense"));
    }

    @Test
    void rootWithSwitchStartsWithWarning() {
        int code = run(FakeSystemAccess.linuxRoot(), "--allow-root");
        assertEquals(Launcher.EXIT_OK, code);
        assertTrue(err().contains("WARNING"), err());
        assertNotNull(launchedSettings);
        assertEquals("true", launchedSettings.get("root-allowed"));
    }

    @Test
    void rootWithEnvironmentVariableStartsWithWarning() {
        int code = run(FakeSystemAccess.linuxRoot().env("VECTORA_ALLOW_ROOT", " TRUE "), "--lang", "de");
        assertEquals(Launcher.EXIT_OK, code);
        assertTrue(err().contains("WARNUNG"), err());
        assertNotNull(launchedSettings);
    }

    @Test
    void environmentVariableOtherThanTrueDoesNotAllow() {
        assertEquals(Launcher.EXIT_ELEVATED, run(FakeSystemAccess.linuxRoot().env("VECTORA_ALLOW_ROOT", "1")));
    }

    @Test
    void normalUserStartsWithoutWarning() {
        int code = run(FakeSystemAccess.linuxUser().property("user.dir", "/srv/cloud"),
                "--non-interactive", "--lang", "de");
        assertEquals(Launcher.EXIT_OK, code);
        assertEquals("", err());
        assertEquals(Path.of("/srv/cloud").toAbsolutePath().normalize(), launchedHome);
        assertEquals("de", launchedSettings.get("language"));
        assertEquals("true", launchedSettings.get("non-interactive"));
        assertEquals("false", launchedSettings.get("root-allowed"));
        assertEquals("linux", launchedSettings.get("platform.os"));
        assertEquals("x86_64", launchedSettings.get("platform.arch"));
        assertFalse(launchedSettings.containsKey("setup-file"));
    }

    @Test
    void homeAndSetupFileArePassedAbsolute(@org.junit.jupiter.api.io.TempDir Path dir) {
        Path setup = dir.resolve("setup.yml");
        run(FakeSystemAccess.linuxUser(), "--home", dir.toString(), "--setup-file", setup.toString());
        assertEquals(dir.toAbsolutePath().normalize(), launchedHome);
        assertEquals(setup.toAbsolutePath().normalize().toString(), launchedSettings.get("setup-file"));
    }

    @Test
    void unknownStateStartsWithNotice() {
        int code = run(new FakeSystemAccess());
        assertEquals(Launcher.EXIT_OK, code);
        assertNotNull(launchedSettings, "node must still start");
        String err = err();
        assertTrue(err.startsWith("****"), err);
        assertTrue(err.contains("WARNING: Could not determine whether VectoraCloud is running with"), err);
        assertTrue(err.contains("stop it now and start it"), err);
        assertTrue(err.contains("Startup continues."), err);
        assertEquals("false", launchedSettings.get("root-allowed"));
    }

    @Test
    void unknownStateWarningInGerman() {
        assertEquals(Launcher.EXIT_OK, run(new FakeSystemAccess(), "--lang", "de"));
        String err = err();
        assertTrue(err.startsWith("****"), err);
        assertTrue(err.contains("WARNUNG: Ob VectoraCloud mit Administrator- bzw. root-Rechten läuft"), err);
        assertTrue(err.contains("beendet es"), err);
        assertTrue(err.contains("Der Start wird fortgesetzt."), err);
    }

    @Test
    void unknownArgumentShowsErrorAndHelp() {
        int code = run(FakeSystemAccess.linuxUser(), "--frobnicate");
        assertEquals(Launcher.EXIT_USAGE, code);
        assertTrue(err().contains("Unknown argument: --frobnicate"), err());
        assertTrue(err().contains("Usage: java -jar VectoraCloud.jar"), err());
        assertNull(launchedSettings);
    }

    @Test
    void helpPrintsUsageAndDoesNotStartNode() {
        assertEquals(Launcher.EXIT_OK, run(FakeSystemAccess.linuxUser(), "--help", "--lang", "de"));
        assertTrue(out().contains("Aufruf: java -jar VectoraCloud.jar"), out());
        assertNull(launchedSettings);
    }

    @Test
    void versionPrintsVersion() {
        assertEquals(Launcher.EXIT_OK, run(FakeSystemAccess.linuxUser(), "--version"));
        assertTrue(out().startsWith("VectoraCloud dev (Java 21.0.5)"), out());
        assertNull(launchedSettings);
    }

    @Test
    void libraryErrorExitsWith4() {
        int code = run(FakeSystemAccess.linuxUser(), (home, settings) -> {
            throw new LibraryException(LibraryException.Kind.HASH_MISMATCH, "netty.jar", "x");
        });
        assertEquals(Launcher.EXIT_LIBRARIES, code);
        assertTrue(err().contains("SHA-256"), err());
    }

    @Test
    void missingNodeExitsWith5() {
        int code = run(FakeSystemAccess.linuxUser(), (home, settings) -> {
            throw new NodeStartException(NodeStartException.Kind.MISSING, "missing", null);
        });
        assertEquals(Launcher.EXIT_NO_NODE, code);
        assertTrue(err().contains(NodeStarter.NODE_MAIN_CLASS), err());
    }

    @Test
    void unexpectedRuntimeExceptionFromNodeExitsWith1() {
        int code = run(FakeSystemAccess.linuxUser(), (home, settings) -> {
            throw new IllegalStateException("kaputt");
        });
        assertEquals(Launcher.EXIT_NODE_FAILED, code);
        assertTrue(err().contains("The node failed to start"), err());
        assertTrue(err().contains("kaputt"), err());
    }

    @Test
    void failingNodeExitsWith1() {
        int code = run(FakeSystemAccess.linuxUser(), (home, settings) -> {
            throw new NodeStartException(NodeStartException.Kind.FAILED, "failed", new IllegalStateException("boom"));
        });
        assertEquals(Launcher.EXIT_NODE_FAILED, code);
        assertTrue(err().contains("boom"), err());
    }
}
