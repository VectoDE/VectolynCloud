package net.vectora.cloud.node.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.vectora.cloud.config.model.RestartConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.config.secret.EnvFile;
import net.vectora.cloud.node.state.ServiceRecord;
import net.vectora.cloud.node.state.ServiceStateStore;
import net.vectora.cloud.node.state.ServiceStates;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.AuthResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the service core against real processes: the fake server jar, started with the JVM the tests
 * run on. CI runs this on Linux and Windows (job "Build") and on Java 21 ("Tests on Java 21").
 */
@Timeout(120)
class ServiceLifecycleIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path dir;

    /** Records every state change, per service, in order. */
    static final class Recorder implements ServiceListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void stateChanged(ServiceInfo service, ServiceState previous) {
            events.add(service.id() + " " + service.state());
        }

        @Override
        public void output(ServiceId service, String line) {
            lines.add(service + " " + line);
        }

        List<String> of(String id) {
            synchronized (events) {
                return events.stream().filter(e -> e.startsWith(id + " ")).map(e -> e.substring(id.length() + 1)).toList();
            }
        }
    }

    @Test
    void wantedServicesRunStopCleanlyAndUnwantedOnesAreNeitherCreatedNorReferenced() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            net.group("lobby", ServerKind.LOBBY, ServerSoftware.PAPER, TestNetwork.process("normal", "20s", null));
            net.task("lobby", "lobby", TaskMode.STATIC, 1, 2);
            net.group("survival", ServerKind.SURVIVAL, ServerSoftware.PAPER, TestNetwork.process("normal", "20s", null));
            net.task("survival", "survival", TaskMode.STATIC, 1, 1);
            net.group("minigame", ServerKind.MINIGAME, ServerSoftware.PAPER, TestNetwork.process("normal", "20s", null));
            net.task("minigame", "minigame", TaskMode.DYNAMIC, 1, 3);
            net.group("proxy", ServerKind.PROXY, ServerSoftware.VELOCITY, TestNetwork.process("normal", "20s", null));
            net.task("proxy", "proxy", TaskMode.STATIC, 1, 1);
            net.unwanted(ServerKind.SURVIVAL);
            Recorder recorder = new Recorder();

            try (ServiceManager manager = net.manager().build()) {
                manager.addListener(recorder);
                manager.startConfigured();
                ServiceId lobby = new ServiceId("lobby-1");
                ServiceId minigame = new ServiceId("minigame-1");
                ServiceId proxy = new ServiceId("proxy-1");
                for (ServiceId id : List.of(lobby, minigame, proxy)) {
                    TestNetwork.await(id + " running", WAIT, () -> state(manager, id) == ServiceState.RUNNING);
                }
                // Listeners run on their own thread, a moment after the state has changed.
                TestNetwork.await("running event", WAIT, () -> recorder.of("lobby-1").contains("RUNNING"));
                assertEquals(List.of("prepared", "starting", "running"),
                        recorder.of("lobby-1").stream().map(s -> s.toLowerCase(java.util.Locale.ROOT)).toList());

                // Unwanted: no service, no folder, not in the proxy list.
                assertTrue(manager.find(new ServiceId("survival-1")).isEmpty());
                assertFalse(Files.exists(net.home.resolve("Survival-Server/runtime")), "unwanted server was rendered");
                String velocity = Files.readString(net.home.resolve("Proxy/runtime/velocity.toml"));
                int lobbyPort = manager.find(lobby).orElseThrow().port();
                assertTrue(velocity.contains("lobby = \"127.0.0.1:" + lobbyPort + "\""), velocity);
                assertTrue(velocity.contains("try = [ \"lobby\" ]"), velocity);
                assertFalse(velocity.contains("survival"), velocity);
                assertFalse(velocity.contains("minigame"), "dynamic services register through the bridge: " + velocity);
                assertTrue(Files.exists(net.home.resolve("Proxy/runtime/forwarding.secret")));

                // Folders: static in <template>/runtime, dynamic in temp/services/<id>.
                Path lobbyDir = net.home.resolve("Lobby-Server/runtime");
                Path minigameDir = net.home.resolve("temp/services/minigame-1");
                assertTrue(Files.isRegularFile(lobbyDir.resolve("world/level.dat")));
                assertTrue(Files.isRegularFile(minigameDir.resolve("world/level.dat")));
                assertEquals("eula=true", Files.readAllLines(lobbyDir.resolve("eula.txt")).getLast());
                String properties = Files.readString(lobbyDir.resolve("server.properties"));
                assertTrue(properties.contains("server-port=" + lobbyPort), properties);

                // Ports do not collide and come from the service range; the proxy has its fixed port.
                List<Integer> ports = manager.services().stream().map(ServiceInfo::port).toList();
                assertEquals(ports.size(), ports.stream().distinct().count(), "ports collide: " + ports);
                assertEquals(25565, manager.find(proxy).orElseThrow().port());
                assertTrue(lobbyPort >= 30000 && lobbyPort <= 30999);

                // The state file names the processes, but holds no token.
                ServiceStates state = new ServiceStateStore(net.home).load().orElseThrow();
                Map<String, ServiceRecord> records = state.services().stream()
                        .collect(Collectors.toMap(ServiceRecord::id, r -> r));
                assertEquals(manager.find(lobby).orElseThrow().pid().getAsLong(), records.get("lobby-1").pid());
                assertEquals("Lobby-Server/runtime", records.get("lobby-1").workDirectory());
                assertEquals("temp/services/minigame-1", records.get("minigame-1").workDirectory());
                String token = Files.readString(lobbyDir.resolve("token.txt"));
                assertFalse(Files.readString(new ServiceStateStore(net.home).file()).contains(token));

                // Token: right one matches, a wrong one does not, other services' tokens do not.
                assertEquals(AuthResult.Outcome.ACCEPTED, auth(manager, lobby, token));
                assertEquals(AuthResult.Outcome.INVALID_TOKEN, auth(manager, lobby, token + "x"));
                assertEquals(AuthResult.Outcome.INVALID_TOKEN, auth(manager, minigame, token));
                assertEquals(AuthResult.Outcome.UNKNOWN_PEER, auth(manager, new ServiceId("lobby-9"), token));

                // Console commands reach the process; line breaks are refused.
                manager.sendCommand(lobby, "say hello");
                TestNetwork.await("echo", WAIT, () -> recorder.lines.contains("lobby-1 > say hello"));
                assertThrows(ServiceException.class, () -> manager.sendCommand(lobby, "say a\nstop"));
                assertThrows(ServiceException.class, () -> manager.sendCommand(lobby, ""));

                // Log: the token and every secret of vectora.env are masked.
                Path log = net.home.resolve("logs/services/lobby-1.log");
                TestNetwork.await("log", WAIT, () -> read(log).contains("Done ("));
                String text = read(log);
                assertTrue(text.contains("[fake] token ***"), text);
                assertFalse(text.contains(token), "token leaked into the log");
                EnvFile env = net.secrets.load();
                String management = env.get("VECTORA_MANAGEMENT_SECRET").orElseThrow().reveal();
                assertTrue(text.contains("management-server-secret=***"), text);
                assertFalse(text.contains(management), "secret leaked into the log");

                // Limits: max-services, unwanted group, unknown task.
                manager.start("lobby");
                assertThrows(ServiceException.class, () -> manager.start("lobby"), "max-services 2");
                assertThrows(ServiceException.class, () -> manager.start("survival"));
                assertThrows(ServiceException.class, () -> manager.start("nope"));
                ServiceId lobby2 = new ServiceId("lobby-2");
                TestNetwork.await("lobby-2 running", WAIT, () -> state(manager, lobby2) == ServiceState.RUNNING);
                assertTrue(Files.isDirectory(net.home.resolve("Lobby-Server/runtime-2")));

                // Clean stop through stdin: static keeps its folder and world, dynamic is deleted.
                ServiceInfo stopped = manager.stop(lobby);
                assertEquals(ServiceState.STOPPED, stopped.state());
                assertEquals(0, stopped.exitCode().getAsInt());
                assertTrue(Files.exists(lobbyDir.resolve("stopped-cleanly")), "stop command did not arrive");
                assertTrue(Files.isRegularFile(lobbyDir.resolve("world/level.dat")), "static world must stay");
                assertEquals(AuthResult.Outcome.UNKNOWN_PEER, auth(manager, lobby, token),
                        "token must die with the process");
                TestNetwork.await("stopped event", WAIT, () -> recorder.of("lobby-1").contains("STOPPED"));
                assertEquals(List.of("prepared", "starting", "running", "stopping", "stopped"),
                        recorder.of("lobby-1").stream().map(s -> s.toLowerCase(java.util.Locale.ROOT)).toList());

                manager.stop(minigame);
                TestNetwork.await("dynamic folder deleted", WAIT, () -> !Files.exists(minigameDir));
                assertTrue(manager.find(minigame).isEmpty(), "a stopped dynamic service is forgotten");

                // A static service starts again in the same folder, with its world and a new token.
                ServiceInfo again = manager.start("lobby");
                assertEquals(lobby, again.id());
                TestNetwork.await("lobby-1 running again", WAIT, () -> state(manager, lobby) == ServiceState.RUNNING);
                assertEquals("fake world of lobby-1", Files.readString(lobbyDir.resolve("world/level.dat")));
                assertNotEquals(token, Files.readString(lobbyDir.resolve("token.txt")));
                assertTrue(Files.exists(net.home.resolve("logs/services/lobby-1.1.log")), "previous log is kept");
            }
            assertTrue(recorder.of("lobby-1").getLast().equalsIgnoreCase("stopped"), "close() stops everything");
            assertTrue(recorder.of("proxy-1").getLast().equalsIgnoreCase("stopped"));
            assertTrue(Files.exists(net.home.resolve("Proxy/runtime/stopped-cleanly")),
                    "Velocity gets \"shutdown\" on stdin");
            ServiceStates after = new ServiceStateStore(net.home).load().orElseThrow();
            assertTrue(after.services().stream().allMatch(r -> r.pid() == 0 && !r.state().hasProcess()), after.toString());
        }
    }

    @Test
    void aCrashedServiceIsRestartedWithBackoffUntilTheLimit() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            RestartConfig twice = new RestartConfig(true, 2, TimeSpan.parse("0s"), TimeSpan.parse("1s"));
            net.group("lobby", ServerKind.LOBBY, ServerSoftware.PAPER, TestNetwork.process("crash", "5s", twice));
            net.task("lobby", "lobby", TaskMode.STATIC, 1, 1);
            Recorder recorder = new Recorder();
            try (ServiceManager manager = net.manager().build()) {
                manager.addListener(recorder);
                manager.startConfigured();
                ServiceId lobby = new ServiceId("lobby-1");
                TestNetwork.await("three crashes", WAIT, () -> recorder.of("lobby-1").stream()
                        .filter(s -> s.equals("CRASHED")).count() == 3);
                Thread.sleep(1500);
                ServiceInfo info = manager.find(lobby).orElseThrow();
                assertEquals(ServiceState.CRASHED, info.state());
                assertEquals(3, info.exitCode().getAsInt());
                assertEquals(2, info.restartAttempts());
                assertEquals(3, recorder.of("lobby-1").stream().filter(s -> s.equals("STARTING")).count(),
                        "one start plus two restarts, then it gives up");
            }
        }
    }

    @Test
    void aCrashRestartPassesTheStartGateAndARefusalKeepsItCrashed() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            RestartConfig once = new RestartConfig(true, 3, TimeSpan.parse("0s"), TimeSpan.parse("1s"));
            net.group("lobby", ServerKind.LOBBY, ServerSoftware.PAPER, TestNetwork.process("crash", "5s", once));
            net.task("lobby", "lobby", TaskMode.STATIC, 1, 1);
            java.util.concurrent.atomic.AtomicInteger gate = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean refuse = new java.util.concurrent.atomic.AtomicBoolean();
            Recorder recorder = new Recorder();
            try (ServiceManager manager = net.manager().startGate(() -> {
                gate.incrementAndGet();
                if (refuse.get()) {
                    throw new ServiceException("the database is not reachable");
                }
            }).build()) {
                manager.addListener(recorder);
                manager.startConfigured();
                TestNetwork.await("a restart", WAIT, () -> recorder.of("lobby-1").stream()
                        .filter(s -> s.equals("STARTING")).count() == 2);
                assertTrue(gate.get() >= 2, "the restart after the crash asked the gate too: " + gate.get());
                // The database goes away: the next restart is refused and the service stays CRASHED.
                refuse.set(true);
                int before = gate.get();
                TestNetwork.await("the gate refused", WAIT, () -> gate.get() > before
                        && manager.find(new ServiceId("lobby-1")).orElseThrow().state() == ServiceState.CRASHED);
                Thread.sleep(2500);
                long starts = recorder.of("lobby-1").stream().filter(s -> s.equals("STARTING")).count();
                int asked = gate.get();
                Thread.sleep(2500);
                assertEquals(asked, gate.get(), "a refused restart is not tried again");
                assertEquals(starts, recorder.of("lobby-1").stream().filter(s -> s.equals("STARTING")).count());
                assertEquals(ServiceState.CRASHED, manager.find(new ServiceId("lobby-1")).orElseThrow().state());
            }
        }
    }

    @Test
    void aStartWaitingAtTheGateIsRefusedOnceTheEulaIsTakenBack() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            net.group("lobby", ServerKind.LOBBY, ServerSoftware.PAPER, TestNetwork.process("normal", "20s", null));
            net.task("lobby", "lobby", TaskMode.STATIC, 0, 1);
            java.util.concurrent.Semaphore entered = new java.util.concurrent.Semaphore(0);
            java.util.concurrent.Semaphore release = new java.util.concurrent.Semaphore(0);
            Recorder recorder = new Recorder();
            try (ServiceManager manager = net.manager().startGate(() -> {
                // Like the database's gate while a managed MariaDB comes up.
                entered.release();
                release.acquireUninterruptibly();
            }).build()) {
                manager.addListener(recorder);
                java.util.concurrent.CompletableFuture<ServiceInfo> start = java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> {
                            try {
                                return manager.start("lobby");
                            } catch (ServiceException e) {
                                throw new java.util.concurrent.CompletionException(e);
                            }
                        });
                assertTrue(entered.tryAcquire(30, java.util.concurrent.TimeUnit.SECONDS), "the start reached the gate");
                // The operator takes the EULA back while the start still waits.
                net.config.apply(current -> current.withCloud(
                        current.cloud().withSetupComplete(false).withEulaAccepted(false)));
                release.release();
                java.util.concurrent.ExecutionException refused = assertThrows(
                        java.util.concurrent.ExecutionException.class, () -> start.get(30, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(refused.getCause().getMessage().contains("eula-accepted"), refused.getCause().getMessage());
                Thread.sleep(500);
                assertFalse(recorder.of("lobby-1").contains("STARTING"), "no process without the EULA: "
                        + recorder.of("lobby-1"));

                // Counter-check: with the EULA accepted again the same waiting start goes through.
                net.config.apply(current -> current.withCloud(
                        current.cloud().withEulaAccepted(true).withSetupComplete(true)));
                java.util.concurrent.CompletableFuture<ServiceInfo> again = java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> {
                            try {
                                return manager.start("lobby");
                            } catch (ServiceException e) {
                                throw new java.util.concurrent.CompletionException(e);
                            }
                        });
                assertTrue(entered.tryAcquire(30, java.util.concurrent.TimeUnit.SECONDS));
                release.release();
                again.get(30, java.util.concurrent.TimeUnit.SECONDS);
                TestNetwork.await("lobby-1 running", WAIT, () -> manager.find(new ServiceId("lobby-1"))
                        .map(info -> info.state() == ServiceState.RUNNING).orElse(false));
            }
        }
    }

    @Test
    void aCrashRestartIsRefusedOnceTheEulaIsTakenBack() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            RestartConfig later = new RestartConfig(true, 3, TimeSpan.parse("2s"), TimeSpan.parse("2s"));
            net.group("lobby", ServerKind.LOBBY, ServerSoftware.PAPER, TestNetwork.process("crash", "5s", later));
            net.task("lobby", "lobby", TaskMode.STATIC, 1, 1);
            Recorder recorder = new Recorder();
            try (ServiceManager manager = net.manager().build()) {
                manager.addListener(recorder);
                manager.startConfigured();
                TestNetwork.await("first crash", WAIT, () -> recorder.of("lobby-1").contains("CRASHED"));
                // A hand edit of cloud.yml while the restart waits for its back-off (setup-complete
                // needs the EULA, so both go back).
                net.config.apply(current -> current.withCloud(
                        current.cloud().withSetupComplete(false).withEulaAccepted(false)));
                Thread.sleep(3500);
                assertEquals(1, recorder.of("lobby-1").stream().filter(s -> s.equals("STARTING")).count(),
                        "no restart without eula-accepted: " + recorder.of("lobby-1"));
                assertEquals(ServiceState.CRASHED, manager.find(new ServiceId("lobby-1")).orElseThrow().state());
            }
        }
    }

    @Test
    void aCrashWithRestartOffStaysCrashedAndADynamicOneIsCleanedUp() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            net.group("minigame", ServerKind.MINIGAME, ServerSoftware.PAPER,
                    TestNetwork.process("crash", "5s", TestNetwork.noRestart()));
            net.task("minigame", "minigame", TaskMode.DYNAMIC, 1, 1);
            Recorder recorder = new Recorder();
            try (ServiceManager manager = net.manager().build()) {
                manager.addListener(recorder);
                manager.startConfigured();
                TestNetwork.await("crash", WAIT, () -> recorder.of("minigame-1").contains("CRASHED"));
                TestNetwork.await("cleanup", WAIT, () -> !Files.exists(net.home.resolve("temp/services/minigame-1")));
                Thread.sleep(500);
                assertEquals(1, recorder.of("minigame-1").stream().filter(s -> s.equals("STARTING")).count());
            }
        }
    }

    @Test
    void aDynamicIdIsNotReusedWhileItsOldFolderIsStillBeingDeleted() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            net.group("minigame", ServerKind.MINIGAME, ServerSoftware.PAPER, TestNetwork.process("normal", "20s", null));
            net.task("minigame", "minigame", TaskMode.DYNAMIC, 1, 3);
            try (ServiceManager manager = net.manager().build()) {
                manager.startConfigured();
                ServiceId first = new ServiceId("minigame-1");
                TestNetwork.await("minigame-1 running", WAIT, () -> state(manager, first) == ServiceState.RUNNING);
                Path oldDir = net.home.resolve("temp/services/minigame-1");
                ServiceInfo next;
                // On Windows an open FileInputStream blocks the deletion, so the cleanup of the old
                // folder is still retrying when the next service is started.
                java.io.InputStream held = new java.io.FileInputStream(oldDir.resolve("server.properties").toFile());
                try {
                    manager.stop(first);
                    next = manager.start("minigame");
                    ServiceId id = next.id();
                    TestNetwork.await(id + " running", WAIT, () -> state(manager, id) == ServiceState.RUNNING);
                } finally {
                    held.close();
                }
                Path newDir = next.workDirectory();
                if (!newDir.equals(oldDir)) {
                    TestNetwork.await("old folder deleted", WAIT, () -> !Files.exists(oldDir));
                }
                assertEquals(ServiceState.RUNNING, state(manager, next.id()));
                assertTrue(Files.isRegularFile(newDir.resolve("world/level.dat")), "the new folder was damaged");
                assertTrue(Files.isRegularFile(newDir.resolve("server.properties")), "the new folder was damaged");
            }
        }
    }

    @Test
    void aServerThatIgnoresStopIsEndedAfterTheTimeout() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            net.group("lobby", ServerKind.LOBBY, ServerSoftware.PAPER, TestNetwork.process("ignore-stop", "1s", null));
            net.task("lobby", "lobby", TaskMode.STATIC, 1, 1);
            try (ServiceManager manager = net.manager().build()) {
                manager.startConfigured();
                ServiceId lobby = new ServiceId("lobby-1");
                TestNetwork.await("running", WAIT, () -> state(manager, lobby) == ServiceState.RUNNING);
                long pid = manager.find(lobby).orElseThrow().pid().getAsLong();
                long started = System.nanoTime();
                ServiceInfo info = manager.stop(lobby);
                assertEquals(ServiceState.STOPPED, info.state(), "a requested stop is never a crash");
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
                assertFalse(Files.exists(net.home.resolve("Lobby-Server/runtime/stopped-cleanly")));
                assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) >= 0,
                        "the stop timeout must be waited for first");
            }
        }
    }

    @Test
    void aServerThatIgnoresTerminationIsKilled() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            net.group("lobby", ServerKind.LOBBY, ServerSoftware.PAPER, TestNetwork.process("ignore-term", "1s", null));
            net.task("lobby", "lobby", TaskMode.STATIC, 1, 1);
            try (ServiceManager manager = net.manager().build()) {
                manager.startConfigured();
                ServiceId lobby = new ServiceId("lobby-1");
                TestNetwork.await("running", WAIT, () -> state(manager, lobby) == ServiceState.RUNNING);
                long pid = manager.find(lobby).orElseThrow().pid().getAsLong();
                assertEquals(ServiceState.STOPPED, manager.stop(lobby).state());
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            }
        }
    }

    @Test
    void orphansFromThePreviousRunAreEndedButForeignProcessesAreLeftAlone() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            net.group("lobby", ServerKind.LOBBY, ServerSoftware.PAPER, TestNetwork.process("normal", "2s", null));
            net.task("lobby", "lobby", TaskMode.STATIC, 1, 1);
            net.group("minigame", ServerKind.MINIGAME, ServerSoftware.PAPER, TestNetwork.process("normal", "2s", null));
            net.task("minigame", "minigame", TaskMode.DYNAMIC, 0, 1);
            Path orphanDir = net.home.resolve("temp/services/minigame-1");
            Files.createDirectories(orphanDir);
            Process orphan = startDirectly(net, orphanDir);
            Path foreignDir = Files.createDirectories(dir.resolve("foreign"));
            Process foreign = startDirectly(net, foreignDir);
            Path leftover = Files.createDirectories(net.home.resolve("temp/services/minigame-7/world"));
            try {
                long orphanStart = orphan.toHandle().info().startInstant().orElseThrow().toEpochMilli();
                long foreignStart = foreign.toHandle().info().startInstant().orElseThrow().toEpochMilli();
                new ServiceStateStore(net.home).save(new ServiceStates(ServiceStates.SCHEMA_VERSION, "node-1", List.of(
                        new ServiceRecord("minigame-1", "minigame", "minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING,
                                orphan.pid(), orphanStart, 30001, "temp/services/minigame-1"),
                        // Same pid as a live process, but another start time: the pid was reused.
                        new ServiceRecord("lobby-1", "lobby", "lobby", 1, TaskMode.STATIC, ServiceState.RUNNING,
                                foreign.pid(), foreignStart - 60_000, 30000, "Lobby-Server/runtime"))));

                try (ServiceManager manager = net.manager().build()) {
                    ServiceManager.Recovery recovery = manager.recoverOrphans();
                    assertEquals(List.of(new ServiceId("minigame-1")), recovery.terminated());
                    assertEquals(List.of(), recovery.unverifiable());
                    assertTrue(orphan.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "orphan still runs");
                    assertTrue(foreign.isAlive(), "a foreign process with a reused pid must not be touched");
                    assertFalse(Files.exists(orphanDir), "orphan's dynamic folder is cleaned up");
                    assertFalse(Files.exists(leftover.getParent()), "leftover dynamic folders are cleaned up");
                    assertEquals(List.of(), new ServiceStateStore(net.home).load().orElseThrow().services());
                }
            } finally {
                orphan.destroyForcibly();
                foreign.destroyForcibly();
                orphan.waitFor();
                foreign.waitFor();
            }
        }
    }

    @Test
    void aBrokenStateFileIsMovedAsideAndTheNodeStillStarts() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            Path file = new ServiceStateStore(net.home).file();
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"schema-version\": 1, \"node\": \"node-1\", \"services\": [ {\"id\": \"../x\"} ] }");
            try (ServiceManager manager = net.manager().build()) {
                ServiceManager.Recovery recovery = manager.recoverOrphans();
                assertEquals(List.of(), recovery.terminated());
            }
            try (var files = Files.list(file.getParent())) {
                assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("services.json.broken-")));
            }
        }
    }

    private static Process startDirectly(TestNetwork net, Path folder) throws Exception {
        Files.writeString(folder.resolve("server.properties"), "x=y\n");
        Path java = JavaRuntimeResolver.JavaRuntime.current().executable();
        Process process = new ProcessBuilder(java.toString(), "-Dfake.echo=server.properties", "-jar",
                net.fakeJar.toString()).directory(folder.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        TestNetwork.await("direct process", WAIT, () -> Files.exists(folder.resolve("token.txt")));
        return process;
    }

    private static ServiceState state(ServiceManager manager, ServiceId id) {
        return manager.find(id).map(ServiceInfo::state).orElse(null);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static AuthResult.Outcome auth(ServiceManager manager, ServiceId id, String token) {
        return manager.authenticator().authenticate(id, new AuthToken(token));
    }
}
