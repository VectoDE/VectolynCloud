package net.vectora.cloud.node;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.model.CombatSystem;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.HeapConfig;
import net.vectora.cloud.config.model.JavaSelection;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.ProcessConfig;
import net.vectora.cloud.config.model.RestartConfig;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.config.operation.AcceptEula;
import net.vectora.cloud.config.operation.CompleteSetup;
import net.vectora.cloud.config.operation.ConfigOperation;
import net.vectora.cloud.config.operation.PutGroup;
import net.vectora.cloud.config.operation.PutTask;
import net.vectora.cloud.database.DatabaseEndpoint;
import net.vectora.cloud.database.DatabaseProvisioner;
import net.vectora.cloud.database.HealthReport;
import net.vectora.cloud.database.StorageMethod;
import net.vectora.cloud.node.database.NodeDatabase;
import net.vectora.cloud.node.fake.FakeServerJar;
import net.vectora.cloud.node.service.JavaRuntimeResolver;
import net.vectora.cloud.node.service.ServerSoftwareProvider;

/**
 * A cloud home in a temporary folder for whole-node tests: configuration with groups and tasks,
 * the fake server jar (with the cloud client), a recording database and node options that never
 * download anything or bind a fixed channel port. Never touches the real {@code Server-Network/}.
 */
final class NodeFixture {

    static final Duration WAIT = Duration.ofSeconds(60);

    final Path home;
    final Path fakeJar;
    final RecordingDatabase database = new RecordingDatabase();

    private NodeFixture(Path home, Path fakeJar) {
        this.home = home;
        this.fakeJar = fakeJar;
    }

    /**
     * Creates the home with one lobby group whose fake server talks to the cloud channel.
     *
     * @param dir temporary folder
     * @param setupDone whether EULA and setup are done
     * @param extra more operations, applied after the lobby
     * @return the fixture
     */
    static NodeFixture create(Path dir, boolean setupDone, List<ConfigOperation> extra) throws Exception {
        Path home = dir.resolve("home");
        Files.createDirectories(home);
        NodeFixture fixture = new NodeFixture(home, FakeServerJar.writeWithCloudClient(dir.resolve("fake-server.jar")));
        for (ServerKind kind : List.of(ServerKind.LOBBY, ServerKind.SURVIVAL, ServerKind.MINIGAME)) {
            fixture.file(kind.templateFolder() + "/template/server.properties", """
                    server-port={{VECTORA_SERVER_PORT}}
                    db-pool={{VECTORA_DB_POOL_SIZE}}
                    """);
        }
        try (ConfigService config = ConfigService.open(CloudHome.of(home))) {
            List<ConfigOperation> operations = new ArrayList<>();
            operations.add(new PutGroup("lobby", group(ServerKind.LOBBY.templateFolder(), ServerSoftware.PAPER,
                    "-Dfake.cloud=true")));
            operations.add(task("lobby", "lobby", TaskMode.STATIC, 1, 1));
            operations.addAll(extra);
            if (setupDone) {
                operations.add(new AcceptEula());
                operations.add(new CompleteSetup());
            }
            config.apply(operations);
        }
        return fixture;
    }

    static GroupConfig group(String template, ServerSoftware software, String... flags) {
        List<String> jvmFlags = new ArrayList<>(List.of(flags));
        jvmFlags.add("-XX:TieredStopAtLevel=1");
        ProcessConfig process = new ProcessConfig(jvmFlags, null, TimeSpan.parse("20s"),
                new RestartConfig(false, 1, TimeSpan.parse("0s"), TimeSpan.parse("0s")));
        return new GroupConfig(GroupConfig.SCHEMA_VERSION, template, software, "26.3", JavaSelection.AUTO,
                new HeapConfig(MemorySize.parse("16M"), MemorySize.parse("64M")), List.of(), CombatSystem.MODERN,
                process);
    }

    static PutTask task(String name, String group, TaskMode mode, int min, int max) {
        return new PutTask(name, new TaskConfig(TaskConfig.SCHEMA_VERSION, group, mode,
                new ScalingConfig(min, max, 50, 80, TimeSpan.parse("5m"), TimeSpan.parse("60s")), List.of()));
    }

    void file(String relative, String content) throws IOException {
        Path file = home.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    NodeSettings settings() {
        return NodeSettings.parse(Map.of("home", home.toAbsolutePath().toString()));
    }

    /** The settings with more launcher entries, for example {@code setup-file} or {@code non-interactive}. */
    NodeSettings settings(Map<String, String> extra) {
        Map<String, String> all = new java.util.HashMap<>(extra);
        all.put("home", home.toAbsolutePath().toString());
        return NodeSettings.parse(all);
    }

    /**
     * The fixture of a home that {@link #create} prepared, for another process.
     *
     * @param home the home
     * @param fakeJar the fake server jar
     * @return the fixture
     */
    static NodeFixture existing(Path home, Path fakeJar) {
        return new NodeFixture(home, fakeJar);
    }

    /** Options of a test node: no console, channel on a free port, fake software, this JVM's Java. */
    Consumer<CloudNode.Options> options() {
        return withConsole().andThen(CloudNode.Options::noConsole);
    }

    /** Like {@link #options()}, with the console the node chooses itself (plain without a terminal). */
    Consumer<CloudNode.Options> withConsole() {
        return options -> {
            options.network(network -> network.withAddress("127.0.0.1", 0))
                    .database(database)
                    // No Pterodactyl or Docker detection from the machine the tests run on.
                    .environment(Map.of());
            options.services()
                    .javaRuntimes(JavaRuntimeResolver.current())
                    .serverSoftware(fakeSoftware())
                    .destroyGrace(Duration.ofSeconds(3));
        };
    }

    ServerSoftwareProvider fakeSoftware() {
        return (spec, folder) -> {
            String name = ServerSoftwareProvider.jarName(spec.group().software());
            Files.copy(fakeJar, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            return name;
        };
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** A database in H2 mode that counts how often the node sets it up and stops it. */
    static final class RecordingDatabase implements DatabaseProvisioner, NodeDatabase.Factory {

        final AtomicInteger provisions = new AtomicInteger();
        final AtomicInteger shutdowns = new AtomicInteger();
        /** The service core, set by a test that checks the order; the database records what it sees. */
        volatile java.util.function.Supplier<List<net.vectora.cloud.node.service.ServiceInfo>> services;
        /** Services with a process when the database was set up, and when it was stopped. */
        final List<String> runningAtProvision = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> runningAtShutdown = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** Makes {@link #shutdown()} throw, to check that the node's close goes on. */
        volatile boolean failShutdown;
        /** Every service the node knew when the database was stopped. */
        final List<String> knownAtShutdown = new java.util.concurrent.CopyOnWriteArrayList<>();

        private void record(List<String> running, List<String> known) {
            java.util.function.Supplier<List<net.vectora.cloud.node.service.ServiceInfo>> source = services;
            if (source == null) {
                return;
            }
            for (net.vectora.cloud.node.service.ServiceInfo info : source.get()) {
                if (known != null) {
                    known.add(info.id().value());
                }
                if (info.state().hasProcess()) {
                    running.add(info.id() + " " + info.state());
                }
            }
        }

        @Override
        public DatabaseProvisioner create(net.vectora.cloud.config.model.ConfigSnapshot snapshot,
                net.vectora.cloud.database.DatabaseSecrets secrets) {
            return this;
        }

        @Override
        public DatabaseMode mode() {
            return DatabaseMode.H2;
        }

        @Override
        public DatabaseEndpoint provision() {
            provisions.incrementAndGet();
            record(runningAtProvision, null);
            return new DatabaseEndpoint(StorageMethod.H2, "127.0.0.1", 3306, "vectora", "vectora", Optional.empty());
        }

        @Override
        public HealthReport health() {
            return new HealthReport(true, Duration.ZERO, "test");
        }

        @Override
        public Optional<String> notice() {
            return Optional.of("test database notice");
        }

        @Override
        public void shutdown() {
            record(runningAtShutdown, knownAtShutdown);
            shutdowns.incrementAndGet();
            if (failShutdown) {
                // An Error: NodeDatabase catches runtime exceptions itself, so only this reaches close().
                throw new LinkageError("test: the database does not stop");
            }
        }
    }
}
