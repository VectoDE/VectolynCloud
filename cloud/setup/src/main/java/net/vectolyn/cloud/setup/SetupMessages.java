package net.vectolyn.cloud.setup;

import java.util.Locale;
import net.vectolyn.cloud.console.Text;

/**
 * Texts of the setup assistant in English and German. They never repeat a value the operator typed,
 * and none of them starts with "No" or "Not": each says what to do instead.
 */
public final class SetupMessages {

    private SetupMessages() {
    }

    /** The EULA, linked in the question and in the setup file error. */
    public static final String EULA_URL = "https://aka.ms/MinecraftEULA";

    // ---- start ------------------------------------------------------------------------------------

    /** Heading on the first start. */
    public static final Text TITLE = Text.of("VectolynCloud setup", "VectolynCloud einrichten");
    /** First start: how it works. */
    public static final Text INTRO = Text.of(
            "A few questions, then the network is ready. Enter keeps the value in brackets. Type !cancel to stop;"
                    + " nothing is saved until you confirm at the end.",
            "Ein paar Fragen, dann steht das Netzwerk. Enter übernimmt den Wert in Klammern. Mit !cancel brichst du ab;"
                    + " gespeichert wird erst, wenn du am Ende bestätigst.");
    /** Running again: how it works. */
    public static final Text INTRO_AGAIN = Text.of(
            "The current settings are filled in. Only what you change is written. Type !cancel to stop.",
            "Die aktuellen Einstellungen sind vorbelegt. Geschrieben wird nur, was du änderst. Mit !cancel brichst du ab.");

    // ---- questions --------------------------------------------------------------------------------

    /** Language question, in both languages because the language is not known yet. */
    public static final Text LANGUAGE = Text.of("Language / Sprache", "Sprache / Language");
    /** EULA explanation. */
    public static final Text EULA_HELP = Text.of(
            "Minecraft servers may only run if you accept the Minecraft EULA: " + EULA_URL,
            "Minecraft-Server dürfen nur laufen, wenn du die Minecraft-EULA akzeptierst: " + EULA_URL);
    /** EULA question. */
    public static final Text EULA = Text.of("Do you accept the Minecraft EULA?", "Akzeptierst du die Minecraft-EULA?");
    /** EULA declined. */
    public static final Text EULA_DECLINED = Text.of(
            "Without the EULA the cloud cannot start any server, so the setup stops here. Nothing was saved."
                    + " Start again once you agree.",
            "Ohne die EULA startet die Cloud keinen Server, deshalb endet die Einrichtung hier. Gespeichert wurde nichts."
                    + " Starte neu, sobald du zustimmst.");
    /** Network name question. */
    public static final Text NETWORK_NAME = Text.of("Network name", "Name des Netzwerks");
    /** Server heading. */
    public static final Text SERVERS = Text.of(
            "Which servers do you want? The cloud only creates and starts these.",
            "Welche Server willst du? Die Cloud legt nur diese an und startet nur sie.");
    /** One server question; %1$s is its name. */
    public static final Text SERVER_WANTED = Text.of("%1$s?", "%1$s?");
    /** One minigame group; %1$s is the group. */
    public static final Text MINIGAME_GROUP_WANTED = Text.of("Minigame group %1$s?", "Minigame-Gruppe %1$s?");
    /** Proxy switched off. */
    public static final Text PROXY_OFF = Text.of(
            "Without a proxy, players cannot join. That only fits if another node runs it.",
            "Ohne Proxy kommt kein Spieler ins Netzwerk. Das passt nur, wenn ein anderer Node ihn betreibt.");
    /** Network heading. */
    public static final Text NETWORK = Text.of("Addresses and ports", "Adressen und Ports");
    /** Pterodactyl hint about ports. */
    public static final Text PTERODACTYL_PORTS = Text.of(
            "On Pterodactyl every port needs an allocation in the panel. The Java port is preset to the assigned one.",
            "Auf Pterodactyl braucht jeder Port eine Allocation im Panel. Als Java-Port ist der zugewiesene vorbelegt.");
    /** Bind question. */
    public static final Text BIND = Text.of("Bind address (0.0.0.0 = every address)", "Bind-Adresse (0.0.0.0 = alle Adressen)");
    /** Java port question. */
    public static final Text JAVA_PORT = Text.of("Java port", "Java-Port");
    /** Bedrock port question. */
    public static final Text BEDROCK_PORT = Text.of("Bedrock port (UDP)", "Bedrock-Port (UDP)");
    /** Legacy port question. */
    public static final Text LEGACY_PORT = Text.of("Port of the legacy proxy", "Port des Legacy-Proxys");
    /** A port that is already used. */
    public static final Text PORT_TAKEN = Text.of(
            "Choose a port that neither another answer nor the cloud itself uses (%1$s).",
            "Wähle einen Port, den weder eine andere Antwort noch die Cloud selbst belegt (%1$s).");
    /** Memory heading. */
    public static final Text MEMORY = Text.of(
            "Memory per server: the maximum heap of one service, for example 1G or 1536M.",
            "Arbeitsspeicher pro Server: der maximale Heap eines Dienstes, zum Beispiel 1G oder 1536M.");
    /** Memory question; %1$s is the server, %2$s the group. */
    public static final Text MEMORY_OF = Text.of("%1$s (group %2$s)", "%1$s (Gruppe %2$s)");
    /** Total memory. */
    public static final Text MEMORY_TOTAL = Text.of("Together, with one service each: %1$s.",
            "Zusammen, bei je einem Dienst: %1$s.");
    /** More than the machine has. */
    public static final Text MEMORY_ABOVE_MACHINE = Text.of(
            "That is more than the %1$s this machine has. Lower it, or plan for fewer servers at once.",
            "Das ist mehr als die %1$s, die diese Maschine hat. Senk den Wert oder lass weniger Server gleichzeitig laufen.");
    /** More than the Pterodactyl limit. */
    public static final Text MEMORY_ABOVE_PTERODACTYL = Text.of(
            "That is more than the %1$s this Pterodactyl server may use. Raise the limit in the panel or lower the values.",
            "Das ist mehr als die %1$s, die dieser Pterodactyl-Server nutzen darf. Erhöh das Limit im Panel oder senk die Werte.");
    /** Services heading. */
    public static final Text SERVICES = Text.of(
            "Services per server, as static/min/max. Static services keep their worlds and always run;"
                    + " above them the cloud adds dynamic ones by player count, fresh from the template, up to max.",
            "Dienste pro Server, als statisch/min/max. Statische Dienste behalten ihre Welten und laufen immer;"
                    + " darüber startet die Cloud nach Spielerzahl dynamische, frisch aus dem Template, bis max.");
    /** Services example. */
    public static final Text SERVICES_HELP = Text.of(
            "1/1/1: one static server, never scaled. 1/1/4: one static, up to 3 more dynamic ones."
                    + " 0/1/8: only dynamic, one always ready, at most 8.",
            "1/1/1: ein statischer Server, nie skaliert. 1/1/4: einer statisch, bis zu 3 weitere dynamisch."
                    + " 0/1/8: nur dynamisch, einer immer bereit, höchstens 8.");
    /** Services question; %1$s is the server, %2$s the task. */
    public static final Text SERVICES_OF = Text.of("%1$s (task %2$s), static/min/max", "%1$s (Task %2$s), statisch/min/max");
    /** Advanced settings question. */
    public static final Text ADVANCED = Text.of(
            "Advanced settings (drain time per group, memory of this node)?",
            "Erweiterte Einstellungen (Abbauzeit pro Gruppe, Speicher dieses Nodes)?");
    /** Advanced settings heading. */
    public static final Text ADVANCED_HEADING = Text.of("Advanced settings", "Erweiterte Einstellungen");
    /** Advanced settings: how Enter works. */
    public static final Text ADVANCED_HELP = Text.of(
            "Enter keeps the value in brackets. \"default\" goes back to the default, \"auto\" lets the node work out its memory.",
            "Enter übernimmt den Wert in Klammern. \"default\" stellt den Standard wieder her, \"auto\" lässt den Node seinen Speicher selbst bestimmen.");
    /** Drain timeout question; %1$s the group. */
    public static final Text DRAIN_TIMEOUT_OF = Text.of(
            "Drain time of %1$s (5s to 10m, players move before the scaler stops a service)",
            "Abbauzeit von %1$s (5s bis 10m, so lange wechseln Spieler, bevor der Scaler einen Dienst stoppt)");
    /** Max memory question; %1$s the node. */
    public static final Text MAX_MEMORY_OF = Text.of(
            "Memory node %1$s may give its services (at least 512M, or auto)",
            "Speicher, den Node %1$s seinen Diensten geben darf (mindestens 512M, oder auto)");
    /** Reserve for the system; %1$s the node. */
    public static final Text RESERVE_SYSTEM_OF = Text.of(
            "Memory node %1$s keeps for the system (1M to 64G)",
            "Speicher, den Node %1$s für das System freihält (1M bis 64G)");
    /** Reserve for the node itself; %1$s the node. */
    public static final Text RESERVE_NODE_OVERHEAD_OF = Text.of(
            "Memory node %1$s keeps for itself (1M to 64G)",
            "Speicher, den Node %1$s für sich selbst freihält (1M bis 64G)");
    /** Reserve for the database; %1$s the node. */
    public static final Text RESERVE_DATABASE_OF = Text.of(
            "Memory node %1$s keeps for the database it runs (1M to 64G)",
            "Speicher, den Node %1$s für die eigene Datenbank freihält (1M bis 64G)");
    /** Database heading. */
    public static final Text DATABASE = Text.of("Database (LuckPerms, later the Vectolyn plugin)",
            "Datenbank (LuckPerms, später das Vectolyn-Plugin)");
    /** Database mode question. */
    public static final Text DATABASE_MODE = Text.of("Which database?", "Welche Datenbank?");
    /** Managed option. */
    public static final Text MODE_MANAGED = Text.of("MariaDB, downloaded and run by the cloud",
            "MariaDB, lädt und betreibt die Cloud selbst");
    /** Managed option where MariaDB has no build. */
    public static final Text MODE_MANAGED_FALLBACK = Text.of(
            "MariaDB, downloaded and run by the cloud (on this system it uses H2 instead)",
            "MariaDB, lädt und betreibt die Cloud selbst (auf diesem System nutzt sie stattdessen H2)");
    /** Docker option. */
    public static final Text MODE_DOCKER = Text.of("MariaDB in Docker (you start the container)",
            "MariaDB in Docker (den Container startest du)");
    /** External option. */
    public static final Text MODE_EXTERNAL = Text.of("Your own MariaDB or MySQL server", "Eigener MariaDB- oder MySQL-Server");
    /** H2 option. */
    public static final Text MODE_H2 = Text.of("H2 files per server (tests, nothing is shared)",
            "H2-Dateien pro Server (für Tests, nichts wird geteilt)");
    /** Docker left out on Pterodactyl. */
    public static final Text DOCKER_HIDDEN = Text.of(
            "Docker is left out: on Pterodactyl the cloud already runs inside a container.",
            "Docker fehlt in der Liste: Auf Pterodactyl läuft die Cloud schon in einem Container.");
    /** External host. */
    public static final Text DB_HOST = Text.of("Database host", "Datenbank-Host");
    /** External port. */
    public static final Text DB_PORT = Text.of("Database port", "Datenbank-Port");
    /** External database name. */
    public static final Text DB_NAME = Text.of("Database name", "Name der Datenbank");
    /** External user. */
    public static final Text DB_USER = Text.of("Database user", "Datenbank-Benutzer");
    /** External password. */
    public static final Text DB_PASSWORD = Text.of("Password of the database user", "Passwort des Datenbank-Benutzers");
    /** The console shows secrets: explain the file. %1$s is the file. */
    public static final Text PASSWORD_VISIBLE = Text.of(
            "This console shows what you type. Better put the password into %1$s as the line"
                    + " VECTOLYN_DB_PASSWORD=<password> and save the file.",
            "Diese Konsole zeigt, was du tippst. Trag das Passwort besser in %1$s ein, als Zeile"
                    + " VECTOLYN_DB_PASSWORD=<Passwort>, und speichere die Datei.");
    /** How to enter the password. */
    public static final Text PASSWORD_HOW = Text.of("How do you want to enter the password?", "Wie willst du das Passwort eingeben?");
    /** Option: file. */
    public static final Text PASSWORD_BY_FILE = Text.of("I put it into the file (recommended)", "Ich trage es in die Datei ein (empfohlen)");
    /** Option: type it. */
    public static final Text PASSWORD_BY_TYPING = Text.of("Type it here anyway (stays visible)", "Trotzdem hier eintippen (bleibt sichtbar)");
    /** Option: keep what the file has. */
    public static final Text PASSWORD_KEEP = Text.of("Keep the password already in the file", "Das Passwort in der Datei behalten");
    /** Waiting for the file. */
    public static final Text PASSWORD_FILE_SAVED = Text.of("File saved?", "Datei gespeichert?");
    /** Found it. */
    public static final Text PASSWORD_FOUND = Text.of("Found the password in the file.", "Passwort in der Datei gefunden.");
    /** Still missing. */
    public static final Text PASSWORD_STILL_MISSING = Text.of(
            "The file has no VECTOLYN_DB_PASSWORD line with a value yet.",
            "In der Datei steht noch keine Zeile VECTOLYN_DB_PASSWORD mit Wert.");
    /** File broken; %1$s is the line. */
    public static final Text SECRETS_UNREADABLE = Text.of(
            "secrets/vectolyn.env breaks the format in line %1$s. Fix that line and save again.",
            "secrets/vectolyn.env verletzt in Zeile %1$s das Format. Korrigier die Zeile und speichere erneut.");
    /** File cannot be read at all. */
    public static final Text SECRETS_ACCESS = Text.of(
            "secrets/vectolyn.env cannot be read. Check that it is a plain file the cloud's user may read.",
            "secrets/vectolyn.env lässt sich nicht lesen. Prüf, ob es eine normale Datei ist, die der Benutzer der Cloud lesen darf.");

    // ---- summary ----------------------------------------------------------------------------------

    /** Summary heading. */
    public static final Text SUMMARY = Text.of("Summary", "Zusammenfassung");
    /** Label. */
    public static final Text S_LANGUAGE = Text.of("Language", "Sprache");
    /** Label. */
    public static final Text S_EULA = Text.of("Minecraft EULA", "Minecraft-EULA");
    /** Value. */
    public static final Text S_ACCEPTED = Text.of("accepted", "akzeptiert");
    /** Label. */
    public static final Text S_NETWORK = Text.of("Network name", "Netzwerkname");
    /** Label. */
    public static final Text S_WANTED = Text.of("Servers", "Server");
    /** Label. */
    public static final Text S_OFF = Text.of("Switched off", "Ausgeschaltet");
    /** Value for an empty list. */
    public static final Text S_NONE = Text.of("(none)", "(keine)");
    /** Label. */
    public static final Text S_ADDRESS = Text.of("Address", "Adresse");
    /** Label. */
    public static final Text S_PORTS = Text.of("Ports", "Ports");
    /** Label. */
    public static final Text S_MEMORY = Text.of("Memory", "Arbeitsspeicher");
    /** Label. */
    public static final Text S_SERVICES = Text.of("Services", "Dienste");
    /** After the counts in the summary. */
    public static final Text S_SERVICES_FORM = Text.of("(static/min/max)", "(statisch/min/max)");
    /** Label. */
    public static final Text S_DATABASE = Text.of("Database", "Datenbank");
    /** Managed; %1$s host:port. */
    public static final Text S_DB_MANAGED = Text.of("MariaDB run by the cloud on %1$s", "MariaDB, betrieben von der Cloud, auf %1$s");
    /** Docker; %1$s image, %2$s host:port. */
    public static final Text S_DB_DOCKER = Text.of("MariaDB in Docker (%1$s) on %2$s", "MariaDB in Docker (%1$s) auf %2$s");
    /** External; %1$s host:port, %2$s database, %3$s user. */
    public static final Text S_DB_EXTERNAL = Text.of("%1$s, database %2$s, user %3$s", "%1$s, Datenbank %2$s, Benutzer %3$s");
    /** H2. */
    public static final Text S_DB_H2 = Text.of("H2 files per server", "H2-Dateien pro Server");
    /** Label. */
    public static final Text S_SECRETS = Text.of("Secrets", "Secrets");
    /** Secrets value. */
    public static final Text S_SECRETS_VALUE = Text.of(
            "created when saving, kept in secrets/vectolyn.env, never shown",
            "werden beim Speichern erzeugt, liegen in secrets/vectolyn.env, werden nie angezeigt");
    /** Extra: new database password. */
    public static final Text S_PASSWORD_NEW = Text.of("; database password: new (hidden)", "; Datenbank-Passwort: neu (verdeckt)");
    /** Label. */
    public static final Text S_FILES = Text.of("Files to write", "Zu schreibende Dateien");
    /** Nothing changes. */
    public static final Text S_UNCHANGED = Text.of(
            "Everything stays as it is; there is nothing to write.",
            "Alles bleibt, wie es ist; es gibt nichts zu schreiben.");
    /** Problems before saving. */
    public static final Text S_PROBLEMS = Text.of("Please fix this first:", "Bitte erst das korrigieren:");
    /** Confirm question. */
    public static final Text CONFIRM = Text.of("Save this?", "So speichern?");
    /** Option. */
    public static final Text CONFIRM_SAVE = Text.of("Save", "Speichern");
    /** Option. */
    public static final Text CONFIRM_CHANGE = Text.of("Change answers", "Antworten ändern");
    /** Option. */
    public static final Text CONFIRM_CANCEL = Text.of("Cancel without saving", "Abbrechen, ohne zu speichern");

    // ---- results ----------------------------------------------------------------------------------

    /** Saved; %1$s the files. */
    public static final Text SAVED = Text.of("Saved: %1$s", "Gespeichert: %1$s");
    /** Done, first time. */
    public static final Text COMPLETE = Text.of(
            "The setup is complete. The cloud now creates and starts the wanted servers.",
            "Die Einrichtung ist abgeschlossen. Die Cloud legt jetzt die gewollten Server an und startet sie.");
    /** Done, again. */
    public static final Text CHANGED = Text.of(
            "The changes are saved. \"setup\" opens these questions again at any time.",
            "Die Änderungen sind gespeichert. „setup“ öffnet diese Fragen jederzeit wieder.");
    /** After a change: when a server switched on starts. */
    public static final Text WANTED_STARTS_LATER = Text.of(
            "A server switched on here starts with \"service start <group>\" or at the next start of the cloud;"
                    + " a server switched off stops right away.",
            "Ein hier eingeschalteter Server startet mit „service start <Gruppe>“ oder beim nächsten Start der Cloud;"
                    + " ein ausgeschalteter Server stoppt sofort.");
    /** After choosing managed: what servers.database means then. */
    public static final Text MANAGED_DATABASE_SWITCH = Text.of(
            "With managed, servers.database in cloud.yml decides: true runs MariaDB, false uses H2 files per server.",
            "Bei managed entscheidet servers.database in cloud.yml: true betreibt MariaDB, false nutzt H2-Dateien pro Server.");
    /** Description of the setup command. */
    public static final Text COMMAND_DESCRIPTION = Text.of("Runs the setup assistant again to change the settings.",
            "Startet den Einrichtungsassistenten erneut, um die Einstellungen zu ändern.");
    /** Language option. */
    public static final Text LANGUAGE_DE = Text.of("Deutsch", "Deutsch");
    /** Language option. */
    public static final Text LANGUAGE_EN = Text.of("English", "English");
    /** Label of a reserved port in the port hint. */
    public static final Text PORT_CLOUD_CHANNEL = Text.of("cloud channel", "Cloud-Kanal");
    /** Label of the service port range in the port hint. */
    public static final Text PORT_SERVICES = Text.of("services", "Dienste");
    /** Cancelled. */
    public static final Text CANCELLED = Text.of("Setup cancelled. Nothing was saved.", "Einrichtung abgebrochen. Gespeichert wurde nichts.");
    /** Input ended. */
    public static final Text INPUT_ENDED = Text.of(
            "The input ended before the setup was done, so nothing was saved. Without a console,"
                    + " pass the answers with --setup-file (see docs/cloud/setup.md).",
            "Die Eingabe endete vor dem Ende der Einrichtung, gespeichert wurde nichts. Ohne Konsole"
                    + " übergibst du die Antworten mit --setup-file (siehe docs/cloud/setup.md).");
    /** Too many invalid answers. */
    public static final Text TOO_MANY_ATTEMPTS = Text.of(
            "Too many invalid answers in a row, so the setup stopped. Nothing was saved.",
            "Zu viele ungültige Antworten hintereinander, deshalb hat die Einrichtung angehalten. Gespeichert wurde nichts.");
    /** Writing failed; %1$s the reason. */
    public static final Text WRITE_FAILED = Text.of(
            "Saving failed: %1$s. The setup stays open and runs again on the next start.",
            "Speichern fehlgeschlagen: %1$s. Die Einrichtung bleibt offen und läuft beim nächsten Start erneut.");

    // ---- who can read the secrets ----------------------------------------------------------------

    /** Windows; %1$s owner, %2$s the administrators group. */
    public static final Text ACCESS_WINDOWS = Text.of(
            "Who can read the secrets: only %1$s has access to secrets/vectolyn.env. Members of %2$s can still take"
                    + " ownership and then read it; Windows always allows administrators that.",
            "Wer die Secrets lesen kann: Auf secrets/vectolyn.env hat nur %1$s Zugriff. Mitglieder von %2$s können die"
                    + " Datei trotzdem übernehmen und dann lesen; das erlaubt Windows Administratoren immer.");
    /** POSIX; %1$s owner. */
    public static final Text ACCESS_POSIX = Text.of(
            "Who can read the secrets: secrets/vectolyn.env belongs to %1$s and only that user may read it (mode 600)."
                    + " root can read it anyway.",
            "Wer die Secrets lesen kann: secrets/vectolyn.env gehört %1$s, und nur dieser Benutzer darf sie lesen"
                    + " (Modus 600). root kann sie trotzdem lesen.");
    /** A file system without access rights. */
    public static final Text ACCESS_OPEN = Text.of(
            "Who can read the secrets: this file system has no access rights (FAT or some network shares), so anyone"
                    + " who can open the folder can read secrets/vectolyn.env. Put the cloud home on NTFS, ext4, XFS or APFS"
                    + " to restrict it.",
            "Wer die Secrets lesen kann: Dieses Dateisystem kennt keine Zugriffsrechte (FAT oder manche Netzlaufwerke),"
                    + " also kann jeder, der den Ordner öffnen kann, secrets/vectolyn.env lesen. Leg das Cloud-Home auf NTFS,"
                    + " ext4, XFS oder APFS, um das einzuschränken.");

    // ---- setup file -------------------------------------------------------------------------------

    /** Neither questions nor a file. */
    public static final Text NEEDS_ANSWERS = Text.of(
            "The setup has to run first, and this start may not ask questions (--non-interactive)."
                    + " Pass the answers with --setup-file <file>; docs/cloud/setup.md has an example.",
            "Zuerst muss die Einrichtung laufen, und dieser Start darf nichts fragen (--non-interactive)."
                    + " Übergib die Antworten mit --setup-file <Datei>; ein Beispiel steht in docs/cloud/setup.md.");
    /** File problems; %1$s the file. */
    public static final Text FILE_PROBLEMS = Text.of(
            "The setup file %1$s has problems, so nothing was saved:",
            "Die Setup-Datei %1$s hat Fehler, deshalb wurde nichts gespeichert:");
    /** File cannot be read. */
    public static final Text FILE_UNREADABLE = Text.of(
            "The setup file cannot be read. Check the path and that the cloud's user may read it.",
            "Die Setup-Datei lässt sich nicht lesen. Prüf den Pfad und ob der Benutzer der Cloud sie lesen darf.");
    /** EULA missing in the file. */
    public static final Text FILE_EULA = Text.of(
            "Set eula: true to accept the Minecraft EULA (" + EULA_URL + "). Without it the cloud starts no server.",
            "Setz eula: true, um die Minecraft-EULA zu akzeptieren (" + EULA_URL + "). Ohne sie startet die Cloud keinen Server.");
    /** Unknown group under memory; %1$s the group. */
    public static final Text FILE_UNKNOWN_MEMORY_GROUP = Text.of(
            "memory: %1$s is neither an existing group nor a group the setup creates.",
            "memory: %1$s ist weder eine vorhandene Gruppe noch eine, die die Einrichtung anlegt.");
    /** Unknown group under drain-timeout; %1$s the group. */
    public static final Text FILE_UNKNOWN_DRAIN_GROUP = Text.of(
            "drain-timeout: %1$s is neither an existing group nor a group the setup creates.",
            "drain-timeout: %1$s ist weder eine vorhandene Gruppe noch eine, die die Einrichtung anlegt.");
    /** node.max-memory that is neither auto nor a size. */
    public static final Text FILE_NODE_MAX_MEMORY = Text.of(
            "node.max-memory: write auto or a size like 8G.", "node.max-memory: schreib auto oder eine Größe wie 8G.");
    /** Unknown task under services; %1$s the task. */
    public static final Text FILE_UNKNOWN_SERVICES_TASK = Text.of(
            "services: %1$s is neither an existing task nor one the setup creates (the proxies always run one service).",
            "services: %1$s ist weder ein vorhandener Task noch einer, den die Einrichtung anlegt (die Proxys laufen immer mit einem Dienst).");
    /** A minigame entry without value; %1$s the group. */
    public static final Text FILE_MINIGAME_VALUE = Text.of(
            "minigames: %1$s needs true or false.", "minigames: %1$s braucht true oder false.");
    /** Password file unreadable. */
    public static final Text FILE_PASSWORD_UNREADABLE = Text.of(
            "database.password-file cannot be read. Check the path and the rights.",
            "database.password-file lässt sich nicht lesen. Prüf Pfad und Rechte.");
    /** Password file without a usable value. */
    public static final Text FILE_PASSWORD_FORM = Text.of(
            "database.password-file must hold the password on one line, without spaces at either end.",
            "database.password-file muss das Passwort in einer Zeile enthalten, ohne Leerzeichen am Anfang oder Ende.");
    /** Taken from the file; %1$s the file. */
    public static final Text FILE_APPLIED = Text.of("Setup taken from %1$s.", "Einrichtung aus %1$s übernommen.");

    // ---- checks -----------------------------------------------------------------------------------

    /** Network name rule. */
    public static final Text CHECK_NETWORK_NAME = Text.of(
            "Use 1 to 32 letters, digits, spaces or . _ -, starting with a letter or digit.",
            "Erlaubt sind 1 bis 32 Buchstaben, Ziffern, Leerzeichen oder . _ -, am Anfang ein Buchstabe oder eine Ziffer.");
    /** Host rule. */
    public static final Text CHECK_HOST = Text.of(
            "Enter an IP address or host name (letters, digits, . : - and [ ] for IPv6).",
            "Gib eine IP-Adresse oder einen Hostnamen ein (Buchstaben, Ziffern, . : - und [ ] für IPv6).");
    /** Identifier rule. */
    public static final Text CHECK_IDENTIFIER = Text.of("Use 1 to 64 letters, digits or _.", "Erlaubt sind 1 bis 64 Buchstaben, Ziffern oder _.");
    /** Port rule. */
    public static final Text CHECK_PORT = Text.of("Enter a port from 1 to 65535.", "Gib einen Port von 1 bis 65535 ein.");
    /** Ports must differ. */
    public static final Text CHECK_PORTS_DIFFER = Text.of(
            "Java, Bedrock and legacy port must all differ.", "Java-, Bedrock- und Legacy-Port müssen sich unterscheiden.");
    /** Legacy server without proxy. */
    public static final Text CHECK_LEGACY_NEEDS_PROXY = Text.of(
            "The 1.8.8 server needs the legacy proxy; switch it on as well.",
            "Der 1.8.8-Server braucht den Legacy-Proxy; schalte ihn mit ein.");
    /** Heap rule. */
    public static final Text CHECK_HEAP = Text.of("At least 256M, written like 512M or 2G.", "Mindestens 256M, geschrieben wie 512M oder 2G.");
    /** Heap rule for one group; %1$s the group. */
    public static final Text CHECK_HEAP_OF = Text.of("Memory of %1$s: at least 256M.", "Arbeitsspeicher von %1$s: mindestens 256M.");
    /** Service count rule. */
    public static final Text CHECK_SERVICES = Text.of(
            "Write static/min/max, for example 1/1/4: static at most min, min at most max, max from 1 to 1000.",
            "Schreib statisch/min/max, zum Beispiel 1/1/4: statisch höchstens min, min höchstens max, max von 1 bis 1000.");
    /** Service count rule for one task; %1$s the task. */
    public static final Text CHECK_SERVICES_OF = Text.of(
            "Services of %1$s: static at most min, min at most max, max from 1 to 1000.",
            "Dienste von %1$s: statisch höchstens min, min höchstens max, max von 1 bis 1000.");
    /** Unknown group under minigames; %1$s the group. */
    public static final Text CHECK_UNKNOWN_GROUP = Text.of(
            "minigames: %1$s is no existing group.", "minigames: %1$s ist keine vorhandene Gruppe.");
    /** Docker on Pterodactyl. */
    public static final Text CHECK_DOCKER_ON_PTERODACTYL = Text.of(
            "Docker is unavailable on Pterodactyl. Pick managed, external or h2.",
            "Docker gibt es auf Pterodactyl nicht. Wähle managed, external oder h2.");
    /** Password missing. */
    public static final Text CHECK_PASSWORD_MISSING = Text.of(
            "An external database needs the password of its user: VECTOLYN_DB_PASSWORD in secrets/vectolyn.env,"
                    + " or database.password-file in the setup file.",
            "Eine externe Datenbank braucht das Passwort ihres Benutzers: VECTOLYN_DB_PASSWORD in secrets/vectolyn.env"
                    + " oder database.password-file in der Setup-Datei.");
    /** Password form. */
    public static final Text CHECK_PASSWORD_FORM = Text.of(
            "The password must be one line without spaces at either end.",
            "Das Passwort muss eine Zeile ohne Leerzeichen am Anfang oder Ende sein.");

    /** Drain timeout rule. */
    public static final Text CHECK_DRAIN_TIMEOUT = Text.of(
            "Between 5s and 10m, written like 45s or 2m.", "Zwischen 5s und 10m, geschrieben wie 45s oder 2m.");
    /** Max memory rule. */
    public static final Text CHECK_MAX_MEMORY = Text.of(
            "At least 512M, written like 8G, or auto.", "Mindestens 512M, geschrieben wie 8G, oder auto.");
    /** Reserve rule. */
    public static final Text CHECK_RESERVE = Text.of(
            "Between 1M and 64G, written like 512M or 2G, or default.",
            "Zwischen 1M und 64G, geschrieben wie 512M oder 2G, oder default.");

    /** {@code text} with its placeholders filled, in both languages. */
    public static Text fill(Text text, Object... args) {
        return Text.of(String.format(Locale.ROOT, text.english(), args), String.format(Locale.ROOT, text.german(), args));
    }
}
