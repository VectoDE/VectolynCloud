package net.vectolyn.cloud.node.jre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import net.vectolyn.cloud.config.model.CombatSystem;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.HeapConfig;
import net.vectolyn.cloud.config.model.JavaSelection;
import net.vectolyn.cloud.config.model.MemorySize;
import net.vectolyn.cloud.config.model.ServerSoftware;
import net.vectolyn.cloud.download.jre.JavaRequirement;
import net.vectolyn.cloud.download.jre.JavaRuntime;
import net.vectolyn.cloud.download.jre.JreException;
import net.vectolyn.cloud.node.service.JavaRuntimeResolver;
import org.junit.jupiter.api.Test;

class ManagedJavaRuntimesTest {

    /** Provides a runtime of exactly the chosen version, like JreManager does. */
    private static final ManagedJavaRuntimes.Provider CHOOSING = requirement -> {
        int feature = requirement.choose().feature();
        return new JavaRuntime(Path.of("jre", "java-" + feature), Path.of("jre", "java-" + feature, "bin", "java"),
                feature, feature + ".0.1", JavaRuntime.Origin.MANAGED);
    };

    private static GroupConfig group(ServerSoftware software, JavaSelection java) {
        return new GroupConfig(GroupConfig.SCHEMA_VERSION, "Lobby-Server", software, "26.3", java,
                new HeapConfig(MemorySize.parse("16M"), MemorySize.parse("64M")), List.of(), CombatSystem.MODERN, null);
    }

    private static int feature(ManagedJavaRuntimes runtimes, GroupConfig group) throws IOException {
        JavaRuntimeResolver.JavaRuntime runtime = runtimes.resolve("g", group);
        return runtime.featureVersion();
    }

    @Test
    void pandaSpigotGetsJava21AndEverythingElseJava25() throws Exception {
        ManagedJavaRuntimes runtimes = new ManagedJavaRuntimes(CHOOSING, ManagedJavaRuntimes.Minimums.NONE);
        assertEquals(JavaRequirement.LEGACY_FEATURE, feature(runtimes, group(ServerSoftware.PANDASPIGOT, JavaSelection.AUTO)));
        for (ServerSoftware software : List.of(ServerSoftware.PAPER, ServerSoftware.VELOCITY)) {
            assertEquals(JavaRequirement.DEFAULT_FEATURE, feature(runtimes, group(software, JavaSelection.AUTO)),
                    software.name());
        }
        JavaRuntimeResolver.JavaRuntime runtime = runtimes.resolve("g", group(ServerSoftware.PAPER, JavaSelection.AUTO));
        assertEquals(Path.of("jre", "java-25", "bin", "java"), runtime.executable());
    }

    @Test
    void theGroupOverridesUnlessItIsBelowTheMinimum() throws Exception {
        ManagedJavaRuntimes none = new ManagedJavaRuntimes(CHOOSING, ManagedJavaRuntimes.Minimums.NONE);
        assertEquals(21, feature(none, group(ServerSoftware.PAPER, JavaSelection.of(21))));
        assertEquals(25, feature(none, group(ServerSoftware.PANDASPIGOT, JavaSelection.of(25))));

        ManagedJavaRuntimes minimum22 = new ManagedJavaRuntimes(CHOOSING,
                (name, group) -> new OptionalInt[] {OptionalInt.of(17), OptionalInt.empty(), OptionalInt.of(22)});
        assertEquals(22, feature(minimum22, group(ServerSoftware.PANDASPIGOT, JavaSelection.AUTO)),
                "the highest minimum beats the legacy default");
        JreException tooLow = assertThrows(JreException.class,
                () -> minimum22.resolve("g", group(ServerSoftware.PAPER, JavaSelection.of(21))));
        assertTrue(tooLow.getMessage().contains("needs Java 22"), tooLow.getMessage());
    }

    @Test
    void anInterruptBecomesAnInterruptedIoException() {
        ManagedJavaRuntimes runtimes = new ManagedJavaRuntimes(requirement -> {
            throw new InterruptedException("test");
        }, ManagedJavaRuntimes.Minimums.NONE);
        try {
            assertThrows(InterruptedIOException.class, () -> runtimes.resolve("g", group(ServerSoftware.PAPER,
                    JavaSelection.AUTO)));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is kept");
        } finally {
            Thread.interrupted();
        }
    }
}
