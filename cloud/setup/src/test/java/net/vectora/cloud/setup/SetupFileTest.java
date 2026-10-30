package net.vectora.cloud.setup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.MemoryReserve;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.config.operation.PutNode;
import net.vectora.cloud.config.secret.SecretStore;
import net.vectora.cloud.console.Console;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The non-interactive way: {@code --setup-file}, checked like typed answers. */
class SetupFileTest {

    private static final String FULL = """
            schema-version: 1
            language: de
            eula: true
            network-name: Testnetz
            servers:
              mod: false
              legacy-proxy: true
              legacy-server: true
            network:
              bind: 127.0.0.1
              java-port: 25570
              bedrock-port: 19140
              legacy-port: 25571
            memory:
              survival: 4G
            database:
              mode: h2
            """;

    @TempDir
    Path root;

    private ConfigService config;

    @BeforeEach
    void open() throws Exception {
        config = ConfigService.open(CloudHome.of(root));
    }

    @AfterEach
    void close() throws IOException {
        config.close();
    }

    private SetupEnvironment env(String file, boolean pterodactyl) throws IOException {
        Path path = root.resolve("setup.yml");
        Files.writeString(path, file);
        return new SetupEnvironment(Language.EN, false, Optional.of(path), false, pterodactyl, pterodactyl, true,
                OptionalLong.empty(), OptionalInt.empty(), OptionalLong.empty());
    }

    private SetupException refused(String file, boolean pterodactyl) throws IOException {
        byte[] before = Files.readAllBytes(config.home().cloudFile());
        SetupEnvironment env = env(file, pterodactyl);
        SetupException e = assertThrows(SetupException.class, () -> SetupWizard.nonInteractive(config, env, null));
        assertArrayEquals(before, Files.readAllBytes(config.home().cloudFile()), "nothing written");
        assertFalse(Files.exists(config.home().secretsFile()), "no secrets written");
        assertTrue(e.describe(Language.EN).contains("setup.yml has problems"), e.describe(Language.EN));
        return e;
    }

    @Test
    void fullFileIsAppliedAndCompletesTheSetup() throws Exception {
        SetupResult result = SetupWizard.nonInteractive(config, env(FULL, false), null);

        assertEquals(SetupResult.Status.COMPLETED, result.status());
        var cloud = config.snapshot().cloud();
        assertTrue(cloud.setupComplete());
        assertTrue(cloud.eulaAccepted());
        assertEquals(Language.DE, cloud.language());
        assertEquals("Testnetz", cloud.networkName());
        assertEquals("127.0.0.1", cloud.proxy().bind());
        assertEquals(25570, cloud.proxy().port());
        assertEquals(19140, cloud.proxy().bedrockPort());
        assertTrue(cloud.legacyProxy().enabled());
        assertEquals(25571, cloud.legacyProxy().port());
        assertTrue(cloud.servers().isWanted(ServerKind.LEGACY_SERVER));
        assertFalse(cloud.servers().isWanted(ServerKind.MOD));
        assertEquals(DatabaseMode.H2, cloud.database().mode());
        assertFalse(config.snapshot().groups().containsKey("mod"), "an unwanted server gets no group");
        assertEquals(MemorySize.parse("4G"), config.snapshot().groups().get("survival").heap().max());
        assertTrue(new SecretStore(config.home()).load().hasValue("VECTORA_MANAGEMENT_SECRET"));
    }

    @Test
    void minimalFileTakesTheDefaults() throws Exception {
        SetupWizard.nonInteractive(config, env("schema-version: 1\neula: true\n", false), null);
        var cloud = config.snapshot().cloud();
        assertTrue(cloud.setupComplete());
        assertEquals(25565, cloud.proxy().port());
        assertEquals(DatabaseMode.MANAGED, cloud.database().mode());
        assertEquals(Set.of("proxy", "lobby", "survival", "creative", "mod", "end", "minigame"),
                config.snapshot().groups().keySet());
    }

    @Test
    void missingOrFalseEulaIsRefused() throws Exception {
        assertTrue(refused("schema-version: 1\n", false).describe(Language.EN).contains("eula: true"));
        assertTrue(refused("schema-version: 1\neula: false\n", false).describe(Language.EN).contains(SetupMessages.EULA_URL));
    }

    @Test
    void unknownFieldsWrongTypesAndVersionsAreRefused() throws Exception {
        assertTrue(refused("schema-version: 1\neula: true\npassword: x\n", false).describe(Language.EN).contains("password"));
        refused("schema-version: 1\neula: yes-please\n", false);
        refused("schema-version: 1\neula: true\nnetwork:\n  java-port: many\n", false);
        refused("schema-version: 2\neula: true\n", false);
        refused("schema-version: 1\neula: true\neula: true\n", false);
        refused("- a list\n", false);
    }

    @Test
    void checksAreTheSameAsForTypedAnswers() throws Exception {
        refused("schema-version: 1\neula: true\nnetwork:\n  java-port: 7010\n", false);
        refused("schema-version: 1\neula: true\nnetwork:\n  java-port: 25565\n  bedrock-port: 25565\n", false);
        assertTrue(refused("schema-version: 1\neula: true\nservers:\n  legacy-server: true\n", false)
                .describe(Language.EN).contains("needs the legacy proxy"));
        refused("schema-version: 1\neula: true\nmemory:\n  survival: 1M\n", false);
        refused("schema-version: 1\neula: true\nnetwork-name: \"\"\n", false);
    }

    @Test
    void dockerIsRefusedOnPterodactylAndAllowedElsewhere() throws Exception {
        String docker = "schema-version: 1\neula: true\ndatabase:\n  mode: docker\n";
        refused(docker, true);
        assertEquals(SetupResult.Status.COMPLETED, SetupWizard.nonInteractive(config, env(docker, false), null).status());
        assertEquals(DatabaseMode.DOCKER, config.snapshot().cloud().database().mode());
    }

    @Test
    void serviceCountsComeFromTheFileOrTheSuggestion() throws Exception {
        String file = """
                schema-version: 1
                eula: true
                servers:
                  legacy-proxy: true
                  legacy-server: true
                services:
                  lobby: {static-services: 2, min-services: 2, max-services: 6}
                  minigame: {max-services: 12}
                """;
        assertEquals(SetupResult.Status.COMPLETED, SetupWizard.nonInteractive(config, env(file, false), null).status());
        var tasks = config.snapshot().tasks();
        assertEquals(new ServiceCounts(2, 2, 6), ServiceCounts.of(tasks.get("lobby").scaling()));
        assertEquals(new ServiceCounts(0, 1, 12), ServiceCounts.of(tasks.get("minigame").scaling()), "only max changed");
        // Counter-check: the suggestions of every other server.
        assertEquals(new ServiceCounts(1, 1, 1), ServiceCounts.of(tasks.get("survival").scaling()));
        assertEquals(new ServiceCounts(1, 1, 3), ServiceCounts.of(tasks.get("legacy-server").scaling()));
        assertEquals(new ServiceCounts(1, 1, 1), ServiceCounts.of(tasks.get("proxy").scaling()));
        assertEquals(new ServiceCounts(1, 1, 1), ServiceCounts.of(tasks.get("legacy-proxy").scaling()));
        assertEquals(75, tasks.get("lobby").scaling().scaleUpAt());
        assertEquals(50, tasks.get("lobby").scaling().scaleDownBelow());
        assertEquals(16, tasks.get("minigame").scaling().playersPerService());
    }

    @Test
    void invalidOrUnknownServiceCountsAreRefused() throws Exception {
        String above = refused("schema-version: 1\neula: true\nservices:\n  lobby: {static-services: 2}\n", false)
                .describe(Language.EN);
        assertTrue(above.contains("Services of lobby"), above);
        refused("schema-version: 1\neula: true\nservices:\n  lobby: {max-services: 0}\n", false);
        String unknown = refused("schema-version: 1\neula: true\nservices:\n  nonsense: {max-services: 2}\n", false)
                .describe(Language.EN);
        assertTrue(unknown.contains("nonsense"), unknown);
        String proxy = refused("schema-version: 1\neula: true\nservices:\n  proxy: {max-services: 2}\n", false)
                .describe(Language.EN);
        assertTrue(proxy.contains("proxies always run one service"), proxy);
        refused("schema-version: 1\neula: true\nservices:\n  lobby: {static: 1}\n", false);
    }

    @Test
    void drainTimeoutIsOptionalPerGroup() throws Exception {
        String file = "schema-version: 1\neula: true\ndrain-timeout:\n  lobby: 45s\n";
        assertEquals(SetupResult.Status.COMPLETED, SetupWizard.nonInteractive(config, env(file, false), null).status());
        var groups = config.snapshot().groups();
        assertEquals(TimeSpan.parse("45s"), groups.get("lobby").drainTimeout());
        assertEquals(null, groups.get("survival").drainTimeout(), "counter-check: other groups keep the default");
        assertEquals(TimeSpan.parse("30s"), groups.get("survival").drainTimeoutOrDefault());
    }

    @Test
    void drainTimeoutOfAnExistingGroupIsChangedAndCheckedLikeTheFile() throws Exception {
        config.apply(new net.vectora.cloud.config.operation.PutGroup("survival",
                SetupPlan.newGroup(NetworkServer.SURVIVAL, MemorySize.parse("2G")).withDrainTimeout(TimeSpan.parse("40s"))));
        String range = refused("schema-version: 1\neula: true\ndrain-timeout:\n  survival: 11m\n", false).describe(Language.EN);
        assertTrue(range.contains("drain-timeout") && range.contains("between 5s and 10m"), range);
        String unknown = refused("schema-version: 1\neula: true\ndrain-timeout:\n  nonsense: 45s\n", false).describe(Language.EN);
        assertTrue(unknown.contains("nonsense"), unknown);
        refused("schema-version: 1\neula: true\ndrain-timeout:\n  survival: soon\n", false);
        assertEquals(TimeSpan.parse("40s"), config.snapshot().groups().get("survival").drainTimeout(), "unchanged");
        // Counter-check: a valid value replaces the one of the existing group.
        String change = "schema-version: 1\neula: true\ndrain-timeout:\n  survival: 2m\n";
        assertEquals(SetupResult.Status.COMPLETED, SetupWizard.nonInteractive(config, env(change, false), null).status());
        assertEquals(TimeSpan.parse("2m"), config.snapshot().groups().get("survival").drainTimeout());
    }

    @Test
    void nodeMemoryIsOptionalAndCheckedLikeNodesYml() throws Exception {
        String low = refused("schema-version: 1\neula: true\nnode:\n  max-memory: 256M\n", false).describe(Language.EN);
        assertTrue(low.contains("max-memory"), low);
        String high = refused("schema-version: 1\neula: true\nnode:\n  memory-reserve:\n    database: 65G\n", false)
                .describe(Language.EN);
        assertTrue(high.contains("memory-reserve.database") && high.contains("between 1M and 64G"), high);
        refused("schema-version: 1\neula: true\nnode:\n  memory-reserve:\n    sytem: 2G\n", false);
        refused("schema-version: 1\neula: true\nnode:\n  max-memory: 8 GB\n", false);

        String file = """
                schema-version: 1
                eula: true
                node:
                  max-memory: 8G
                  memory-reserve:
                    system: 2G
                """;
        assertEquals(SetupResult.Status.COMPLETED, SetupWizard.nonInteractive(config, env(file, false), null).status());
        var node = config.snapshot().nodes().find(config.snapshot().cloud().nodeId()).orElseThrow();
        assertEquals(MemorySize.parse("8G"), node.maxMemory());
        assertEquals(new MemoryReserve(MemorySize.parse("2G"), null, null), node.reserve(), "only the given field is written");
        assertEquals(MemorySize.parse("512M"), node.memoryReserve().database());
    }

    @Test
    void maxMemoryAutoRemovesTheLimit() throws Exception {
        var before = config.snapshot().nodes().nodes().getFirst();
        config.apply(List.of(new PutNode(before.withMaxMemory(MemorySize.parse("8G")))));
        String typo = refused("schema-version: 1\neula: true\nnode:\n  max-memory: lots\n", false).describe(Language.EN);
        assertTrue(typo.contains("node.max-memory: write auto"), typo);
        assertEquals(MemorySize.parse("8G"), config.snapshot().nodes().nodes().getFirst().maxMemory(), "refused: unchanged");

        assertEquals(SetupResult.Status.COMPLETED, SetupWizard.nonInteractive(config,
                env("schema-version: 1\neula: true\nnode:\n  max-memory: auto\n", false), null).status());
        assertEquals(null, config.snapshot().nodes().nodes().getFirst().maxMemory());
    }

    @Test
    void withoutNodeSectionNodesYmlStaysAsItIs() throws Exception {
        byte[] before = Files.readAllBytes(root.resolve("config/nodes.yml"));
        SetupWizard.nonInteractive(config, env("schema-version: 1\neula: true\n", false), null);
        assertArrayEquals(before, Files.readAllBytes(root.resolve("config/nodes.yml")));
        assertEquals(null, config.snapshot().nodes().nodes().getFirst().maxMemory());
    }

    @Test
    void unknownMemoryGroupsAndEmptyMinigameSwitchesAreNamed() throws Exception {
        String message = refused("schema-version: 1\neula: true\nmemory:\n  nonsense: 1G\n", false).describe(Language.EN);
        assertTrue(message.contains("nonsense"), message);
        refused("schema-version: 1\neula: true\nminigames:\n  bedwars: null\n", false);
        refused("schema-version: 1\neula: true\nminigames:\n  bedwars: true\n", false);
    }

    @Test
    void externalDatabaseNeedsAPassword() throws Exception {
        String external = """
                schema-version: 1
                eula: true
                database:
                  mode: external
                  host: db.example.org
                  name: lp
                  user: lp
                """;
        assertTrue(refused(external, false).describe(Language.EN).contains("VECTORA_DB_PASSWORD"));

        Files.writeString(root.resolve("db-password"), "from-a-docker-secret-42\n");
        SetupResult result = SetupWizard.nonInteractive(config, env(external + "  password-file: db-password\n", false), null);
        assertEquals(SetupResult.Status.COMPLETED, result.status());
        assertEquals(3306, config.snapshot().cloud().database().port());
        assertEquals("from-a-docker-secret-42",
                new SecretStore(config.home()).load().get("VECTORA_DB_PASSWORD").orElseThrow().reveal());
    }

    @Test
    void externalDatabaseTakesAPasswordAlreadyInTheSecretsFile() throws Exception {
        Files.createDirectories(config.home().secretsFile().getParent());
        Files.writeString(config.home().secretsFile(), "VECTORA_DB_PASSWORD=written-by-the-operator\n");
        String external = "schema-version: 1\neula: true\ndatabase:\n  mode: external\n  host: db.example.org\n";
        assertEquals(SetupResult.Status.COMPLETED, SetupWizard.nonInteractive(config, env(external, false), null).status());
        assertEquals("written-by-the-operator",
                new SecretStore(config.home()).load().get("VECTORA_DB_PASSWORD").orElseThrow().reveal());
    }

    @Test
    void badPasswordFilesAreRefused() throws Exception {
        String external = "schema-version: 1\neula: true\ndatabase:\n  mode: external\n  host: db.example.org\n"
                + "  password-file: pw\n";
        assertTrue(refused(external, false).describe(Language.EN).contains("cannot be read"));
        Files.writeString(root.resolve("pw"), "two\nlines\n");
        assertTrue(refused(external, false).describe(Language.EN).contains("one line"));
    }

    @Test
    void ensureCompletePicksTheRightWay() throws Exception {
        SetupEnvironment none = new SetupEnvironment(Language.EN, false, Optional.empty(), false, false, false, true,
                OptionalLong.empty(), OptionalInt.empty(), OptionalLong.empty());
        SetupException e = assertThrows(SetupException.class, () -> SetupWizard.ensureComplete(null, config, none));
        assertTrue(e.describe(Language.EN).contains("--setup-file"));
        assertThrows(SetupException.class, () -> SetupWizard.ensureComplete(null, config, InteractiveSetupTest.env(false, true)),
                "questions allowed, but there is no console");

        assertEquals(SetupResult.Status.COMPLETED, SetupWizard.ensureComplete(null, config, env(FULL, false)).status());
        byte[] cloud = Files.readAllBytes(config.home().cloudFile());

        SetupEnvironment changed = env("schema-version: 1\neula: true\nnetwork-name: Other\n", false);
        assertEquals(SetupResult.Status.ALREADY_COMPLETE, SetupWizard.ensureComplete(null, config, changed).status());
        assertArrayEquals(cloud, Files.readAllBytes(config.home().cloudFile()), "a file that stays mounted changes nothing");
        assertEquals(SetupResult.Status.ALREADY_COMPLETE, SetupWizard.ensureComplete(null, config, none).status());
    }

    @Test
    void reportGoesToTheConsoleWithoutSecrets() throws Exception {
        ScriptedTerminal terminal = new ScriptedTerminal();
        Console console = Console.builder().terminal(terminal).build();
        SetupWizard.ensureComplete(console, config, env(FULL, false));
        String output = terminal.output();
        assertTrue(output.contains("setup.yml"), output);
        assertTrue(output.contains("Who can read the secrets"), output);
        String channel = new SecretStore(config.home()).load().get("VECTORA_MANAGEMENT_SECRET").orElseThrow().reveal();
        assertFalse(output.contains(channel));
    }

    @Test
    void missingFileIsReportedNotThrownAsIoError() throws Exception {
        SetupEnvironment env = new SetupEnvironment(Language.EN, false, Optional.of(root.resolve("absent.yml")), false,
                false, false, true, OptionalLong.empty(), OptionalInt.empty(), OptionalLong.empty());
        SetupException e = assertThrows(SetupException.class, () -> SetupWizard.nonInteractive(config, env, null));
        assertTrue(e.describe(Language.EN).contains("absent.yml"), e.describe(Language.EN));
        assertTrue(e.describe(Language.EN).contains("cannot be read"), e.describe(Language.EN));
    }

    @Test
    void readTakesOnlyRegularFilesUpToTheLimit() throws Exception {
        Files.createDirectories(root.resolve("folder.yml"));
        SetupException folder = assertThrows(SetupException.class, () -> SetupFile.read(root.resolve("folder.yml")));
        assertTrue(folder.describe(Language.EN).contains("cannot be read"), folder.describe(Language.EN));
        if (Files.exists(Path.of("/dev/zero"))) {
            // Endless: refused before it is opened, instead of filling the memory.
            assertThrows(SetupException.class, () -> SetupFile.read(Path.of("/dev/zero")));
        }
        Path large = root.resolve("large.yml");
        byte[] tooLarge = new byte[net.vectora.cloud.config.io.ConfigMappers.MAX_FILE_BYTES + 1];
        java.util.Arrays.fill(tooLarge, (byte) '#');
        Files.write(large, tooLarge);
        SetupException e = assertThrows(SetupException.class, () -> SetupFile.read(large));
        assertTrue(e.describe(Language.EN).contains("larger than"), e.describe(Language.EN));

        // Counter-check: a regular file within the limit is read.
        Files.writeString(large, "schema-version: 1\neula: true\n");
        assertTrue(SetupFile.read(large).toString().startsWith("SetupFile"));
    }

    @Test
    void nonInteractiveLeavesACompletedSetupAlone() throws Exception {
        SetupWizard.nonInteractive(config, env(FULL, false), null);
        byte[] cloud = Files.readAllBytes(config.home().cloudFile());
        SetupResult again = SetupWizard.nonInteractive(config,
                env("schema-version: 1\neula: true\nnetwork-name: Other\n", false), null);
        assertEquals(SetupResult.Status.ALREADY_COMPLETE, again.status());
        assertArrayEquals(cloud, Files.readAllBytes(config.home().cloudFile()), "a mounted file undoes nothing");
    }

    @Test
    void parseReportsLineAndPath() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> SetupFile.parse("schema-version: 1\neula: true\nnetwork:\n  jav-port: 1\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.describe(Language.EN).contains("setup file"), e.describe(Language.EN));
        assertTrue(e.describe(Language.EN).contains("jav-port"), e.describe(Language.EN));
    }

    @Test
    void toStringShowsNoValues() throws Exception {
        SetupFile file = SetupFile.parse(FULL.getBytes(StandardCharsets.UTF_8));
        assertFalse(file.toString().contains("Testnetz"));
    }
}
