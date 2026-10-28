package net.vectora.cloud.console.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.console.Console;
import net.vectora.cloud.console.FakeTerminal;
import net.vectora.cloud.console.Text;
import net.vectora.cloud.console.terminal.ConsoleTerminal.LineKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrompterTest {

    private FakeTerminal terminal;
    private Prompter prompter;
    private final AtomicReference<Language> language = new AtomicReference<>(Language.EN);

    @BeforeEach
    void setUp() {
        terminal = new FakeTerminal();
        prompter = Console.builder().terminal(terminal).language(language::get).build().prompter();
    }

    @Test
    void textWithDefaultAndValidation() throws Exception {
        Question<String> name = Question.text(Text.of("Network name", "Netzwerkname")).defaultValue("Vectora")
                .validate(value -> value.length() <= 8 ? null : Text.of("At most 8 characters.", "Höchstens 8 Zeichen."));
        terminal.type("");
        assertEquals("Vectora", prompter.ask(name), "Enter takes the default");
        assertEquals("Network name [Vectora]: ", terminal.reads().getLast().prompt());
        terminal.type("far too long a name", "  Mine  ");
        assertEquals("Mine", prompter.ask(name), "invalid first, then stripped");
        assertTrue(terminal.output().contains("At most 8 characters."));
        assertFalse(terminal.output().contains("far too long"), "the rejected answer is not repeated");
        assertTrue(terminal.reads().stream().allMatch(r -> r.kind() == LineKind.ANSWER));
    }

    @Test
    void requiredTextWithoutDefaultAsksAgain() throws Exception {
        terminal.type("", "  ", "x");
        assertEquals("x", prompter.ask(Question.text(Text.of("Name", "Name"))));
        assertEquals(2, terminal.printed().stream().filter(l -> l.equals("An answer is required.")).count());
    }

    @Test
    void numberInRange() throws Exception {
        Question<Integer> port = Question.number(Text.of("Proxy port", "Proxy-Port"), 1, 65535).defaultValue(25565);
        terminal.type("0", "65536", "12x", "99999999999", "+25570");
        assertEquals(25570, prompter.ask(port));
        assertEquals(4, terminal.printed().stream().filter(l -> l.equals("Enter a whole number from 1 to 65535.")).count());
        assertEquals("Proxy port (1-65535) [25565]: ", terminal.reads().getFirst().prompt());
        terminal.type("");
        assertEquals(25565, prompter.ask(port));
    }

    @Test
    void yesNoInBothLanguages() throws Exception {
        Question<Boolean> eula = Question.yesNo(Text.of("Accept the EULA?", "EULA akzeptieren?")).defaultValue(false);
        terminal.type("YES");
        assertTrue(prompter.ask(eula));
        assertEquals("Accept the EULA? [y/N]: ", terminal.reads().getLast().prompt());
        language.set(Language.DE);
        terminal.type("vielleicht", "j");
        assertTrue(prompter.ask(eula));
        assertEquals("EULA akzeptieren? [j/N]: ", terminal.reads().getLast().prompt());
        assertTrue(terminal.output().contains("Bitte j (ja) oder n (nein) antworten."));
        terminal.type("nein", "");
        assertFalse(prompter.ask(eula));
        assertFalse(prompter.ask(eula), "default");
    }

    @Test
    void choiceByNumberOrKey() throws Exception {
        Question<DatabaseMode> mode = Question.choice(Text.of("Database", "Datenbank"), List.of(
                Question.option("managed", Text.of("Managed MariaDB", "Verwaltete MariaDB"), DatabaseMode.MANAGED),
                Question.option("docker", Text.of("Docker container", "Docker-Container"), DatabaseMode.DOCKER),
                Question.option("external", Text.of("External server", "Externer Server"), DatabaseMode.EXTERNAL)))
                .defaultValue(DatabaseMode.MANAGED);
        terminal.type("2");
        assertEquals(DatabaseMode.DOCKER, prompter.ask(mode));
        assertTrue(terminal.output().contains("  1) Managed MariaDB (managed)"));
        assertTrue(terminal.output().contains("  3) External server (external)"));
        assertEquals("Number or name [managed]: ", terminal.reads().getLast().prompt().substring("Database ".length()));
        terminal.type("4", "EXTERNAL");
        assertEquals(DatabaseMode.EXTERNAL, prompter.ask(mode));
        assertTrue(terminal.output().contains("Enter a number from 1 to 3 or one of the names shown."));
        terminal.type("");
        assertEquals(DatabaseMode.MANAGED, prompter.ask(mode));
        assertThrows(IllegalArgumentException.class, () -> Question.choice(Text.of("x", "x"), List.of(
                Question.option("a", Text.of("A", "A"), 1), Question.option("A", Text.of("B", "B"), 2))));
    }

    @Test
    void secretIsReadMaskedAndNeverShown() throws Exception {
        Question<Secret> password = Question.secret(Text.of("Database password", "Datenbank-Passwort"))
                .validate(value -> value.length() >= 12 ? null : Text.of("At least 12 characters.", "Mindestens 12 Zeichen."));
        terminal.type("short", "correct horse battery");
        Secret secret = prompter.ask(password);
        assertEquals("correct horse battery", secret.reveal());
        assertEquals("***", secret.toString());
        assertTrue(terminal.reads().stream().allMatch(r -> r.kind() == LineKind.SECRET), "read as SECRET: masked, no history");
        assertTrue(terminal.output().contains("At least 12 characters."));
        assertFalse(terminal.output().contains("short"));
        assertFalse(terminal.output().contains("correct horse"));

        terminal.type("");
        Secret kept = Secret.of("existing-value-123");
        assertEquals(kept, prompter.ask(password.defaultValue(kept)));
        assertEquals("Database password [Enter keeps the current value]: ", terminal.reads().getLast().prompt());
        assertFalse(terminal.reads().getLast().prompt().contains("existing"), "a default secret is never shown");
    }

    @Test
    void cancelWithKeysWordOrEndOfInput() {
        Question<String> name = Question.text(Text.of("Name", "Name"));
        terminal.interrupt();
        assertEquals(PromptCancelledException.Reason.CANCELLED, assertThrows(PromptCancelledException.class, () -> prompter.ask(name)).reason());
        terminal.type(" !cancel ");
        assertEquals(PromptCancelledException.Reason.CANCELLED, assertThrows(PromptCancelledException.class, () -> prompter.ask(name)).reason());
        terminal.endOfInput();
        assertEquals(PromptCancelledException.Reason.END_OF_INPUT, assertThrows(PromptCancelledException.class, () -> prompter.ask(name)).reason());
        terminal.type("!cancel");
        assertThrows(PromptCancelledException.class, () -> prompter.ask(Question.secret(Text.of("S", "S"))), "also for secrets");
    }

    @Test
    void tooManyInvalidAnswersGiveUp() {
        for (int i = 0; i < Prompter.MAX_ATTEMPTS; i++) {
            terminal.type("x");
        }
        terminal.type("5");
        PromptCancelledException e = assertThrows(PromptCancelledException.class,
                () -> prompter.ask(Question.number(Text.of("N", "N"), 1, 9)));
        assertEquals(PromptCancelledException.Reason.TOO_MANY_ATTEMPTS, e.reason());
        assertEquals(1, terminal.pending(), "stops after the limit");
    }

    @Test
    void controlCharactersInAnAnswerAreRejected() throws Exception {
        terminal.type("a\u001b[2Jb", "ok");
        assertEquals("ok", prompter.ask(Question.text(Text.of("Name", "Name"))));
        assertTrue(terminal.output().contains("The answer must not contain control characters."));
    }

    @Test
    void helpLineAndCustomParser() throws Exception {
        Question<Long> megabytes = Question.custom(Text.of("Heap", "Heap"), text -> {
            if (!text.matches("[0-9]+M")) {
                throw new InvalidAnswerException(Text.of("Like 512M.", "Wie 512M."));
            }
            return Long.parseLong(text.substring(0, text.length() - 1));
        }, value -> value + "M").defaultValue(512L).help(Text.of("Memory of each server.", "Speicher je Server."));
        terminal.type("lots", "1024M");
        assertEquals(1024L, prompter.ask(megabytes));
        assertEquals("Memory of each server.", terminal.printed().getFirst());
        assertTrue(terminal.output().contains("Like 512M."));
        assertEquals("Heap [512M]: ", terminal.reads().getFirst().prompt());
    }
}
