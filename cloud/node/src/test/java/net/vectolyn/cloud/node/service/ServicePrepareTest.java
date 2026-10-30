package net.vectolyn.cloud.node.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.vectolyn.cloud.config.model.ServerKind;
import net.vectolyn.cloud.config.model.ServerSoftware;
import net.vectolyn.cloud.config.model.TaskMode;
import net.vectolyn.protocol.ServiceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** What happens before a service's process exists. */
class ServicePrepareTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @TempDir
    Path dir;

    @Test
    void afterCloseNoStartReachesTheStartGate() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            net.group("lobby", ServerKind.LOBBY, ServerSoftware.PAPER, TestNetwork.process("normal", "20s", null));
            net.task("lobby", "lobby", 1, 1, 1);
            java.util.concurrent.atomic.AtomicInteger gate = new java.util.concurrent.atomic.AtomicInteger();
            ServiceManager manager = net.manager().startGate(gate::incrementAndGet).build();
            manager.close();
            ServiceException refused = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                    () -> manager.start("lobby"));
            assertTrue(refused.getMessage().contains("stopping"), refused.getMessage());
            assertTrue(manager.startConfigured().isEmpty());
            assertEquals(0, gate.get(), "the database is not set up while the cloud stops");
        }
    }

    @Test
    void aHeldServiceDoesNotStartUntilTheHoldIsReleased() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            net.group("lobby", ServerKind.LOBBY, ServerSoftware.PAPER, TestNetwork.process("normal", "20s", null));
            net.task("lobby", "lobby", 1, 1, 1);
            try (ServiceManager manager = net.manager().build()) {
                ServiceId lobby = new ServiceId("lobby-1");
                ServiceManager.Hold hold = manager.holdStopped(lobby);
                ServiceException refused = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                        () -> manager.start("lobby"));
                assertTrue(refused.getMessage().contains("backup"), refused.getMessage());
                // One hold at a time: a second backup or restore of the same service waits its turn.
                org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class, () -> manager.holdStopped(lobby));
                hold.close();
                hold.close();

                // Counter-check: released, it starts; and a running service cannot be held.
                manager.start("lobby");
                TestNetwork.await("lobby-1 running", WAIT, () -> manager.find(lobby)
                        .map(info -> info.state() == ServiceState.RUNNING).orElse(false));
                ServiceException running = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                        () -> manager.holdStopped(lobby));
                assertTrue(running.getMessage().contains("running"), running.getMessage());
            }
        }
    }

    @Test
    void aStopWhilePreparedEndsAsStoppedAndNoProcessStarts() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            net.group("lobby", ServerKind.LOBBY, ServerSoftware.PAPER, TestNetwork.process("normal", "20s", null));
            net.task("lobby", "lobby", 1, 1, 1);
            CountDownLatch providing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ServerSoftwareProvider fake = net.fakeSoftware();
            ServerSoftwareProvider blocking = (spec, folder) -> {
                providing.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return fake.provide(spec, folder);
            };
            try (ServiceManager manager = net.manager().serverSoftware(blocking).build()) {
                ServiceId id = new ServiceId("lobby-1");
                // start() prepares and launches on the calling thread, so it runs in its own.
                java.util.concurrent.CompletableFuture<Object> starting = java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> {
                            try {
                                return manager.start("lobby");
                            } catch (ServiceException e) {
                                return e;
                            }
                        });
                try {
                    assertTrue(providing.await(30, TimeUnit.SECONDS), "the launch reached the download step");
                    assertEquals(ServiceState.PREPARED, manager.find(id).orElseThrow().state());

                    ServiceInfo stopped = manager.stop(id);
                    assertEquals(ServiceState.STOPPED, stopped.state(), "stop() in PREPARED reports STOPPED");
                } finally {
                    release.countDown();
                }
                starting.get(30, TimeUnit.SECONDS);
                ServiceInfo after = manager.find(id).orElseThrow();
                assertEquals(ServiceState.STOPPED, after.state(), "not CRASHED, not started after all");
                assertTrue(after.pid().isEmpty(), "no process");
                assertFalse(Files.exists(after.workDirectory().resolve("token.txt")), "the server never ran");
            }
        }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "symbolic links need extra rights on Windows")
    void aLinkedTempFolderIsNeitherFilledNorDeleted() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            net.group("minigame", ServerKind.MINIGAME, ServerSoftware.PAPER, TestNetwork.process("normal", "20s", null));
            net.task("minigame", "minigame", TaskMode.DYNAMIC, 0, 1);
            Path outside = dir.resolve("outside");
            Files.createDirectories(outside.resolve("minigame-1"));
            Path precious = Files.writeString(outside.resolve("minigame-1").resolve("precious.txt"), "keep");
            Files.createDirectories(net.home.resolve("temp"));
            Files.createSymbolicLink(net.home.resolve(ServicePlanner.DYNAMIC_DIR), outside);
            try (ServiceManager manager = net.manager().build()) {
                ServiceException refused = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                        () -> manager.start("minigame"));
                assertTrue(refused.getMessage().contains("is a link"), refused.getMessage());
                ServiceId id = new ServiceId("minigame-1");
                assertTrue(manager.find(id).map(s -> !s.state().hasProcess()).orElse(true), "no process");
                assertTrue(Files.exists(precious), "nothing outside the home is deleted");
                try (java.util.stream.Stream<Path> files = Files.list(outside.resolve("minigame-1"))) {
                    assertEquals(List.of(precious), files.toList(), "nothing is rendered outside the home");
                }
            }
        }
    }
}
