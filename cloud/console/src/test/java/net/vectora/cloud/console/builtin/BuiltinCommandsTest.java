package net.vectora.cloud.console.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.MemoryReserve;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.console.Console;
import net.vectora.cloud.console.FakeTerminal;
import net.vectora.cloud.console.command.CommandDispatcher;
import net.vectora.cloud.console.command.CommandDispatcher.Outcome;
import net.vectora.cloud.console.terminal.ConsoleTerminal.LineKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The basic commands against a real configuration in a temporary cloud home. */
class BuiltinCommandsTest {

    @TempDir
    Path home;

    private ConfigService config;
    private FakeTerminal terminal;
    private Console console;
    private final AtomicInteger shutdowns = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        config = ConfigService.open(CloudHome.of(home));
        terminal = new FakeTerminal();
        console = Console.builder().terminal(terminal).language(() -> config.snapshot().cloud().language()).build();
        BuiltinCommands.register(console, config, shutdowns::incrementAndGet);
    }

    @AfterEach
    void tearDown() throws Exception {
        config.close();
    }

    private Outcome run(String line) {
        return console.dispatcher().execute(line);
    }

    private String cloudFile() throws Exception {
        return Files.readString(home.resolve("cloud.yml"), StandardCharsets.UTF_8);
    }

    @Test
    void configGetSetListGoThroughTheConfigurationLayer() throws Exception {
        assertEquals(Outcome.DONE, run("config get proxy.port"));
        assertTrue(terminal.output().contains("proxy.port = 25565"));

        assertEquals(Outcome.DONE, run("config set proxy.port 25570"));
        assertEquals(25570, config.snapshot().cloud().proxy().port());
        assertTrue(cloudFile().contains("port: 25570"), "written to cloud.yml");
        assertTrue(terminal.output().contains("Saved: proxy.port = 25570"));

        assertEquals(Outcome.DONE, run("config set network-name 'My Network'"));
        assertEquals("My Network", config.snapshot().cloud().networkName());

        assertEquals(Outcome.DONE, run("config set servers.mod false"));
        assertFalse(config.snapshot().cloud().servers().isWanted(ServerKind.MOD));

        terminal.clearOutput();
        assertEquals(Outcome.DONE, run("config list"));
        assertTrue(terminal.output().contains("network-name"));
        assertTrue(terminal.output().contains("api.tls.keystore"));
        assertEquals(ConfigKeys.names().size(), terminal.printed().size());
    }

    @Test
    void configSetUsesTheSameValidationAsFilesAndWritesNothingOnError() throws Exception {
        String before = cloudFile();
        assertEquals(Outcome.FAILED, run("config set proxy.port 70000"));
        assertTrue(terminal.output().contains("[out-of-range]") || terminal.output().contains("Must be between 1 and 65535"),
                terminal.output());
        assertEquals(Outcome.FAILED, run("config set proxy.port \"25565\"x"));
        assertEquals(Outcome.FAILED, run("config set eula-accepted yes"), "yes is not a boolean, as in the file");
        assertEquals(Outcome.FAILED, run("config set setup-complete true"), "needs the EULA first");
        assertEquals(Outcome.FAILED, run("config set network-name 'bad/name'"));
        assertEquals(before, cloudFile(), "nothing written after rejected changes");
        assertTrue(terminal.output().contains("Expected: true or false"));
    }

    @Test
    void apiOnAPublicAddressNeedsTls() {
        // Counter-check for the line above: the bind is accepted while the API is off.
        assertEquals(Outcome.DONE, run("config set api.bind 0.0.0.0"));
        assertEquals(Outcome.FAILED, run("config set api.enabled true"));
        assertTrue(terminal.output().contains("needs TLS"), terminal.output());
        assertFalse(config.snapshot().cloud().api().enabled());
    }

    @Test
    void unknownKeyIsAnErrorAndCompletionKnowsTheKeys() {
        assertEquals(Outcome.FAILED, run("config get proxy.prot"));
        assertTrue(terminal.output().contains("Unknown key."));
        assertEquals(List.of("proxy.bedrock-port", "proxy.bind", "proxy.port"),
                CommandDispatcher.complete(console.commands(), "config get proxy."));
        assertEquals(List.of("false", "true"), CommandDispatcher.complete(console.commands(), "config set api.enabled "));
        assertEquals(List.of("de", "en"), CommandDispatcher.complete(console.commands(), "config set language "));
    }

    @Test
    void languageFromTheConfigurationSwitchesTheConsole() {
        run("config set language de");
        terminal.clearOutput();
        run("config get nope");
        run("config set proxy.port 0");
        assertTrue(terminal.output().contains("Unbekannter Schlüssel."), terminal.output());
        assertTrue(terminal.output().contains("Muss zwischen 1 und 65535 liegen."), terminal.output());
        terminal.clearOutput();
        run("help");
        assertTrue(terminal.output().contains("Befehle:"));
    }

    @Test
    void configReloadPicksUpHandEdits() throws Exception {
        assertEquals(Outcome.DONE, run("config reload"));
        assertTrue(terminal.output().contains("No changes on disk."));
        Files.writeString(home.resolve("cloud.yml"), cloudFile().replace("language: en", "language: de"));
        assertEquals(Outcome.DONE, run("config reload"));
        assertEquals(Language.DE, config.snapshot().cloud().language());
        assertTrue(terminal.output().contains("Neu geladen: cloud.yml"), terminal.output());
        Files.writeString(home.resolve("cloud.yml"), cloudFile().replace("language: de", "langauge: de"));
        assertEquals(Outcome.FAILED, run("config reload"));
        assertTrue(terminal.output().contains("unknown-field") || terminal.output().contains("Unbekanntes Feld"), terminal.output());
        assertEquals(Language.DE, config.snapshot().cloud().language(), "the previous configuration stays");
    }

    @Test
    void groupLifecycle() throws Exception {
        assertEquals(Outcome.DONE, run("group create lobby Lobby-Server paper 26.3"));
        assertTrue(Files.isRegularFile(home.resolve("groups/lobby.yml")));
        assertEquals(ServerSoftware.PAPER, config.snapshot().groups().get("lobby").software());
        assertEquals(Outcome.FAILED, run("group create lobby Lobby-Server paper 26.3"));
        assertTrue(terminal.output().contains("\"lobby\" already exists."));

        assertEquals(Outcome.DONE, run("group set lobby heap.max 2G"));
        assertEquals(2048, config.snapshot().groups().get("lobby").heap().max().megabytes());
        assertEquals(Outcome.FAILED, run("group set lobby heap.min 4G"), "min above max is rejected by the validator");
        assertEquals(Outcome.FAILED, run("group set lobby heap.min lots"));
        assertEquals(Outcome.FAILED, run("group set lobby colour red"));
        assertEquals(Outcome.FAILED, run("group set nobody heap.max 1G"));
        assertTrue(terminal.output().contains("Group \"nobody\" does not exist."));
        assertEquals(Outcome.DONE, run("group set lobby java 21"));
        assertEquals(21, config.snapshot().groups().get("lobby").java().major());

        terminal.clearOutput();
        assertEquals(Outcome.DONE, run("group list"));
        assertTrue(terminal.output().contains("lobby  Lobby-Server  paper 26.3  wanted"), terminal.output());

        assertEquals(Outcome.DONE, run("group wanted lobby off"));
        assertFalse(config.snapshot().cloud().servers().isWanted(ServerKind.LOBBY));
        assertEquals(Outcome.FAILED, run("group wanted lobby maybe"));
        assertEquals(Outcome.DONE, run("group wanted lobby on"));
        assertTrue(config.snapshot().cloud().servers().isWanted(ServerKind.LOBBY));

        assertEquals(Outcome.DONE, run("group delete lobby --yes"));
        assertFalse(Files.exists(home.resolve("groups/lobby.yml")));
    }

    @Test
    void fallbackGroupMustExistAndEmptyMeansTheDefault() throws Exception {
        assertEquals(Outcome.DONE, run("config get fallback-group"));
        assertTrue(terminal.output().contains("fallback-group = lobby"), terminal.output());
        String before = cloudFile();
        assertEquals(Outcome.FAILED, run("config set fallback-group hub"));
        assertTrue(terminal.output().contains("Group \"hub\" does not exist."), terminal.output());
        assertEquals(Outcome.FAILED, run("config set fallback-group Hub!"));
        assertEquals(before, cloudFile(), "nothing written after rejected changes");
        // Counter-check: an existing group is taken and written; "" goes back to the default.
        run("group create hub Lobby-Server paper 26.3");
        assertEquals(Outcome.DONE, run("config set fallback-group hub"));
        assertEquals("hub", config.snapshot().cloud().fallbackGroup());
        assertTrue(cloudFile().contains("fallback-group: \"hub\"") || cloudFile().contains("fallback-group: hub"),
                cloudFile());
        assertEquals(Outcome.FAILED, run("group delete hub --yes"), "named as fallback group");
        assertEquals(Outcome.DONE, run("config set fallback-group ''"));
        assertEquals(null, config.snapshot().cloud().fallbackGroup());
        assertFalse(cloudFile().contains("fallback-group"));
    }

    @Test
    void groupSetDrainTimeout() throws Exception {
        run("group create lobby Lobby-Server paper 26.3");
        assertEquals(Outcome.DONE, run("group info lobby"));
        assertTrue(terminal.output().contains("drain-timeout     = 30s (default)"), terminal.output());
        assertEquals(Outcome.DONE, run("group set lobby drain-timeout 45s"));
        assertEquals(net.vectora.cloud.config.model.TimeSpan.parse("45s"), config.snapshot().groups().get("lobby").drainTimeout());
        assertTrue(Files.readString(home.resolve("groups/lobby.yml")).contains("drain-timeout: \"45s\""));
        // Out of range and wrong form are refused, the value stays.
        assertEquals(Outcome.FAILED, run("group set lobby drain-timeout 4s"));
        assertTrue(terminal.output().contains("Must be between 5s and 10m."), terminal.output());
        assertEquals(Outcome.FAILED, run("group set lobby drain-timeout 11m"));
        assertEquals(Outcome.FAILED, run("group set lobby drain-timeout soon"));
        assertEquals(net.vectora.cloud.config.model.TimeSpan.parse("45s"), config.snapshot().groups().get("lobby").drainTimeout());
        // Counter-check: the limits themselves are accepted, and default removes the field.
        assertEquals(Outcome.DONE, run("group set lobby drain-timeout 10m"));
        assertEquals(Outcome.DONE, run("group set lobby drain-timeout 5s"));
        assertEquals(Outcome.DONE, run("group set lobby drain-timeout default"));
        assertEquals(null, config.snapshot().groups().get("lobby").drainTimeout());
        assertFalse(Files.readString(home.resolve("groups/lobby.yml")).contains("drain-timeout"));
    }

    @Test
    void nodeListInfoAndMemorySettings() throws Exception {
        assertEquals(Outcome.DONE, run("node list"));
        assertTrue(terminal.output().contains("node-1  127.0.0.1:7010  max-memory auto  (this node)"), terminal.output());
        assertEquals(Outcome.DONE, run("node info node-1"));
        assertTrue(terminal.output().contains("memory-reserve.system        = 1G (default)"), terminal.output());
        assertTrue(terminal.output().contains("memory-reserve.node-overhead = 256M (default)"), terminal.output());
        assertTrue(terminal.output().contains("memory-reserve.database      = 512M (default)"), terminal.output());

        assertEquals(Outcome.DONE, run("node set node-1 max-memory 8G"));
        assertEquals(MemorySize.parse("8G"), node().maxMemory());
        assertEquals(Outcome.DONE, run("node set node-1 memory-reserve.system 2G"));
        assertEquals(Outcome.DONE, run("node set node-1 memory-reserve.database 1G"));
        assertEquals(new MemoryReserve(MemorySize.parse("2G"), null, MemorySize.parse("1G")), node().reserve());
        assertEquals(MemorySize.parse("256M"), node().memoryReserve().nodeOverhead());
        String file = Files.readString(home.resolve("nodes.yml"));
        assertTrue(file.contains("memory-reserve:") && file.contains("max-memory: \"8G\""), file);

        // Refused like a hand edit: below 512M, above 64G, wrong form, unknown node or field.
        assertEquals(Outcome.FAILED, run("node set node-1 max-memory 256M"));
        assertEquals(Outcome.FAILED, run("node set node-1 memory-reserve.node-overhead 65G"));
        assertTrue(terminal.output().contains("Must be between 1M and 64G."), terminal.output());
        assertEquals(Outcome.FAILED, run("node set node-1 memory-reserve.system lots"));
        assertEquals(Outcome.FAILED, run("node set node-9 max-memory 8G"));
        assertTrue(terminal.output().contains("Node \"node-9\" does not exist."), terminal.output());
        assertEquals(Outcome.FAILED, run("node set node-1 port 7011"), "only memory fields");
        assertEquals(MemorySize.parse("8G"), node().maxMemory(), "unchanged after the errors");

        // Counter-check: auto and default remove the values again, the file is as before.
        assertEquals(Outcome.DONE, run("node set node-1 max-memory auto"));
        assertEquals(Outcome.DONE, run("node set node-1 memory-reserve.system default"));
        assertEquals(Outcome.DONE, run("node set node-1 memory-reserve.database default"));
        assertEquals(net.vectora.cloud.config.model.NodeConfig.defaults(), node());
        assertFalse(Files.readString(home.resolve("nodes.yml")).contains("memory"));
    }

    private net.vectora.cloud.config.model.NodeConfig node() {
        return config.snapshot().nodes().nodes().getFirst();
    }

    @Test
    void deleteAsksFirstAndEnterOrNoKeepsTheFile() throws Exception {
        run("group create lobby Lobby-Server paper 26.3");
        run("task create lobby lobby");
        Path group = home.resolve("groups/lobby.yml");
        Path task = home.resolve("tasks/lobby.yml");

        terminal.type("n");
        assertEquals(Outcome.DONE, run("task delete lobby"));
        assertTrue(Files.exists(task), "no keeps the file");
        assertEquals("Really delete tasks/lobby.yml? [y/N]: ", terminal.reads().getLast().prompt());
        assertEquals(LineKind.ANSWER, terminal.reads().getLast().kind());
        assertTrue(terminal.output().contains("Nothing was deleted."), terminal.output());

        terminal.type("");
        assertEquals(Outcome.DONE, run("task remove lobby"));
        assertTrue(Files.exists(task), "Enter means no");

        terminal.interrupt();
        assertEquals(Outcome.CANCELLED, run("task delete lobby"));
        assertTrue(Files.exists(task), "Ctrl+C keeps the file");

        terminal.type("y");
        assertEquals(Outcome.DONE, run("task delete lobby"));
        assertFalse(Files.exists(task));
        assertTrue(terminal.output().contains("Deleted: tasks/lobby.yml"), terminal.output());

        terminal.type("ja");
        assertEquals(Outcome.DONE, run("group delete lobby"));
        assertFalse(Files.exists(group));
        assertEquals(0, terminal.pending());
    }

    @Test
    void deleteWithYesSkipsTheQuestionForScripts() throws Exception {
        run("group create lobby Lobby-Server paper 26.3");
        run("group create hub Lobby-Server paper 26.3");
        int reads = terminal.reads().size();
        assertEquals(Outcome.DONE, run("group delete lobby -y"));
        assertEquals(Outcome.DONE, run("group delete hub --yes"));
        assertEquals(reads, terminal.reads().size(), "nothing was asked");
        assertTrue(config.snapshot().groups().isEmpty());
    }

    @Test
    void deleteRefusesOtherFlagsAndChecksBeforeAsking() throws Exception {
        run("group create lobby Lobby-Server paper 26.3");
        run("task create lobby lobby");
        int reads = terminal.reads().size();

        assertEquals(Outcome.FAILED, run("group delete lobby --force"));
        assertTrue(terminal.output().contains("Only --yes or -y may follow the name."), terminal.output());
        assertEquals(Outcome.FAILED, run("group delete lobby"), "a task still uses the group");
        assertEquals(Outcome.FAILED, run("group delete nobody"));
        assertEquals(Outcome.FAILED, run("task delete nobody --yes"));
        assertTrue(terminal.output().contains("Task \"nobody\" does not exist."), terminal.output());
        assertEquals(reads, terminal.reads().size(), "a deletion that cannot work is not asked about");
        assertTrue(Files.exists(home.resolve("groups/lobby.yml")));
        assertTrue(Files.exists(home.resolve("tasks/lobby.yml")));
    }

    @Test
    void groupNamesAndValuesFollowTheFileRules() {
        assertEquals(Outcome.FAILED, run("group create Lobby Lobby-Server paper 26.3"), "upper case is not allowed");
        assertEquals(Outcome.FAILED, run("group create con Lobby-Server paper 26.3"), "Windows device name");
        assertEquals(Outcome.FAILED, run("group create old Lobby-Server pandaspigot 26.3"), "pandaspigot only runs 1.8.8");
        assertEquals(Outcome.FAILED, run("group create x Lobby-Server bukkit 26.3"));
        assertTrue(config.snapshot().groups().isEmpty());
        assertEquals(Outcome.DONE, run("group create old Legacy pandaspigot 1.8.8"));
        assertEquals(Outcome.FAILED, run("group wanted old on"), "no server kind uses that template");
        assertTrue(terminal.output().contains("no wanted switch"));
    }

    private List<Integer> counts(String task) {
        var s = config.snapshot().tasks().get(task).scaling();
        return List.of(s.staticServices(), s.minServices(), s.maxServices());
    }

    @Test
    void modeStaticTakesMinServicesAndAtLeastOne() throws Exception {
        run("group create lobby Lobby-Server paper 26.3");
        run("task create lobby lobby dynamic");
        run("task set lobby max-services 6");
        run("task set lobby min-services 3");
        assertEquals(List.of(0, 3, 6), counts("lobby"));
        assertEquals(Outcome.DONE, run("task set lobby mode static"));
        assertEquals(List.of(3, 3, 3), counts("lobby"), "min-services, not max-services");

        // Counter-check: dynamic only clears static-services and keeps min and max.
        run("task set lobby max-services 6");
        assertEquals(Outcome.DONE, run("task set lobby mode dynamic"));
        assertEquals(List.of(0, 3, 6), counts("lobby"));

        run("task set lobby min-services 0");
        assertEquals(List.of(0, 0, 6), counts("lobby"));
        assertEquals(Outcome.DONE, run("task set lobby mode static"));
        assertEquals(List.of(1, 1, 1), counts("lobby"), "min-services 0 becomes one static service, like the migration");
    }

    @Test
    void taskLifecycleAndReferences() throws Exception {
        assertEquals(Outcome.FAILED, run("task create lobby lobby"), "the group must exist");
        assertTrue(terminal.output().contains("Group \"lobby\" does not exist."), terminal.output());
        run("group create lobby Lobby-Server paper 26.3");
        assertEquals(Outcome.DONE, run("task create lobby lobby dynamic"));
        assertEquals(0, config.snapshot().tasks().get("lobby").scaling().staticServices(), "dynamic: no static service");
        assertEquals(Outcome.FAILED, run("task create lobby lobby"));

        assertEquals(Outcome.DONE, run("task set lobby max-services 4"));
        assertEquals(Outcome.DONE, run("task set lobby static-services 1"));
        assertEquals(1, config.snapshot().tasks().get("lobby").scaling().staticServices());
        assertEquals(Outcome.FAILED, run("task set lobby static-services 2"), "static above min-services");
        assertTrue(terminal.output().contains("static services always run"), terminal.output());
        assertEquals(1, config.snapshot().tasks().get("lobby").scaling().staticServices(), "unchanged after the error");
        assertEquals(Outcome.DONE, run("task set lobby scale-down-below 40"));
        assertEquals(40, config.snapshot().tasks().get("lobby").scaling().scaleDownBelow());
        assertEquals(Outcome.FAILED, run("task set lobby scale-down-below 80"), "not below scale-up-at");
        assertEquals(Outcome.DONE, run("task set lobby scale-up-at 90"));
        assertEquals(Outcome.DONE, run("task set lobby scale-down-below 80"), "counter-check: below the new scale-up-at");
        assertEquals(Outcome.DONE, run("task set lobby mode static"));
        assertEquals(List.of(1, 1, 1), counts("lobby"), "static: static = min = max = min-services");
        assertEquals(Outcome.DONE, run("task info lobby"));
        assertTrue(terminal.output().contains("static-services     = 1"), terminal.output());
        assertTrue(terminal.output().contains("scale-down-below    = 80"), terminal.output());
        assertEquals(Outcome.DONE, run("task set lobby mode dynamic"));
        assertEquals(0, config.snapshot().tasks().get("lobby").scaling().staticServices());
        assertEquals(Outcome.FAILED, run("task set lobby mode elastic"));
        assertEquals(Outcome.DONE, run("task set lobby min-services 1"));
        assertEquals(Outcome.FAILED, run("task set lobby min-services 5"), "min above max");
        assertEquals(Outcome.DONE, run("task set lobby scale-down-after 10m"));
        assertEquals(Outcome.FAILED, run("task set lobby cooldown 10d"));
        assertEquals(Outcome.FAILED, run("task set lobby nodes node-9"), "unknown node");
        assertEquals(Outcome.DONE, run("task set lobby nodes node-1"));
        assertEquals(List.of("node-1"), config.snapshot().tasks().get("lobby").nodes());
        assertEquals(Outcome.DONE, run("task set lobby nodes \"\""));
        assertTrue(config.snapshot().tasks().get("lobby").nodes().isEmpty());

        assertEquals(Outcome.DONE, run("task wanted lobby off"));
        assertFalse(config.snapshot().cloud().servers().isWanted(ServerKind.LOBBY));

        assertEquals(Outcome.FAILED, run("group delete lobby --yes"), "a task still uses the group");
        assertTrue(Files.exists(home.resolve("groups/lobby.yml")));
        assertEquals(Outcome.DONE, run("task delete lobby --yes"));
        assertEquals(Outcome.DONE, run("group delete lobby --yes"));
        terminal.clearOutput();
        run("task list");
        assertTrue(terminal.output().contains("No tasks yet."));
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // no ESC in Windows file names
    void aFileNameFromTheFolderCannotSendEscapeSequences() throws Exception {
        FakeTerminal ansi = new FakeTerminal(true, true);
        Console colored = Console.builder().terminal(ansi).build();
        BuiltinCommands.register(colored, config, () -> { });
        Files.writeString(home.resolve("groups/x\u001b]0;owned\u0007.yml"), "schema-version: 1\n");
        assertEquals(Outcome.FAILED, colored.dispatcher().execute("config reload"));
        String printed = ansi.output();
        assertTrue(printed.contains("x?]0;owned?.yml"), printed);
        assertFalse(printed.replace("\u001b[31m", "").replace("\u001b[0m", "").contains("\u001b"),
                "only the console's own color codes reach the terminal");
    }

    @Test
    void shutdownAndExitRunTheActionAndStopTheConsole() {
        assertEquals(Outcome.DONE, run("exit"));
        assertEquals(1, shutdowns.get());
        assertFalse(console.isRunning());
        assertEquals(Outcome.DONE, run("shutdown"));
        assertEquals(2, shutdowns.get());
    }

    @Test
    void clearOnlyWorksOnAnAnsiTerminal() throws Exception {
        run("clear");
        assertEquals(0, terminal.clears());
        assertTrue(terminal.output().contains("cannot be cleared"));

        FakeTerminal ansi = new FakeTerminal(true, true);
        Console colored = Console.builder().terminal(ansi).build();
        BuiltinCommands.register(colored, config, () -> { });
        colored.dispatcher().execute("cls");
        assertEquals(1, ansi.clears());
        colored.dispatcher().execute("nope");
        assertTrue(ansi.output().contains("\u001b[31m"), "errors are red on an ANSI terminal");
        assertFalse(terminal.output().contains("\u001b["), "no escape codes without ANSI");
    }
}
