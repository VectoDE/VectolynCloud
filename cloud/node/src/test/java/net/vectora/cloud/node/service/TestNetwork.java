package net.vectora.cloud.node.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.model.CombatSystem;
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
import net.vectora.cloud.config.operation.PutGroup;
import net.vectora.cloud.config.operation.PutTask;
import net.vectora.cloud.config.operation.SetServerWanted;
import net.vectora.cloud.config.secret.SecretGenerator;
import net.vectora.cloud.config.secret.SecretStore;
import net.vectora.cloud.node.fake.FakeServerJar;

/**
 * A complete cloud home in a temporary folder: configuration, generated secrets, small templates
 * with real placeholders, and the fake server jar. Never touches the real {@code Server-Network/}.
 */
final class TestNetwork implements AutoCloseable {

    final Path home;
    final ConfigService config;
    final SecretStore secrets;
    final Path fakeJar;

    private TestNetwork(Path home, ConfigService config, SecretStore secrets, Path fakeJar) {
        this.home = home;
        this.config = config;
        this.secrets = secrets;
        this.fakeJar = fakeJar;
    }

    static TestNetwork create(Path dir) throws Exception {
        Path home = dir.resolve("home");
        Files.createDirectories(home);
        ConfigService config = ConfigService.open(CloudHome.of(home));
        SecretStore secrets = new SecretStore(CloudHome.of(home));
        secrets.ensureGenerated(new SecretGenerator());
        Path jar = FakeServerJar.write(dir.resolve("fake-server.jar"));
        TestNetwork network = new TestNetwork(home, config, secrets, jar);
        network.backendTemplate(ServerKind.LOBBY.templateFolder());
        network.backendTemplate(ServerKind.SURVIVAL.templateFolder());
        network.backendTemplate(ServerKind.MINIGAME.templateFolder());
        network.file("Proxy/template/velocity.toml", """
                bind = "{{VECTORA_PROXY_BIND}}:{{VECTORA_PROXY_PORT}}"
                [servers]
                {{VECTORA_PROXY_SERVERS}}
                try = [ {{VECTORA_PROXY_TRY}} ]
                """);
        config.apply(List.of(new AcceptEula(), new CompleteSetup()));
        return network;
    }

    private void backendTemplate(String folder) throws IOException {
        file(folder + "/template/server.properties", """
                server-ip={{VECTORA_SERVER_HOST}}
                server-port={{VECTORA_SERVER_PORT}}
                management-server-secret={{VECTORA_MANAGEMENT_SECRET}}
                """);
    }

    void file(String relative, String content) throws IOException {
        Path file = home.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    void group(String name, ServerKind kind, ServerSoftware software, ProcessConfig process) throws Exception {
        GroupConfig group = new GroupConfig(GroupConfig.SCHEMA_VERSION, kind.templateFolder(), software, "26.3",
                JavaSelection.AUTO, new HeapConfig(MemorySize.parse("16M"), MemorySize.parse("64M")), List.of(),
                CombatSystem.MODERN, process);
        config.apply(new PutGroup(name, group));
    }

    /** A task; {@code STATIC} makes the {@code min} services static, {@code DYNAMIC} none. */
    void task(String name, String group, TaskMode mode, int min, int max) throws Exception {
        task(name, group, mode == TaskMode.STATIC ? min : 0, min, max);
    }

    /** A task with {@code staticServices} static services. */
    void task(String name, String group, int staticServices, int min, int max) throws Exception {
        config.apply(new PutTask(name, new TaskConfig(TaskConfig.SCHEMA_VERSION, group,
                new ScalingConfig(staticServices, min, max, 50, 80, 50, TimeSpan.parse("5m"), TimeSpan.parse("60s")),
                List.of())));
    }

    void unwanted(ServerKind kind) throws Exception {
        config.apply(new SetServerWanted(kind, false));
    }

    static ProcessConfig process(String mode, String stopTimeout, RestartConfig restart) {
        return new ProcessConfig(List.of("-Dfake.mode=" + mode, "-Dfake.echo=server.properties",
                "-XX:TieredStopAtLevel=1"), null, TimeSpan.parse(stopTimeout), restart);
    }

    static RestartConfig noRestart() {
        return new RestartConfig(false, 1, TimeSpan.parse("0s"), TimeSpan.parse("0s"));
    }

    /** Copies the fake jar into the service folder under the name the software expects. */
    ServerSoftwareProvider fakeSoftware() {
        return (spec, folder) -> {
            String name = ServerSoftwareProvider.jarName(spec.group().software());
            Files.copy(fakeJar, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            return name;
        };
    }

    ServiceManager.Builder manager() {
        return ServiceManager.builder(home, config::snapshot, secrets::load)
                .serverSoftware(fakeSoftware())
                .destroyGrace(Duration.ofSeconds(3));
    }

    static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    @Override
    public void close() throws IOException {
        config.close();
    }
}
