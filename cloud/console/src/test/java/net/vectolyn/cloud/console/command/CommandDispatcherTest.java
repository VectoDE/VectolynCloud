package net.vectolyn.cloud.console.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.console.Console;
import net.vectolyn.cloud.console.FakeTerminal;
import net.vectolyn.cloud.console.Text;
import net.vectolyn.cloud.console.builtin.BuiltinCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandDispatcherTest {

    private FakeTerminal terminal;
    private Console console;
    private AtomicReference<Language> language;
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        terminal = new FakeTerminal();
        language = new AtomicReference<>(Language.EN);
        console = Console.builder().terminal(terminal).language(language::get).build();
        console.commands().register(BuiltinCommands.help(console.commands()));
        console.commands().register(Command.builder("service")
                .description(Text.of("Manage services.", "Dienste verwalten."))
                .subcommand(Command.builder("stop")
                        .aliases("halt")
                        .description(Text.of("Stops a service.", "Stoppt einen Dienst."))
                        .argument(Argument.required("service", Text.of("Service id", "Dienst-ID"))
                                .suggest(List.of("lobby-1", "lobby-2", "survival-1")))
                        .argument(Argument.optional("reason", Text.of("Why", "Warum")).takingRest())
                        .executes(context -> calls.add("stop " + context.argument("service") + " / "
                                + context.optional("reason").orElse("-"))))
                .subcommand(Command.builder("fail")
                        .description(Text.of("Fails.", "Scheitert."))
                        .executes(context -> {
                            throw new CommandException(Text.of("Service %1$s is busy.", "Dienst %1$s ist beschäftigt."), "x");
                        }))
                .subcommand(Command.builder("crash")
                        .description(Text.of("Crashes.", "Stürzt ab."))
                        .executes(context -> {
                            throw new IllegalStateException("bug with secret-value inside");
                        }))
                .build());
    }

    private CommandDispatcher.Outcome run(String line) {
        return console.dispatcher().execute(line);
    }

    @Test
    void runsSubcommandWithArgumentsAliasesAndQuotedRest() {
        assertEquals(CommandDispatcher.Outcome.DONE, run("service stop lobby-1"));
        assertEquals(CommandDispatcher.Outcome.DONE, run("service halt 'lobby 2' too many players"));
        assertEquals(List.of("stop lobby-1 / -", "stop lobby 2 / too many players"), calls);
        assertEquals(CommandDispatcher.Outcome.EMPTY, run("   "));
    }

    @Test
    void usageErrorsNameTheUsageAndRunNothing() {
        assertEquals(CommandDispatcher.Outcome.USAGE_ERROR, run("service"));
        assertTrue(terminal.output().contains("A subcommand is missing. Usage: service <stop|fail|crash>"), terminal.output());
        assertEquals(CommandDispatcher.Outcome.USAGE_ERROR, run("service stop"));
        assertTrue(terminal.output().contains("Missing argument <service>. Usage: service stop <service> [reason...]"));
        assertEquals(CommandDispatcher.Outcome.USAGE_ERROR, run("service start x"));
        assertTrue(terminal.output().contains("Unknown subcommand. Usage: service <stop|fail|crash>"));
        assertEquals(CommandDispatcher.Outcome.FAILED, run("help service stop extra"), "help reports an unknown subcommand");
        assertTrue(calls.isEmpty());
    }

    @Test
    void tooManyArgumentsIsAnError() {
        console.commands().register(Command.builder("one")
                .description(Text.of("One argument.", "Ein Argument."))
                .argument(Argument.required("a", Text.of("A", "A")))
                .executes(context -> calls.add(context.argument("a")))
                .build());
        assertEquals(CommandDispatcher.Outcome.USAGE_ERROR, run("one x y"));
        assertTrue(terminal.output().contains("Too many arguments. Usage: one <a>"));
        assertTrue(calls.isEmpty());
    }

    @Test
    void unknownCommandAndSyntaxErrorsNeverRepeatTheInput() {
        String secret = "S3cretPassw0rd";
        assertEquals(CommandDispatcher.Outcome.UNKNOWN_COMMAND, run(secret));
        assertEquals(CommandDispatcher.Outcome.SYNTAX_ERROR, run("service stop '" + secret));
        assertEquals(CommandDispatcher.Outcome.FAILED, run("service crash"));
        assertFalse(terminal.output().contains(secret), terminal.output());
        assertFalse(terminal.output().contains("secret-value"), "exception messages stay in the log");
        assertTrue(terminal.output().contains("Unknown command. Type \"help\" for a list."));
        assertTrue(terminal.output().contains("Unclosed quote at position 13."));
    }

    @Test
    void commandErrorsAreShownInTheConfiguredLanguage() {
        assertEquals(CommandDispatcher.Outcome.FAILED, run("service fail"));
        assertTrue(terminal.output().contains("Service x is busy."));
        language.set(Language.DE);
        terminal.clearOutput();
        run("service fail");
        run("nope");
        run("service");
        assertTrue(terminal.output().contains("Dienst x ist beschäftigt."));
        assertTrue(terminal.output().contains("Unbekannter Befehl."));
        assertTrue(terminal.output().contains("Es fehlt ein Unterbefehl. Aufruf: service <stop|fail|crash>"));
    }

    @Test
    void helpListsCommandsAndExplainsOneInBothLanguages() {
        run("help");
        assertTrue(terminal.output().contains("Commands:"));
        assertTrue(terminal.output().contains("service  Manage services."));
        terminal.clearOutput();
        run("help service stop");
        assertTrue(terminal.output().contains("service stop <service> [reason...]"));
        assertTrue(terminal.output().contains("Also: halt"));
        assertTrue(terminal.output().contains("<service>"));
        language.set(Language.DE);
        terminal.clearOutput();
        run("help service");
        assertTrue(terminal.output().contains("Dienste verwalten."));
        assertTrue(terminal.output().contains("Unterbefehle:"));
        assertTrue(terminal.output().contains("Stoppt einen Dienst."));
        terminal.clearOutput();
        run("help nothing");
        assertTrue(terminal.output().contains("Unbekannter Befehl."));
    }

    @Test
    void completionOffersCommandsSubcommandsAndArgumentValues() {
        CommandRegistry registry = console.commands();
        assertEquals(List.of("help", "service"), CommandDispatcher.complete(registry, ""));
        assertEquals(List.of("service"), CommandDispatcher.complete(registry, "se"));
        assertEquals(List.of("crash", "fail", "halt", "stop"), CommandDispatcher.complete(registry, "service "));
        assertEquals(List.of("halt"), CommandDispatcher.complete(registry, "service h"));
        assertEquals(List.of("lobby-1", "lobby-2"), CommandDispatcher.complete(registry, "service stop lob"));
        assertEquals(List.of(), CommandDispatcher.complete(registry, "unknown "));
        assertEquals(List.of(), CommandDispatcher.complete(registry, "service nope "));
        assertEquals(List.of("help", "service"), CommandDispatcher.complete(registry, "help "));
    }

    @Test
    void registryRejectsTakenNamesAndUnregisters() {
        CommandRegistry registry = console.commands();
        Command other = Command.builder("halt-all").aliases("help")
                .description(Text.of("x", "x")).executes(context -> { }).build();
        assertThrows(IllegalArgumentException.class, () -> registry.register(other));
        assertTrue(registry.find("halt-all").isEmpty(), "nothing of a refused command is registered");
        assertTrue(registry.unregister("service"));
        assertFalse(registry.unregister("service"));
        assertEquals(CommandDispatcher.Outcome.UNKNOWN_COMMAND, run("service stop x"));
    }

    @Test
    void commandDefinitionsAreChecked() {
        Text t = Text.of("x", "x");
        assertThrows(IllegalArgumentException.class, () -> Command.builder("Bad"));
        assertThrows(IllegalArgumentException.class, () -> Command.builder("x").description(t).build(), "needs executor or subcommands");
        assertThrows(IllegalArgumentException.class, () -> Command.builder("x").description(t)
                .argument(Argument.optional("a", t)).argument(Argument.required("b", t)).executes(c -> { }).build());
        assertThrows(IllegalArgumentException.class, () -> Command.builder("x").description(t)
                .argument(Argument.required("a", t).takingRest()).argument(Argument.required("b", t)).executes(c -> { }).build());
        assertThrows(IllegalArgumentException.class, () -> Command.builder("x").description(t)
                .subcommand(Command.builder("a").description(t).executes(c -> { }))
                .subcommand(Command.builder("b").aliases("a").description(t).executes(c -> { })).build());
    }

    @Test
    void sensitiveCommandsAreRecognisedThroughSubcommands() {
        console.commands().register(Command.builder("secret")
                .description(Text.of("Secrets.", "Geheimnisse."))
                .subcommand(Command.builder("set").sensitive().description(Text.of("Sets.", "Setzt."))
                        .argument(Argument.required("value", Text.of("v", "v"))).executes(c -> { }))
                .subcommand(Command.builder("list").description(Text.of("Lists.", "Listet.")).executes(c -> { }))
                .build());
        CommandRegistry registry = console.commands();
        assertTrue(registry.isSensitive("secret set abc"));
        assertTrue(registry.isSensitive("  secret set 'abc"), "also with a syntax error");
        assertFalse(registry.isSensitive("secret list"));
        assertFalse(registry.isSensitive("service stop x"));
        assertFalse(registry.isSensitive(""));
    }
}
