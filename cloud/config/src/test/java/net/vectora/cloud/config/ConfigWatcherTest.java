package net.vectora.cloud.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.io.AtomicFileWriter;
import net.vectora.cloud.config.io.ConfigYaml;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.operation.SetLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Hand edits while the cloud runs. macOS polls for changes, hence the generous timeouts. */
@Timeout(90)
class ConfigWatcherTest {

    private static final long WAIT_SECONDS = 30;

    @TempDir
    Path root;

    @Test
    void invalidHandEditKeepsThePreviousConfigurationAndIsReported() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            Recorder recorder = new Recorder();
            service.addListener(recorder);
            service.startWatching();
            ConfigSnapshot before = service.snapshot();

            Path cloud = root.resolve("config/cloud.yml");
            String text = Files.readString(cloud);
            new AtomicFileWriter().write(cloud, text.replace("language: en", "langauge: de").getBytes(StandardCharsets.UTF_8));

            ConfigException error = recorder.awaitFailure(e -> e.errors().stream()
                    .anyMatch(err -> err.code() == ErrorCode.UNKNOWN_FIELD && err.path().equals("langauge")));
            assertEquals("cloud.yml", error.errors().getFirst().file());
            assertEquals(before, service.snapshot(), "the previous configuration stays active");

            new AtomicFileWriter().write(cloud, text.replace("language: en", "language: de").getBytes(StandardCharsets.UTF_8));
            ConfigChanged change = recorder.awaitChange(c -> c.current().cloud().language() == Language.DE);
            assertEquals(ConfigChanged.Origin.FILE, change.origin());
            assertEquals(Language.DE, service.snapshot().cloud().language());
        }
    }

    @Test
    void newGroupFileIsPickedUp() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            Recorder recorder = new Recorder();
            service.addListener(recorder);
            service.startWatching();

            Files.write(root.resolve("config/groups/lobby.yml"), ConfigYaml.write(Samples.lobbyGroup()));
            ConfigChanged change = recorder.awaitChange(c -> c.current().groups().containsKey("lobby"));
            assertTrue(change.files().contains("groups/lobby.yml"));
        }
    }

    @Test
    void ownWritesDoNotComeBackAsFileEvents() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            Recorder recorder = new Recorder();
            service.addListener(recorder);
            service.startWatching();
            service.apply(new SetLanguage(Language.DE));
            assertEquals(ConfigChanged.Origin.OPERATION, recorder.changes.take().origin());
            ConfigChanged echo = recorder.changes.poll(2, TimeUnit.SECONDS);
            assertEquals(null, echo, "the watcher sees the file equal to memory and stays quiet");
        }
    }

    @Test
    void deletedGroupsFolderIsWatchedAgainAfterItsKeyBecameInvalid() throws Exception {
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            Recorder recorder = new Recorder();
            service.addListener(recorder);
            service.startWatching();

            // Deleting the folder invalidates its watch key (key.reset() returns false).
            Files.delete(root.resolve("config/groups"));
            awaitDirectory(root.resolve("config/groups"));

            Files.write(root.resolve("config/groups/lobby.yml"), ConfigYaml.write(Samples.lobbyGroup()));
            ConfigChanged change = recorder.awaitChange(c -> c.current().groups().containsKey("lobby"));
            assertTrue(change.files().contains("groups/lobby.yml"), "changes in the new folder are seen again");
        }
    }

    @Test
    void folderThatCannotBeWatchedAgainIsReported() throws Exception {
        // groups/ replaced by a regular file: the folder can neither be created nor watched again.
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            Recorder recorder = new Recorder();
            service.addListener(recorder);
            service.startWatching();

            replaceFolderWithFile(root.resolve("config/groups"));

            ConfigException error = recorder.awaitFailure(e -> e.errors().stream()
                    .anyMatch(err -> err.code() == ErrorCode.FILE_ACCESS && err.file().equals("groups/")));
            assertNotNull(error);
        }
    }

    /**
     * Deletes {@code dir} and puts a file in its place. The watcher races to create the folder
     * again (fast on Linux); if it wins, the write fails and the folder is deleted once more, which
     * invalidates the new key as well. As soon as the file is in place, the pending re-watch fails.
     */
    private static void replaceFolderWithFile(Path dir) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (true) {
            try {
                if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(dir);
                }
                Files.writeString(dir, "not a folder", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return;
            } catch (FileSystemException e) {
                assertTrue(System.nanoTime() < deadline, "could not replace " + dir + " with a file: " + e);
                Thread.sleep(10);
            }
        }
    }

    private static void awaitDirectory(Path dir) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!Files.isDirectory(dir)) {
            assertTrue(System.nanoTime() < deadline, "the watcher did not create " + dir + " again");
            Thread.sleep(50);
        }
    }

    private static final class Recorder implements ConfigListener {
        final BlockingQueue<ConfigChanged> changes = new LinkedBlockingQueue<>();
        final BlockingQueue<ConfigException> failures = new LinkedBlockingQueue<>();

        @Override
        public void configChanged(ConfigChanged change) {
            changes.add(change);
        }

        @Override
        public void reloadFailed(ConfigException error) {
            failures.add(error);
        }

        ConfigChanged awaitChange(Predicate<ConfigChanged> wanted) throws InterruptedException {
            return await(changes, wanted);
        }

        ConfigException awaitFailure(Predicate<ConfigException> wanted) throws InterruptedException {
            return await(failures, wanted);
        }

        private static <T> T await(BlockingQueue<T> queue, Predicate<T> wanted) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (System.nanoTime() < deadline) {
                T next = queue.poll(500, TimeUnit.MILLISECONDS);
                if (next != null && wanted.test(next)) {
                    return next;
                }
            }
            assertNotNull(null, "expected event did not arrive within " + WAIT_SECONDS + " s");
            return null;
        }
    }
}
