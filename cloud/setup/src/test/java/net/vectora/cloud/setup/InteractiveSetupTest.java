package net.vectora.cloud.setup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.MemoryReserve;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.config.operation.ConfigOperation;
import net.vectora.cloud.config.operation.PutGroup;
import net.vectora.cloud.config.operation.PutNode;
import net.vectora.cloud.config.operation.PutTask;
import net.vectora.cloud.config.operation.SetGroupDrainTimeout;
import net.vectora.cloud.config.operation.SetGroupHeap;
import net.vectora.cloud.config.operation.SetTaskScaling;
import net.vectora.cloud.config.secret.EnvFile;
import net.vectora.cloud.config.secret.GeneratedSecret;
import net.vectora.cloud.config.secret.SecretStore;
import net.vectora.cloud.console.Console;
import net.vectora.cloud.console.terminal.ConsoleTerminal.LineKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The questions on the console: order, defaults, cancelling, saving and running again. */
@Timeout(60)
class InteractiveSetupTest {

    /** Questions of a first run with the defaults: up to the legacy proxy, the 1.8.8 server is skipped. */
    private static final int SERVER_QUESTIONS = 8;
    /** Service-count questions with the default servers: every wanted one except the proxy. */
    private static final int SERVICE_QUESTIONS = 6;
    /** The yes/no question for the advanced settings, right after the service counts. */
    private static final int ADVANCED_QUESTION = 1;
    /**
     * Memory questions with the default servers (all but the 1.8.8 part), followed directly by the
     * service-count questions and the advanced-settings question, so these blocks are passed together.
     */
    private static final int MEMORY_QUESTIONS = 7 + SERVICE_QUESTIONS + ADVANCED_QUESTION;
    /** Drain-timeout questions with the default servers: every wanted group. */
    private static final int DRAIN_QUESTIONS = 7;

    @TempDir
    Path root;

    private ConfigService config;
    private ScriptedTerminal terminal;
    private Console console;

    @BeforeEach
    void open() throws Exception {
        config = ConfigService.open(CloudHome.of(root));
        terminal = new ScriptedTerminal();
        console = Console.builder().terminal(terminal).build();
    }

    @AfterEach
    void close() throws IOException {
        config.close();
    }

    static SetupEnvironment env(boolean pterodactyl, boolean managed) {
        return new SetupEnvironment(Language.EN, true, Optional.empty(), false, pterodactyl, pterodactyl, managed,
                pterodactyl ? OptionalLong.of(4096) : OptionalLong.empty(),
                pterodactyl ? OptionalInt.of(25600) : OptionalInt.empty(), OptionalLong.empty());
    }

    /** Language, EULA and network name with defaults. */
    private ScriptedTerminal start() {
        return terminal.type("en", "y").enter(1);
    }

    /** Every question after the EULA with its default, then "save". */
    private ScriptedTerminal defaultsAndSave() {
        return terminal.enter(SERVER_QUESTIONS + 3 + MEMORY_QUESTIONS + 1 + 1);
    }

    @Test
    void firstRunAsksInOrderAndWritesOnlyAfterSaving() throws Exception {
        start();
        defaultsAndSave();

        SetupResult result = SetupWizard.run(console, config, env(false, true));

        assertEquals(SetupResult.Status.COMPLETED, result.status(), terminal.output());
        assertEquals(0, terminal.pending(), "every scripted answer was used");
        CloudConfig cloud = config.snapshot().cloud();
        assertTrue(cloud.eulaAccepted());
        assertTrue(cloud.setupComplete());
        assertEquals(Language.EN, cloud.language());
        assertEquals(DatabaseMode.MANAGED, cloud.database().mode());
        assertFalse(cloud.legacyProxy().enabled());
        assertFalse(cloud.servers().isWanted(ServerKind.LEGACY_SERVER));
        assertEquals(Set.of("proxy", "lobby", "survival", "creative", "mod", "end", "minigame"), config.snapshot().groups().keySet());
        assertEquals(new ServiceCounts(0, 1, 8), ServiceCounts.of(config.snapshot().tasks().get("minigame").scaling()));
        assertEquals(new ServiceCounts(1, 1, 4), ServiceCounts.of(config.snapshot().tasks().get("lobby").scaling()));
        assertEquals(new ServiceCounts(1, 1, 1), ServiceCounts.of(config.snapshot().tasks().get("survival").scaling()));
        assertEquals(new ServiceCounts(1, 1, 1), ServiceCounts.of(config.snapshot().tasks().get("proxy").scaling()));
        assertEquals(75, config.snapshot().tasks().get("lobby").scaling().scaleUpAt());
        assertEquals(50, config.snapshot().tasks().get("lobby").scaling().scaleDownBelow());
        assertEquals(MemorySize.parse("3G"), config.snapshot().groups().get("survival").heap().max());
        assertEquals(MemorySize.parse("512M"), config.snapshot().groups().get("survival").heap().min());
        assertTrue(result.files().contains("cloud.yml"));
        assertTrue(result.files().contains("secrets/vectora.env"));

        EnvFile secrets = new SecretStore(config.home()).load();
        for (GeneratedSecret kind : GeneratedSecret.values()) {
            assertTrue(secrets.hasValue(kind.key()), kind.key());
            assertFalse(terminal.output().contains(secrets.get(kind.key()).orElseThrow().reveal()), "a secret was printed");
        }
        assertTrue(terminal.reads().stream().allMatch(r -> r.kind() == LineKind.ANSWER), "answers never reach the history");

        List<String> prompts = terminal.reads().stream().map(ScriptedTerminal.Read::prompt).toList();
        assertTrue(prompts.get(0).startsWith("Language / Sprache"), prompts.get(0));
        assertTrue(prompts.get(1).startsWith("Do you accept the Minecraft EULA?"), prompts.get(1));
        assertTrue(prompts.get(2).startsWith("Network name"), prompts.get(2));
        assertTrue(prompts.get(3).startsWith("Proxy"), prompts.get(3));
        assertTrue(prompts.get(10).startsWith("Legacy proxy"), prompts.get(10));
        assertTrue(prompts.get(11).startsWith("Bind address"), prompts.get(11));
        assertTrue(prompts.get(prompts.size() - 1).startsWith("Save this?"));
        assertTrue(terminal.output().contains(SetupMessages.EULA_URL), "the EULA is linked");
        assertTrue(terminal.output().contains("Who can read the secrets"), terminal.output());
    }

    @Test
    void cancellingAtTheSummaryWritesNothing() throws Exception {
        byte[] before = Files.readAllBytes(config.home().cloudFile());
        start();
        terminal.enter(SERVER_QUESTIONS + 3 + MEMORY_QUESTIONS + 1).type("cancel");

        SetupResult result = SetupWizard.run(console, config, env(false, true));

        assertEquals(SetupResult.Status.CANCELLED, result.status());
        assertNothingWritten(before);
        assertTrue(terminal.output().contains("Setup cancelled. Nothing was saved."));
    }

    @Test
    void cancellingAnywhereWritesNothing() throws Exception {
        byte[] before = Files.readAllBytes(config.home().cloudFile());
        start();
        terminal.enter(4).type("!cancel");
        assertEquals(SetupResult.Status.CANCELLED, SetupWizard.run(console, config, env(false, true)).status());
        assertNothingWritten(before);

        terminal.type("de", "j").enter(2).interrupt();
        assertEquals(SetupResult.Status.CANCELLED, SetupWizard.run(console, config, env(false, true)).status());
        assertNothingWritten(before);
        assertTrue(terminal.output().contains("Einrichtung abgebrochen."), "the answer to the first question switches the language");
    }

    @Test
    void endOfInputPointsToTheSetupFile() throws Exception {
        byte[] before = Files.readAllBytes(config.home().cloudFile());
        terminal.type("en");
        assertEquals(SetupResult.Status.CANCELLED, SetupWizard.run(console, config, env(false, true)).status());
        assertNothingWritten(before);
        assertTrue(terminal.output().contains("--setup-file"), terminal.output());
    }

    @Test
    void declinedEulaStopsWithoutWriting() throws Exception {
        byte[] before = Files.readAllBytes(config.home().cloudFile());
        terminal.type("en", "n");
        assertEquals(SetupResult.Status.EULA_DECLINED, SetupWizard.run(console, config, env(false, true)).status());
        assertNothingWritten(before);
        assertTrue(terminal.output().contains("Without the EULA the cloud cannot start any server"));
        assertFalse(config.snapshot().cloud().eulaAccepted());
    }

    @Test
    void pterodactylHidesDockerAndPresetsTheAssignedPort() throws Exception {
        start();
        terminal.enter(SERVER_QUESTIONS);
        terminal.enter(3).enter(MEMORY_QUESTIONS).type("docker", "h2").enter(1);

        SetupResult result = SetupWizard.run(console, config, env(true, false));

        assertEquals(SetupResult.Status.COMPLETED, result.status(), terminal.output());
        assertEquals(25600, config.snapshot().cloud().proxy().port(), "the Pterodactyl port is the default");
        assertEquals(DatabaseMode.H2, config.snapshot().cloud().database().mode());
        assertTrue(terminal.output().contains("Docker is left out"));
        assertFalse(terminal.output().contains("(docker)"), "docker is no option");
        assertTrue(terminal.output().contains("On Pterodactyl every port needs an allocation"));
        assertTrue(terminal.output().contains("more than the 4G this Pterodactyl server may use"), terminal.output());
    }

    @Test
    void managedIsMarkedWhereMariaDbHasNoBuildAndH2IsTheDefault() throws Exception {
        start();
        defaultsAndSave();
        SetupWizard.run(console, config, env(false, false));
        assertTrue(terminal.output().contains("on this system it uses H2 instead"));
        assertEquals(DatabaseMode.H2, config.snapshot().cloud().database().mode());
    }

    @Test
    void externalDatabaseOnAMaskingConsoleTakesTheTypedPassword() throws Exception {
        start();
        terminal.enter(SERVER_QUESTIONS + 3 + MEMORY_QUESTIONS)
                .type("external", "db.example.org", "", "lp", "lpuser", "a-typed-password-1234").enter(1);

        SetupResult result = SetupWizard.run(console, config, env(false, true));

        assertEquals(SetupResult.Status.COMPLETED, result.status(), terminal.output());
        var db = config.snapshot().cloud().database();
        assertEquals(DatabaseMode.EXTERNAL, db.mode());
        assertEquals("db.example.org", db.host());
        assertEquals(SetupPlan.EXTERNAL_DATABASE_PORT, db.port());
        assertEquals("lp", db.name());
        assertEquals("lpuser", db.user());
        assertEquals("a-typed-password-1234",
                new SecretStore(config.home()).load().get("VECTORA_DB_PASSWORD").orElseThrow().reveal());
        assertFalse(terminal.output().contains("a-typed-password-1234"), "the password is never shown");
        assertEquals(LineKind.SECRET, terminal.reads().stream().filter(r -> r.prompt().startsWith("Password"))
                .findFirst().orElseThrow().kind());
        assertTrue(terminal.output().contains("database password: new (hidden)"));
    }

    @Test
    void externalDatabaseOnAVisibleConsoleOffersTheFileAndChecksIt() throws Exception {
        terminal.hidingSecrets(false);
        Path env = config.home().secretsFile();
        start();
        terminal.enter(SERVER_QUESTIONS + 3 + MEMORY_QUESTIONS)
                .type("external", "db.example.org", "", "", "")
                .type("file")
                .type("y")
                .then(() -> write(env, "VECTORA_DB_PASSWORD=written-by-the-operator\n"))
                .type("y")
                .enter(1);

        SetupResult result = SetupWizard.run(console, config, env(false, true));

        assertEquals(SetupResult.Status.COMPLETED, result.status(), terminal.output());
        assertTrue(terminal.output().contains(env.toString()), "the file is named");
        assertTrue(terminal.output().contains("The file has no VECTORA_DB_PASSWORD line with a value yet."));
        assertTrue(terminal.output().contains("Found the password in the file."));
        assertEquals("written-by-the-operator", new SecretStore(config.home()).load().get("VECTORA_DB_PASSWORD").orElseThrow().reveal(),
                "the operator's password stays");
        assertTrue(terminal.reads().stream().noneMatch(r -> r.kind() == LineKind.SECRET), "nothing secret was typed");
        assertFalse(terminal.output().contains("written-by-the-operator"));
    }

    @Test
    void legacyProxyBringsItsPortAndTheLegacyServerQuestion() throws Exception {
        start();
        terminal.enter(SERVER_QUESTIONS - 1).type("y", "y") // legacy proxy, then the 1.8.8 server
                .enter(3).type("25577") // bind, java, bedrock, legacy port
                .enter(MEMORY_QUESTIONS + 2 + 1).enter(1).enter(1); // two more heaps, one more count
        SetupResult result = SetupWizard.run(console, config, env(false, true));
        assertEquals(SetupResult.Status.COMPLETED, result.status(), terminal.output());
        assertEquals(new ServiceCounts(1, 1, 3), ServiceCounts.of(config.snapshot().tasks().get("legacy-server").scaling()));
        assertEquals(new ServiceCounts(1, 1, 1), ServiceCounts.of(config.snapshot().tasks().get("legacy-proxy").scaling()));
        assertTrue(config.snapshot().cloud().legacyProxy().enabled());
        assertEquals(25577, config.snapshot().cloud().legacyProxy().port());
        assertTrue(config.snapshot().cloud().servers().isWanted(ServerKind.LEGACY_SERVER));
        assertEquals("1.8.8", config.snapshot().groups().get("legacy-server").minecraftVersion());
        assertTrue(config.snapshot().groups().containsKey("legacy-proxy"));
    }

    @Test
    void serviceCountsAreAskedPerServerAndCheckedWhereTheyAreTyped() throws Exception {
        start();
        // Servers, network, memory with defaults; then the lobby: two invalid answers, then 1/2/6.
        terminal.enter(SERVER_QUESTIONS + 3 + 7)
                .type("2/1/4", "1-1-4", "1/2/6")
                .enter(SERVICE_QUESTIONS - 1 + ADVANCED_QUESTION + 1 + 1);
        SetupResult result = SetupWizard.run(console, config, env(false, true));
        assertEquals(SetupResult.Status.COMPLETED, result.status(), terminal.output());
        assertEquals(0, terminal.pending(), "every scripted answer was used");
        assertEquals(2, terminal.output().lines().filter(l -> l.startsWith("Write static/min/max")).count(),
                terminal.output());
        assertEquals(new ServiceCounts(1, 2, 6), ServiceCounts.of(config.snapshot().tasks().get("lobby").scaling()));
        assertTrue(terminal.output().contains("lobby 1/2/6"), "the summary shows the counts: " + terminal.output());
        // Counter-check: the other servers kept their suggestion.
        assertEquals(new ServiceCounts(0, 1, 8), ServiceCounts.of(config.snapshot().tasks().get("minigame").scaling()));
        assertEquals(new ServiceCounts(1, 1, 1), ServiceCounts.of(config.snapshot().tasks().get("end").scaling()));
    }

    @Test
    void advancedSettingsAreSkippedByDefault() throws Exception {
        start();
        defaultsAndSave();
        SetupResult result = SetupWizard.run(console, config, env(false, true));
        assertEquals(SetupResult.Status.COMPLETED, result.status(), terminal.output());
        List<String> prompts = terminal.reads().stream().map(ScriptedTerminal.Read::prompt).toList();
        assertTrue(prompts.stream().anyMatch(p -> p.startsWith("Advanced settings") && p.contains("[y/N]")),
                "asked once, default no: " + prompts);
        assertFalse(prompts.stream().anyMatch(p -> p.startsWith("Drain time") || p.startsWith("Memory node")), prompts.toString());
        var node = config.snapshot().nodes().nodes().getFirst();
        assertEquals(null, node.maxMemory());
        assertEquals(null, node.reserve());
        config.snapshot().groups().values().forEach(g -> assertEquals(null, g.drainTimeout()));
    }

    @Test
    void advancedSettingsAreCheckedWhereTheyAreTypedAndWritten() throws Exception {
        start();
        terminal.enter(SERVER_QUESTIONS + 3 + 7 + SERVICE_QUESTIONS).type("y")
                .type("4s", "11m", "45s").enter(DRAIN_QUESTIONS - 1) // proxy, then the other groups keep 30s
                .type("256M", "8G") // max-memory
                .type("65G", "2G").enter(2) // reserve: system, then node-overhead and database keep the default
                .enter(1 + 1); // database, save
        SetupResult result = SetupWizard.run(console, config, env(false, true));

        assertEquals(SetupResult.Status.COMPLETED, result.status(), terminal.output());
        assertEquals(0, terminal.pending(), "every scripted answer was used");
        assertEquals(2, terminal.output().lines().filter(l -> l.startsWith("Between 5s and 10m")).count(), terminal.output());
        assertEquals(1, terminal.output().lines().filter(l -> l.startsWith("At least 512M, written like 8G")).count());
        assertEquals(1, terminal.output().lines().filter(l -> l.startsWith("Between 1M and 64G")).count());
        assertEquals("45s", config.snapshot().groups().get("proxy").drainTimeout().toString());
        assertEquals(null, config.snapshot().groups().get("lobby").drainTimeout(), "Enter keeps the default, unwritten");
        var node = config.snapshot().nodes().nodes().getFirst();
        assertEquals(MemorySize.parse("8G"), node.maxMemory());
        assertEquals(new MemoryReserve(MemorySize.parse("2G"), null, null), node.reserve());
        assertTrue(result.files().contains("nodes.yml"), result.files().toString());

        // Running again: "default" and "auto" remove what was set; Enter keeps the rest.
        terminal.enter(1 + 1 + SERVER_QUESTIONS + 3 + 7 + SERVICE_QUESTIONS).type("y", "default").enter(DRAIN_QUESTIONS - 1)
                .type("auto", "default").enter(2).enter(1 + 1);
        SetupResult again = SetupWizard.run(console, config, env(false, true));
        assertEquals(SetupResult.Status.CHANGED, again.status(), terminal.output());
        assertEquals(Set.of("groups/proxy.yml", "nodes.yml"), again.files());
        assertEquals(null, config.snapshot().groups().get("proxy").drainTimeout());
        node = config.snapshot().nodes().nodes().getFirst();
        assertEquals(null, node.maxMemory());
        assertEquals(null, node.reserve());
    }

    @Test
    void changedServiceCountsOfAnExistingTaskAreWrittenOnRunningAgain() throws Exception {
        start();
        defaultsAndSave();
        SetupWizard.run(console, config, env(false, true));
        terminal.enter(1 + 1 + SERVER_QUESTIONS + 3 + 7 + 1).type("1/1/2").enter(SERVICE_QUESTIONS - 2 + ADVANCED_QUESTION + 1).enter(1);
        SetupResult again = SetupWizard.run(console, config, env(false, true));
        assertEquals(SetupResult.Status.CHANGED, again.status(), terminal.output());
        assertEquals(Set.of("tasks/survival.yml"), again.files());
        assertEquals(new ServiceCounts(1, 1, 2), ServiceCounts.of(config.snapshot().tasks().get("survival").scaling()));
        assertEquals(75, config.snapshot().tasks().get("survival").scaling().scaleUpAt(), "the rest of the scaling stays");
    }

    @Test
    void changesMadeWhileTheAssistantRunsSurviveWhereTheOperatorKeptTheShownValue() throws Exception {
        start();
        defaultsAndSave();
        SetupWizard.run(console, config, env(false, true));

        // language, name, servers, ports, heap: proxy keeps, lobby 2G, the other five keep
        terminal.enter(1 + 1 + SERVER_QUESTIONS + 3 + 1).type("2G").enter(7 - 2)
                .then(() -> changeInTheMeantime(List.of(
                        new SetGroupDrainTimeout("proxy", TimeSpan.parse("45s")),
                        new SetGroupHeap("survival", MemorySize.parse("512M"), MemorySize.parse("4G")),
                        new SetGroupHeap("lobby", MemorySize.parse("512M"), MemorySize.parse("1536M")),
                        new SetTaskScaling("lobby", config.snapshot().tasks().get("lobby").scaling().withCounts(1, 2, 6)),
                        new PutNode(config.snapshot().nodes().nodes().getFirst().withMaxMemory(MemorySize.parse("8G"))
                                .withMemoryReserve(new MemoryReserve(MemorySize.parse("2G"), null, null))))))
                .type("1/1/5").enter(SERVICE_QUESTIONS - 1) // lobby: only the maximum changed (shown 1/1/4)
                .type("y").enter(DRAIN_QUESTIONS).enter(1).enter(3) // advanced: every shown value kept
                .enter(1).enter(1); // database, save
        SetupResult again = SetupWizard.run(console, config, env(false, true));

        assertEquals(SetupResult.Status.CHANGED, again.status(), terminal.output());
        assertEquals(0, terminal.pending(), "every scripted answer was used");
        var groups = config.snapshot().groups();
        assertEquals("45s", groups.get("proxy").drainTimeout().toString(), "the parallel drain-timeout stays");
        assertEquals(MemorySize.parse("4G"), groups.get("survival").heap().max(), "the parallel heap stays");
        var node = config.snapshot().nodes().nodes().getFirst();
        assertEquals(MemorySize.parse("8G"), node.maxMemory(), "the parallel max-memory stays");
        assertEquals(new MemoryReserve(MemorySize.parse("2G"), null, null), node.reserve(), "the parallel reserve stays");
        // Counter-check: what the operator changed is written, count by count.
        assertEquals(MemorySize.parse("2G"), groups.get("lobby").heap().max(), "the typed heap wins");
        assertEquals(new ServiceCounts(1, 2, 5), ServiceCounts.of(config.snapshot().tasks().get("lobby").scaling()),
                "the typed maximum wins, the kept static and minimum take the parallel values");
        assertEquals(Set.of("groups/lobby.yml", "tasks/lobby.yml"), again.files());
    }

    @Test
    void removingWithDefaultOrAutoWinsOverAChangeInTheMeantime() throws Exception {
        start();
        terminal.enter(SERVER_QUESTIONS + 3 + 7 + SERVICE_QUESTIONS).type("y", "45s").enter(DRAIN_QUESTIONS - 1)
                .type("8G", "2G").enter(2).enter(1 + 1);
        assertEquals(SetupResult.Status.COMPLETED, SetupWizard.run(console, config, env(false, true)).status(),
                terminal.output());

        terminal.enter(1 + 1 + SERVER_QUESTIONS + 3 + 7 + SERVICE_QUESTIONS).type("y")
                .then(() -> changeInTheMeantime(List.of(
                        new SetGroupDrainTimeout("proxy", TimeSpan.parse("60s")),
                        new PutNode(config.snapshot().nodes().nodes().getFirst().withMaxMemory(MemorySize.parse("12G"))
                                .withMemoryReserve(new MemoryReserve(MemorySize.parse("3G"), null, null))))))
                .type("default").enter(DRAIN_QUESTIONS - 1).type("auto", "default").enter(2).enter(1 + 1);
        SetupResult again = SetupWizard.run(console, config, env(false, true));

        assertEquals(SetupResult.Status.CHANGED, again.status(), terminal.output());
        assertEquals(null, config.snapshot().groups().get("proxy").drainTimeout());
        var node = config.snapshot().nodes().nodes().getFirst();
        assertEquals(null, node.maxMemory());
        assertEquals(null, node.reserve());
    }

    private void changeInTheMeantime(List<ConfigOperation> ops) {
        try {
            config.apply(ops);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ConfigException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void reservedPortsAreRefusedWhereTheyAreTyped() throws Exception {
        start();
        terminal.enter(SERVER_QUESTIONS).enter(1).type("7010", "30001", "25570", "25570", "19140")
                .enter(MEMORY_QUESTIONS + 1 + 1);
        SetupResult result = SetupWizard.run(console, config, env(false, true));
        assertEquals(SetupResult.Status.COMPLETED, result.status(), terminal.output());
        assertEquals(25570, config.snapshot().cloud().proxy().port());
        assertEquals(19140, config.snapshot().cloud().proxy().bedrockPort());
        assertEquals(3, terminal.output().lines().filter(l -> l.startsWith("Choose a port that neither")).count(),
                terminal.output());
    }

    @Test
    void changeAnswersAsksAgainWithThePreviousAnswers() throws Exception {
        start();
        terminal.enter(SERVER_QUESTIONS + 3).type("2G").enter(MEMORY_QUESTIONS - 1 + 1).type("change");
        terminal.enter(2).enter(1 + SERVER_QUESTIONS + 3 + MEMORY_QUESTIONS + 1).enter(1);
        SetupResult result = SetupWizard.run(console, config, env(false, true));
        assertEquals(SetupResult.Status.COMPLETED, result.status(), terminal.output());
        assertEquals(MemorySize.parse("2G"), config.snapshot().groups().get("proxy").heap().max(), "the changed answer stayed");
    }

    @Test
    void runningAgainWithEveryDefaultWritesNothing() throws Exception {
        start();
        defaultsAndSave();
        SetupWizard.run(console, config, env(false, true));
        byte[] cloud = Files.readAllBytes(config.home().cloudFile());
        byte[] secrets = Files.readAllBytes(config.home().secretsFile());

        terminal.enter(1 + 1 + SERVER_QUESTIONS + 3 + MEMORY_QUESTIONS + 1); // language, name, ... database
        SetupResult again = SetupWizard.run(console, config, env(false, true));

        assertEquals(SetupResult.Status.UNCHANGED, again.status(), terminal.output());
        assertArrayEquals(cloud, Files.readAllBytes(config.home().cloudFile()));
        assertArrayEquals(secrets, Files.readAllBytes(config.home().secretsFile()));
        assertTrue(terminal.output().contains("Everything stays as it is"));
    }

    @Test
    void runningAgainWritesOnlyTheDifference() throws Exception {
        start();
        defaultsAndSave();
        SetupWizard.run(console, config, env(false, true));
        byte[] cloud = Files.readAllBytes(config.home().cloudFile());

        terminal.enter(1 + 1 + SERVER_QUESTIONS + 3 + 1).type("1536M").enter(MEMORY_QUESTIONS - 2 + 1).enter(1);
        SetupResult again = SetupWizard.run(console, config, env(false, true));

        assertEquals(SetupResult.Status.CHANGED, again.status(), terminal.output());
        assertEquals(Set.of("groups/lobby.yml"), again.files());
        assertArrayEquals(cloud, Files.readAllBytes(config.home().cloudFile()), "cloud.yml was left alone");
        assertTrue(terminal.output().contains("Files to write  groups/lobby.yml"), terminal.output());
    }

    @Test
    void severalMinigameGroupsGetTheirOwnSwitch() throws Exception {
        start();
        defaultsAndSave();
        SetupWizard.run(console, config, env(false, true));
        config.apply(List.of(new PutGroup("bedwars", SetupPlan.newGroup(NetworkServer.MINIGAME, MemorySize.parse("1G"))),
                new PutTask("bedwars", SetupPlan.newTask(NetworkServer.MINIGAME, "bedwars"))));

        // language, name, proxy..end (6), minigames, bedwars, minigame, legacy proxy
        terminal.enter(2 + 6 + 1).type("n").enter(2).enter(3).enter(MEMORY_QUESTIONS).enter(1).enter(1);
        SetupResult again = SetupWizard.run(console, config, env(false, true));

        assertEquals(SetupResult.Status.CHANGED, again.status(), terminal.output());
        assertFalse(config.snapshot().groups().get("bedwars").wantedOrDefault());
        assertTrue(config.snapshot().groups().get("minigame").wantedOrDefault());
        assertEquals(Set.of("groups/bedwars.yml"), again.files());
    }

    @Test
    void germanTextsAfterChoosingGerman() throws Exception {
        terminal.type("de", "j").enter(1 + SERVER_QUESTIONS + 3 + MEMORY_QUESTIONS + 1 + 1);
        SetupResult result = SetupWizard.run(console, config, env(false, true));
        assertEquals(SetupResult.Status.COMPLETED, result.status());
        assertEquals(Language.DE, config.snapshot().cloud().language());
        assertTrue(terminal.output().contains("Zusammenfassung"));
        assertTrue(terminal.output().contains("Die Einrichtung ist abgeschlossen."));
    }

    @Test
    void setupCommandRunsTheAssistantAgain() throws Exception {
        start();
        defaultsAndSave();
        SetupWizard.run(console, config, env(false, true));
        SetupWizard.registerCommand(console, config, env(false, true));

        terminal.enter(1 + 1 + SERVER_QUESTIONS + 3 + MEMORY_QUESTIONS + 1);
        console.dispatcher().execute("setup");

        assertTrue(terminal.output().contains("Everything stays as it is"), terminal.output());
        assertTrue(console.commands().find("setup").isPresent());
    }

    private void assertNothingWritten(byte[] cloudBefore) throws IOException {
        assertArrayEquals(cloudBefore, Files.readAllBytes(config.home().cloudFile()), "cloud.yml unchanged");
        try (Stream<Path> groups = Files.list(config.home().groupsDir()); Stream<Path> tasks = Files.list(config.home().tasksDir())) {
            assertEquals(0, groups.count(), "no group written");
            assertEquals(0, tasks.count(), "no task written");
        }
        assertFalse(Files.exists(config.home().secretsFile()), "no secrets written");
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
