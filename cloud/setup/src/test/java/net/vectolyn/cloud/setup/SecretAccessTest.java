package net.vectolyn.cloud.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.config.secret.SecretGenerator;
import net.vectolyn.cloud.config.secret.SecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SecretAccessTest {

    private static final String GERMAN = String.join("\r\n",
            "\"Jeder\",\"Bekannte Gruppe\",\"S-1-1-0\",\"Verbindliche Gruppe, Aktivierte Gruppe\"",
            "\"VORDEFINIERT\\Administratoren\",\"Alias\",\"S-1-5-32-544\",\"Nur für Verweigerungen verwendete Gruppe\"",
            "\"VORDEFINIERT\\Benutzer\",\"Alias\",\"S-1-5-32-545\",\"Verbindliche Gruppe\"");
    private static final String FALLBACK = "BUILTIN\\Administrators (S-1-5-32-544)";

    @Test
    void localizedNameComesWithTheSid() {
        assertEquals("VORDEFINIERT\\Administratoren (S-1-5-32-544)", SecretAccess.administratorsGroup(Optional.of(GERMAN)));
    }

    @Test
    void fallbackWhenTheGroupIsMissingOrOdd() {
        assertEquals(FALLBACK, SecretAccess.administratorsGroup(Optional.empty()));
        assertEquals(FALLBACK, SecretAccess.administratorsGroup(Optional.of("\"Jeder\",\"Bekannte Gruppe\",\"S-1-1-0\",\"x\"")));
        assertEquals(FALLBACK, SecretAccess.administratorsGroup(Optional.of("\"Evil\u001b[2J\",\"Alias\",\"S-1-5-32-544\",\"x\"")),
                "control characters never reach the console");
        assertEquals(FALLBACK, SecretAccess.administratorsGroup(Optional.of("garbage")));
    }

    @Test
    void csvHandlesQuotesAndCommas() {
        assertEquals(List.of("a,b", "c\"d", "", "e"), SecretAccess.csv("\"a,b\",\"c\"\"d\",,e"));
    }

    @Test
    void missingFileIsDescribedAsOpen(@TempDir Path root) {
        assertSame(SetupMessages.ACCESS_OPEN, SecretAccess.describe(root.resolve("absent"), true, Optional::empty));
    }

    @Test
    void restrictedFileNamesOwnerAndAdministrators(@TempDir Path root) throws Exception {
        SecretStore store = new SecretStore(CloudHome.of(root));
        store.ensureGenerated(new SecretGenerator());
        String windows = SecretAccess.describe(store.file(), true, () -> Optional.of(GERMAN)).get(Language.DE);
        String posix = SecretAccess.describe(store.file(), false, Optional::empty).get(Language.EN);
        String open = SetupMessages.ACCESS_OPEN.get(Language.DE);
        assertTrue(windows.contains("VORDEFINIERT\\Administratoren (S-1-5-32-544)") || windows.equals(open), windows);
        assertTrue(posix.contains("root") || posix.contains("Administrators") || posix.equals(SetupMessages.ACCESS_OPEN.get(Language.EN)),
                posix);
    }

    @Test
    @Timeout(20)
    void aSilentProcessIsKilledAtTheDeadline() {
        HangingProcess process = new HangingProcess();
        long start = System.nanoTime();
        assertTrue(SecretAccess.output(process, 300).isEmpty());
        assertTrue(process.destroyed.getCount() == 0, "the process was killed");
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10), "the timeout held while the output stayed open");
    }

    /** A process that writes nothing and keeps its output open until it is killed. */
    private static final class HangingProcess extends Process {
        private final CountDownLatch destroyed = new CountDownLatch(1);

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    try {
                        destroyed.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return -1;
                }
            };
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            destroyed.await();
            return 1;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return destroyed.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (destroyed.getCount() > 0) {
                throw new IllegalThreadStateException("running");
            }
            return 1;
        }

        @Override
        public void destroy() {
            destroyed.countDown();
        }

        @Override
        public Process destroyForcibly() {
            destroyed.countDown();
            return this;
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsTheRealGroupListIsUsed() {
        String group = SecretAccess.administratorsGroup(SecretAccess.whoamiGroups());
        assertTrue(group.endsWith("(S-1-5-32-544)"), group);
    }
}
