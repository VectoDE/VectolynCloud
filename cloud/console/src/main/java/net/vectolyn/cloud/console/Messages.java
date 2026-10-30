package net.vectolyn.cloud.console;

/**
 * Texts of the console framework in English and German. Texts never repeat what was typed: a
 * secret pasted at the wrong prompt must not show up in the output or a log.
 */
public final class Messages {

    private Messages() {
    }

    /** Unknown command. */
    public static final Text UNKNOWN_COMMAND = Text.of("Unknown command. Type \"help\" for a list.",
            "Unbekannter Befehl. „help“ zeigt alle Befehle.");
    /** Unknown subcommand. */
    public static final Text UNKNOWN_SUBCOMMAND = Text.of("Unknown subcommand. Usage: %1$s",
            "Unbekannter Unterbefehl. Aufruf: %1$s");
    /** Subcommand missing. */
    public static final Text MISSING_SUBCOMMAND = Text.of("A subcommand is missing. Usage: %1$s",
            "Es fehlt ein Unterbefehl. Aufruf: %1$s");
    /** Argument missing. */
    public static final Text MISSING_ARGUMENT = Text.of("Missing argument <%1$s>. Usage: %2$s",
            "Es fehlt das Argument <%1$s>. Aufruf: %2$s");
    /** Too many arguments. */
    public static final Text TOO_MANY_ARGUMENTS = Text.of("Too many arguments. Usage: %1$s",
            "Zu viele Argumente. Aufruf: %1$s");
    /** Unexpected failure. */
    public static final Text INTERNAL_ERROR = Text.of("The command failed unexpectedly; details are in the log.",
            "Der Befehl ist unerwartet fehlgeschlagen; Details stehen im Log.");
    /** File problem. */
    public static final Text FILE_ERROR = Text.of("A file could not be read or written: %1$s",
            "Eine Datei konnte nicht gelesen oder geschrieben werden: %1$s");
    /** Question cancelled. */
    public static final Text CANCELLED = Text.of("Cancelled.", "Abgebrochen.");

    /** Help: heading of the command list. */
    public static final Text HELP_HEADING = Text.of("Commands:", "Befehle:");
    /** Help: footer. */
    public static final Text HELP_FOOTER = Text.of("\"help <command>\" shows details. Tab completes commands.",
            "„help <Befehl>“ zeigt Details. Tab vervollständigt Befehle.");
    /** Help: usage line. */
    public static final Text HELP_USAGE = Text.of("Usage: %1$s", "Aufruf: %1$s");
    /** Help: aliases line. */
    public static final Text HELP_ALIASES = Text.of("Also: %1$s", "Auch: %1$s");
    /** Help: subcommand heading. */
    public static final Text HELP_SUBCOMMANDS = Text.of("Subcommands:", "Unterbefehle:");
    /** Help: argument heading. */
    public static final Text HELP_ARGUMENTS = Text.of("Arguments:", "Argumente:");

    /** Question: answer required. */
    public static final Text ANSWER_REQUIRED = Text.of("An answer is required.", "Eine Antwort ist erforderlich.");
    /** Question: not a whole number in range. */
    public static final Text ANSWER_NUMBER = Text.of("Enter a whole number from %1$d to %2$d.",
            "Bitte eine ganze Zahl von %1$d bis %2$d eingeben.");
    /** Question: yes or no. */
    public static final Text ANSWER_YES_NO = Text.of("Answer y (yes) or n (no).", "Bitte j (ja) oder n (nein) antworten.");
    /** Question: choice. */
    public static final Text ANSWER_CHOICE = Text.of("Enter a number from 1 to %1$d or one of the names shown.",
            "Bitte eine Zahl von 1 bis %1$d oder einen der angezeigten Namen eingeben.");
    /** Question: answer contains a line break or control character. */
    public static final Text ANSWER_CONTROL = Text.of("The answer must not contain control characters.",
            "Die Antwort darf keine Steuerzeichen enthalten.");
    /** Question: hint how to cancel. */
    public static final Text CANCEL_HINT = Text.of("(Ctrl+C or %1$s cancels)", "(Strg+C oder %1$s bricht ab)");
    /** Question: yes/no suffix with yes as default. */
    public static final Text YES_NO_DEFAULT_YES = Text.of("[Y/n]", "[J/n]");
    /** Question: yes/no suffix with no as default. */
    public static final Text YES_NO_DEFAULT_NO = Text.of("[y/N]", "[j/N]");
    /** Question: yes/no suffix without default. */
    public static final Text YES_NO = Text.of("[y/n]", "[j/n]");
    /** Question: warning before a secret on a console that cannot hide the input. */
    public static final Text SECRET_VISIBLE = Text.of(
            "This console cannot hide what you type. To keep the value off the screen, type %1$s and put it into secrets/vectolyn.env instead.",
            "Diese Konsole kann die Eingabe nicht verbergen. Damit der Wert nicht auf dem Bildschirm steht, mit %1$s abbrechen und ihn stattdessen in secrets/vectolyn.env eintragen.");
    /** Question: secret with default. */
    public static final Text SECRET_KEEP = Text.of("[Enter keeps the current value]", "[Enter behält den aktuellen Wert]");
    /** Question: choice prompt. */
    public static final Text CHOICE_PROMPT = Text.of("Number or name", "Nummer oder Name");
}
