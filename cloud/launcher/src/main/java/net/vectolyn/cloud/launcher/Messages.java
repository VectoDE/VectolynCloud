package net.vectolyn.cloud.launcher;

import net.vectolyn.cloud.launcher.libs.LibraryException;
import net.vectolyn.cloud.launcher.platform.OperatingSystem;

/**
 * All texts the launcher prints, in German and English. Kept in code rather than in resource
 * bundles: they are few, and the launcher must be able to print them even when its own jar is
 * damaged.
 */
public final class Messages {

    private final Language language;

    /**
     * Creates the message set for one language.
     *
     * @param language the language
     */
    public Messages(Language language) {
        this.language = language;
    }

    private boolean de() {
        return language == Language.DE;
    }

    /**
     * Message when the launcher refuses to run as root or administrator.
     *
     * @param os       operating system, selects the instructions
     * @param evidence what the detection found
     * @return the message, several lines
     */
    public String rootRefused(OperatingSystem os, String evidence) {
        String howTo = os == OperatingSystem.WINDOWS ? windowsHowTo() : unixHowTo();
        if (de()) {
            return """
                    VectolynCloud startet nicht mit Administrator- bzw. root-Rechten (%s).
                    Die Cloud startet Server, lädt Software herunter und führt sie aus. Mit vollen Rechten \
                    könnte ein Fehler oder ein manipulierter Download das ganze System treffen.

                    So startest du ohne Admin-Rechte:
                    %s
                    Wenn es wirklich nicht anders geht: --allow-root oder VECTOLYN_ALLOW_ROOT=true (nicht empfohlen).
                    """.formatted(evidence, howTo);
        }
        return """
                VectolynCloud does not start with administrator or root rights (%s).
                The cloud starts servers and downloads and runs software. With full rights, a bug or a \
                tampered download could affect the whole system.

                How to start without admin rights:
                %s
                If there really is no other way: --allow-root or VECTOLYN_ALLOW_ROOT=true (not recommended).
                """.formatted(evidence, howTo);
    }

    private String windowsHowTo() {
        if (de()) {
            return """
                      - Öffne eine normale Eingabeaufforderung oder PowerShell, nicht "Als Administrator ausführen".
                      - Starte dort: java -jar VectolynCloud.jar
                      - Als Windows-Dienst: unter einem eigenen Benutzerkonto statt LocalSystem laufen lassen.
                    """;
        }
        return """
                  - Open a normal Command Prompt or PowerShell, not "Run as administrator".
                  - Start it there: java -jar VectolynCloud.jar
                  - As a Windows service: run it under its own user account instead of LocalSystem.
                """;
    }

    private String unixHowTo() {
        if (de()) {
            return """
                      - Eigener Benutzer: sudo useradd --system --create-home vectolyn
                        und dann: sudo -u vectolyn java -jar VectolynCloud.jar
                      - systemd: in der Unit unter [Service] User=vectolyn und Group=vectolyn setzen.
                      - Docker: im Dockerfile USER 1000 setzen oder docker run --user 1000:1000 nutzen.
                      - Pterodactyl startet Server bereits ohne root; dort ist nichts zu tun.
                    """;
        }
        return """
                  - Own user: sudo useradd --system --create-home vectolyn
                    then: sudo -u vectolyn java -jar VectolynCloud.jar
                  - systemd: set User=vectolyn and Group=vectolyn under [Service] in the unit.
                  - Docker: set USER 1000 in the Dockerfile or use docker run --user 1000:1000.
                  - Pterodactyl already starts servers without root; nothing to do there.
                """;
    }

    /**
     * Warning when an elevated start was allowed with {@code --allow-root} or
     * {@code VECTOLYN_ALLOW_ROOT=true}.
     *
     * @param evidence what the detection found
     * @return the warning
     */
    public String rootAllowedWarning(String evidence) {
        if (de()) {
            return """
                    ************************************************************************
                    WARNUNG: VectolynCloud läuft mit Administrator- bzw. root-Rechten (%s).
                    Erlaubt durch --allow-root oder VECTOLYN_ALLOW_ROOT=true.
                    Jeder gestartete Server und jeder Download hat damit volle Rechte.
                    Für den Dauerbetrieb einen eigenen Benutzer ohne Admin-Rechte verwenden.
                    ************************************************************************
                    """.formatted(evidence);
        }
        return """
                ************************************************************************
                WARNING: VectolynCloud is running with administrator or root rights (%s).
                Allowed by --allow-root or VECTOLYN_ALLOW_ROOT=true.
                Every server it starts and every download gets full rights.
                For regular operation, use a dedicated user without admin rights.
                ************************************************************************
                """.formatted(evidence);
    }

    /**
     * Warning when the rights could not be determined. The launcher still starts, so the warning
     * tells anyone who did start it elevated to stop it right away.
     *
     * @param evidence why the check failed
     * @return the warning
     */
    public String elevationUnknown(String evidence) {
        if (de()) {
            return """
                    ************************************************************************
                    WARNUNG: Ob VectolynCloud mit Administrator- bzw. root-Rechten läuft,
                    ließ sich nicht ermitteln (%s).
                    Wer VectolynCloud als Administrator oder root gestartet hat, beendet es
                    jetzt sofort und startet es als normaler Benutzer neu.
                    Der Start wird fortgesetzt.
                    ************************************************************************
                    """.formatted(evidence);
        }
        return """
                ************************************************************************
                WARNING: Could not determine whether VectolynCloud is running with
                administrator or root rights (%s).
                If you started it as administrator or root, stop it now and start it
                again as a normal user.
                Startup continues.
                ************************************************************************
                """.formatted(evidence);
    }

    /**
     * Message for a wrong command line argument.
     *
     * @param e the parser error
     * @return the message, without the help text
     */
    public String argumentError(ArgumentException e) {
        String arg = e.argument();
        return switch (e.kind()) {
            case UNKNOWN -> de() ? "Unbekanntes Argument: " + arg : "Unknown argument: " + arg;
            case MISSING_VALUE -> de() ? "Zu " + arg + " fehlt der Wert." : "Missing value for " + arg + ".";
            case DUPLICATE -> de() ? arg + " wurde mehrfach angegeben." : arg + " was given more than once.";
            case UNEXPECTED_VALUE -> de() ? "Dieser Schalter nimmt keinen Wert: " + arg : "This switch takes no value: " + arg;
            case INVALID_VALUE -> de() ? "Ungültiger Wert: " + arg : "Invalid value: " + arg;
        };
    }

    /**
     * Help text for {@code --help} and after argument errors.
     *
     * @return the help text
     */
    public String help() {
        if (de()) {
            return """
                    Aufruf: java -jar VectolynCloud.jar [Optionen]

                    Optionen:
                      --home <pfad>         Cloud-Verzeichnis (Standard: aktuelles Verzeichnis)
                      --non-interactive     keine Rückfragen in der Konsole (Docker, Pterodactyl, systemd)
                      --setup-file <pfad>   Antworten für den Setup-Assistenten aus einer Datei
                      --lang de|en          Sprache der Meldungen (Standard: en)
                      --allow-root          Start mit Admin-/root-Rechten erlauben (nicht empfohlen)
                      --help                diese Hilfe
                      --version             Version anzeigen

                    Umgebungsvariable: VECTOLYN_ALLOW_ROOT=true wirkt wie --allow-root.
                    """;
        }
        return """
                Usage: java -jar VectolynCloud.jar [options]

                Options:
                  --home <path>         cloud directory (default: working directory)
                  --non-interactive     never ask on the console (Docker, Pterodactyl, systemd)
                  --setup-file <path>   answers for the setup assistant from a file
                  --lang de|en          language of messages (default: en)
                  --allow-root          allow starting with admin/root rights (not recommended)
                  --help                show this help
                  --version             show the version

                Environment variable: VECTOLYN_ALLOW_ROOT=true works like --allow-root.
                """;
    }

    /**
     * Output of {@code --version}, the same in both languages.
     *
     * @param version version of this build
     * @param javaVersion version of the running Java
     * @return one line
     */
    public String version(String version, String javaVersion) {
        return "VectolynCloud " + version + " (Java " + javaVersion + ")";
    }

    /**
     * Message when the embedded libraries cannot be used.
     *
     * @param e the error
     * @return the message
     */
    public String libraryError(LibraryException e) {
        String subject = e.subject();
        return switch (e.kind()) {
            case HASH_MISMATCH -> de()
                    ? "Die eingebettete Bibliothek " + subject + " hat nicht die erwartete SHA-256-Prüfsumme. "
                        + "VectolynCloud.jar ist beschädigt oder verändert. Lade es neu herunter und prüfe die Signatur."
                    : "The embedded library " + subject + " does not have the expected SHA-256 checksum. "
                        + "VectolynCloud.jar is damaged or modified. Download it again and verify its signature.";
            case MISSING -> de()
                    ? "Die Bibliothek " + subject + " fehlt in VectolynCloud.jar. Lade die Datei neu herunter."
                    : "The library " + subject + " is missing from VectolynCloud.jar. Download the file again.";
            case UNSAFE_NAME, INDEX_INVALID -> de()
                    ? "Das Bibliotheksverzeichnis in VectolynCloud.jar ist ungültig (" + subject + "). Lade die Datei neu herunter."
                    : "The library index in VectolynCloud.jar is invalid (" + subject + "). Download the file again.";
            case IO -> de()
                    ? "Die Bibliotheken ließen sich nicht nach storage/libs entpacken: " + e.getMessage()
                    : "Could not unpack the libraries to storage/libs: " + e.getMessage();
        };
    }

    /**
     * Message when the node cannot be started.
     *
     * @param e the error
     * @return the message
     */
    public String nodeError(NodeStartException e) {
        return switch (e.kind()) {
            case MISSING -> de()
                    ? "Dieser Build von VectolynCloud enthält noch keinen Node (" + NodeStarter.NODE_MAIN_CLASS + " fehlt)."
                    : "This build of VectolynCloud does not contain a node yet (" + NodeStarter.NODE_MAIN_CLASS + " is missing).";
            case INVALID_ENTRY -> de()
                    ? "Der Einstiegspunkt des Node ist ungültig: " + e.getMessage()
                    : "The node entry point is invalid: " + e.getMessage();
            case FAILED -> de()
                    ? "Der Node konnte nicht starten: " + describe(e.getCause())
                    : "The node failed to start: " + describe(e.getCause());
        };
    }

    private static String describe(Throwable cause) {
        return cause == null ? "?" : cause.toString();
    }
}
