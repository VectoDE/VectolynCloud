package net.vectolyn.cloud.console.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Set;
import net.vectolyn.cloud.console.terminal.TerminalSelector.Mode;
import net.vectolyn.cloud.console.terminal.TerminalSelector.Selection;
import org.jline.terminal.spi.SystemStream;
import org.jline.terminal.spi.TerminalProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class TerminalSelectorTest {

    private static final Set<String> BOTH = Set.of("ffm", "jni");

    @Test
    void withoutTerminalItIsAlwaysPlain() {
        for (String wish : new String[] {null, "auto", "ffm", "jni", "dumb"}) {
            Selection s = TerminalSelector.choose(25, wish, BOTH, false);
            assertEquals(Mode.PLAIN, s.mode(), String.valueOf(wish));
            assertTrue(s.reason().contains("not a terminal"));
        }
        assertEquals(Mode.PLAIN, TerminalSelector.choose(25, "PLAIN ", BOTH, true).mode(), "plain can be forced");
    }

    @Test
    void automaticChoiceFollowsTheJavaVersion() {
        assertEquals("ffm", TerminalSelector.choose(25, null, BOTH, true).provider());
        assertEquals("ffm", TerminalSelector.choose(22, null, BOTH, true).provider());
        assertEquals("jni", TerminalSelector.choose(21, null, BOTH, true).provider(), "Java 21 never takes ffm");
        assertEquals("jni", TerminalSelector.choose(25, null, Set.of("jni"), true).provider());
        assertEquals("dumb", TerminalSelector.choose(21, null, Set.of(), true).provider());
        assertTrue(TerminalSelector.choose(21, null, Set.of(), true).reason().contains("no native terminal provider"));
    }

    @Test
    void overridesFallBackWithAReason() {
        Selection ffmOn21 = TerminalSelector.choose(21, "ffm", BOTH, true);
        assertEquals("jni", ffmOn21.provider());
        assertTrue(ffmOn21.reason().contains("ffm needs Java 22+, running 21"), ffmOn21.reason());
        assertEquals("jni", TerminalSelector.choose(25, "jni", BOTH, true).provider());
        assertEquals("dumb", TerminalSelector.choose(25, "dumb", BOTH, true).provider());
        Selection jniMissing = TerminalSelector.choose(25, "jni", Set.of("ffm"), true);
        assertEquals("dumb", jniMissing.provider());
        assertTrue(jniMissing.reason().contains("jni is not available here"));
        Selection typo = TerminalSelector.choose(25, "fmm", BOTH, true);
        assertEquals("ffm", typo.provider());
        assertTrue(typo.reason().contains("must be auto, ffm, jni, dumb or plain"));
    }

    @Test
    void selectionChecksItsParts() {
        assertThrows(IllegalArgumentException.class, () -> new Selection(Mode.JLINE, null, "x"));
        assertThrows(IllegalArgumentException.class, () -> new Selection(Mode.PLAIN, "jni", "x"));
    }

    @Test
    void detectInATestRunNeverPicksFfmBelowJava22() {
        // Tests run without a terminal on stdin, so detect() must answer PLAIN here; on a Java 21
        // runtime it must not even consider ffm.
        Selection detected = TerminalSelector.detect();
        assertEquals(Mode.PLAIN, detected.mode(), detected.reason());
        if (Runtime.version().feature() < TerminalSelector.FFM_MIN_JAVA) {
            assertNotEquals("ffm", detected.provider());
        }
    }

    @Test
    void jniProviderLoadsOnEveryRuntime() throws Exception {
        // The Java 21 path: jline-terminal-jni ships native code for Windows, Linux and macOS.
        TerminalProvider jni = TerminalProvider.load("jni");
        assertEquals("jni", jni.name());
        jni.isSystemStream(SystemStream.Output); // loads the native helper
    }

    @Test
    void ffmProviderCannotLoadOnJava21() {
        // Counter-check for the version gate: on Java 21 the ffm classes do not even load.
        assumeTrue(Runtime.version().feature() < TerminalSelector.FFM_MIN_JAVA, "only meaningful below Java 22");
        assertThrows(Throwable.class, () -> TerminalProvider.load("ffm").isSystemStream(SystemStream.Output));
    }

    @Test
    @Tag("requires-java25")
    void ffmProviderLoadsOnJava25() throws Exception {
        TerminalProvider ffm = TerminalProvider.load("ffm");
        assertEquals("ffm", ffm.name());
        ffm.isSystemStream(SystemStream.Output);
    }
}
