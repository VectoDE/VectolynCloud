package net.vectolyn.cloud.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArgumentParserTest {

    @Test
    void noArgumentsGiveDefaults() throws ArgumentException {
        assertEquals(LauncherArguments.defaults(), ArgumentParser.parse(new String[0]));
    }

    @Test
    void allOptionsWithSeparateValues() throws ArgumentException {
        LauncherArguments args = ArgumentParser.parse(new String[] {
            "--allow-root", "--home", "srv/cloud", "--non-interactive",
            "--setup-file", "setup.yml", "--lang", "de", "--help", "--version"});
        assertTrue(args.allowRoot());
        assertEquals(Optional.of(Path.of("srv/cloud")), args.home());
        assertTrue(args.nonInteractive());
        assertEquals(Optional.of(Path.of("setup.yml")), args.setupFile());
        assertEquals(Optional.of(Language.DE), args.language());
        assertTrue(args.help());
        assertTrue(args.version());
    }

    @Test
    void valuesWithEqualsSign() throws ArgumentException {
        LauncherArguments args = ArgumentParser.parse(new String[] {"--home=data", "--lang=EN", "--setup-file=a=b.yml"});
        assertEquals(Optional.of(Path.of("data")), args.home());
        assertEquals(Optional.of(Language.EN), args.language());
        assertEquals(Optional.of(Path.of("a=b.yml")), args.setupFile());
        assertFalse(args.allowRoot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--foo", "-h", "start", "--allowroot", "-", "--"})
    void unknownArgument(String arg) {
        ArgumentException e = assertThrows(ArgumentException.class, () -> ArgumentParser.parse(new String[] {arg}));
        assertEquals(ArgumentException.Kind.UNKNOWN, e.kind());
        assertEquals(arg, e.argument());
    }

    @Test
    void missingValueAtEnd() {
        ArgumentException e = assertThrows(ArgumentException.class, () -> ArgumentParser.parse(new String[] {"--home"}));
        assertEquals(ArgumentException.Kind.MISSING_VALUE, e.kind());
    }

    @Test
    void optionIsNotTakenAsValue() {
        ArgumentException e = assertThrows(ArgumentException.class,
                () -> ArgumentParser.parse(new String[] {"--home", "--allow-root"}));
        assertEquals(ArgumentException.Kind.MISSING_VALUE, e.kind());
    }

    @Test
    void emptyInlineValue() {
        ArgumentException e = assertThrows(ArgumentException.class, () -> ArgumentParser.parse(new String[] {"--home="}));
        assertEquals(ArgumentException.Kind.MISSING_VALUE, e.kind());
    }

    @Test
    void duplicateOption() {
        ArgumentException e = assertThrows(ArgumentException.class,
                () -> ArgumentParser.parse(new String[] {"--lang", "de", "--lang=en"}));
        assertEquals(ArgumentException.Kind.DUPLICATE, e.kind());
        assertEquals("--lang", e.argument());
    }

    @Test
    void switchWithValue() {
        ArgumentException e = assertThrows(ArgumentException.class,
                () -> ArgumentParser.parse(new String[] {"--allow-root=false"}));
        assertEquals(ArgumentException.Kind.UNEXPECTED_VALUE, e.kind());
    }

    @Test
    void invalidLanguage() {
        ArgumentException e = assertThrows(ArgumentException.class,
                () -> ArgumentParser.parse(new String[] {"--lang", "fr"}));
        assertEquals(ArgumentException.Kind.INVALID_VALUE, e.kind());
    }

    @Test
    void earlyLanguageReadsLangBeforeFullParsing() {
        assertEquals(Language.DE, Language.early(new String[] {"--lang", "de"}));
        assertEquals(Language.EN, Language.early(new String[] {"--lang=en"}));
        assertEquals(Language.DE, Language.early(new String[] {"--lang", "en", "--lang=de"}));
        // English unless --lang says otherwise; the system language does not count.
        assertEquals(Language.EN, Language.early(new String[0]));
        // Invalid values are left to the parser; the default stays.
        assertEquals(Language.EN, Language.early(new String[] {"--lang", "xx"}));
    }
}
