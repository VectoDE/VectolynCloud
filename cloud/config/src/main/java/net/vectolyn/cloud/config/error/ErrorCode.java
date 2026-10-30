package net.vectolyn.cloud.config.error;

import java.util.Locale;
import net.vectolyn.cloud.config.model.Language;

/**
 * Stable identifier of a configuration problem, with an English and a German message template.
 *
 * <p>The code ({@link #code()}) is part of the API: the console, the setup assistant and later the
 * REST API and the dashboard may react to it. The templates take the {@link ConfigError#args()}
 * in order ({@code %1$s}, {@code %2$s}, ...). Arguments never contain the offending value, only
 * limits, names or field paths, so an error can be logged without leaking what was typed.
 */
public enum ErrorCode {

    /** The file is not valid YAML. */
    SYNTAX("The file is not valid YAML.", "Die Datei ist kein gültiges YAML."),
    /** The file is empty or does not contain a mapping at the top level. */
    NOT_A_MAPPING("The file must contain a YAML mapping (key: value).",
            "Die Datei muss eine YAML-Zuordnung (Schlüssel: Wert) enthalten."),
    /** The file is larger than the configured limit. */
    FILE_TOO_LARGE("The file is larger than %1$s bytes.", "Die Datei ist größer als %1$s Bytes."),
    /** A required file is missing (for example {@code cloud.yml} was deleted while the cloud runs). */
    FILE_MISSING("The file is missing.", "Die Datei fehlt."),
    /** The file could not be read or written. */
    FILE_ACCESS("The file could not be accessed.", "Auf die Datei konnte nicht zugegriffen werden."),
    /** A symbolic link, junction, special file or the wrong kind (file for folder) where a real file or folder belongs. */
    NOT_REGULAR("Must be a regular file or folder, not a symbolic link, junction or special file.",
            "Muss eine normale Datei bzw. ein normaler Ordner sein, keine symbolische Verknüpfung, kein Junction und keine Spezialdatei."),
    /** The name of a group, task or node file does not match the name pattern. */
    INVALID_FILE_NAME("The file name must match %1$s.", "Der Dateiname muss %1$s entsprechen."),
    /** {@code schema-version} is missing. */
    SCHEMA_VERSION_MISSING("The field \"schema-version\" is missing.", "Das Feld „schema-version“ fehlt."),
    /** {@code schema-version} is not a supported version. */
    SCHEMA_VERSION_UNSUPPORTED("Unsupported schema-version. Supported: 1 to %1$s.",
            "Nicht unterstützte schema-version. Unterstützt: 1 bis %1$s."),
    /** A field that the model does not know, usually a typo. */
    UNKNOWN_FIELD("Unknown field \"%1$s\".", "Unbekanntes Feld „%1$s“."),
    /** A key appears twice in the same mapping. */
    DUPLICATE_FIELD("The field appears more than once.", "Das Feld kommt mehrfach vor."),
    /** The value has the wrong type or format. */
    INVALID_VALUE("Invalid value. Expected: %1$s.", "Ungültiger Wert. Erwartet: %1$s."),
    /** A required value is missing. */
    REQUIRED("A value is required.", "Ein Wert ist erforderlich."),
    /** A number lies outside its allowed range. */
    OUT_OF_RANGE("Must be between %1$s and %2$s.", "Muss zwischen %1$s und %2$s liegen."),
    /** A text does not match its pattern. */
    PATTERN_MISMATCH("Must match %1$s.", "Muss %1$s entsprechen."),
    /** A minimum is greater than its maximum. */
    MIN_GREATER_THAN_MAX("Must not be greater than %1$s.", "Darf nicht größer sein als %1$s."),
    /** More static services than {@code min-services}; static services always count toward it. */
    STATIC_ABOVE_MIN("Must not be greater than %1$s: static services always run and count toward it.",
            "Darf nicht größer sein als %1$s: statische Dienste laufen immer und zählen dazu."),
    /** {@code scale-down-below} is not below {@code scale-up-at}. */
    SCALE_DOWN_NOT_BELOW_UP("Must be lower than %1$s, otherwise a service would be started and stopped in turn.",
            "Muss kleiner sein als %1$s, sonst würde ein Dienst abwechselnd gestartet und gestoppt."),
    /** Two fixed ports on the same node are equal. */
    PORT_CONFLICT("Port already used by %1$s.", "Port wird schon von %1$s verwendet."),
    /** A fixed port lies inside the service port range of the node. */
    PORT_IN_SERVICE_RANGE("Port lies inside the service port range %1$s of node %2$s.",
            "Port liegt im Dienst-Portbereich %1$s von Node %2$s."),
    /** An entry occurs twice in a list where it must be unique. */
    DUPLICATE_ENTRY("Duplicate entry \"%1$s\".", "Doppelter Eintrag „%1$s“."),
    /** A reference to a group that does not exist. */
    UNKNOWN_GROUP("Group \"%1$s\" does not exist.", "Gruppe „%1$s“ existiert nicht."),
    /** A reference to a task that does not exist. */
    UNKNOWN_TASK("Task \"%1$s\" does not exist.", "Task „%1$s“ existiert nicht."),
    /** A reference to a node that does not exist. */
    UNKNOWN_NODE("Node \"%1$s\" does not exist.", "Node „%1$s“ existiert nicht."),
    /** A group, task or node with this name already exists. */
    ALREADY_EXISTS("\"%1$s\" already exists.", "„%1$s“ existiert bereits."),
    /** A field is set although the selected mode does not use it. */
    NOT_ALLOWED("Not allowed when %1$s.", "Nicht erlaubt, wenn %1$s."),
    /** A catalog entry kind does not fit the server software of the group. */
    CATALOG_KIND_MISMATCH("A \"%1$s\" entry does not fit software \"%2$s\".",
            "Ein Eintrag vom Typ „%1$s“ passt nicht zur Software „%2$s“."),
    /** The server software does not support this Minecraft version. */
    SOFTWARE_VERSION_MISMATCH("Software \"%1$s\" only supports Minecraft %2$s.",
            "Software „%1$s“ unterstützt nur Minecraft %2$s."),
    /** The API would listen on a public address without TLS. */
    API_TLS_REQUIRED("The API listens on a non-loopback address and therefore needs TLS.",
            "Die API lauscht auf einer Adresse außerhalb von Loopback und braucht deshalb TLS."),
    /** The setup cannot be completed before the Minecraft EULA is accepted. */
    EULA_REQUIRED("The setup can only be completed after the Minecraft EULA has been accepted.",
            "Die Einrichtung kann erst abgeschlossen werden, wenn die Minecraft-EULA akzeptiert ist."),
    /** At least one entry is required. */
    EMPTY("At least one entry is required.", "Mindestens ein Eintrag ist erforderlich."),
    /** A JVM flag that loads code, runs a command or opens remote access (see {@code JvmFlagPolicy}). */
    JVM_FLAG_FORBIDDEN("This JVM flag loads code, runs commands or opens remote access; the cloud does not set it.",
            "Dieses JVM-Flag lädt Code, führt Befehle aus oder öffnet einen Fernzugang; die Cloud setzt es nicht."),
    /** A JVM flag outside the allowlist (see {@code JvmFlagPolicy}). */
    JVM_FLAG_NOT_LISTED("This JVM flag is not on the allowlist in docs/cloud/config.md.",
            "Dieses JVM-Flag steht nicht auf der Allowlist in docs/cloud/config.md."),
    /** An origin for CORS that is not {@code http(s)://host[:port]}. */
    INVALID_ORIGIN("Must be an origin like https://dashboard.example.org or http://localhost:5173, without path.",
            "Muss ein Origin wie https://dashboard.example.org oder http://localhost:5173 sein, ohne Pfad.");

    private final String english;
    private final String german;

    ErrorCode(String english, String german) {
        this.english = english;
        this.german = german;
    }

    /** Stable, lower-case code for APIs and logs, for example {@code unknown-field}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Message template in the given language. */
    public String template(Language language) {
        return language == Language.DE ? german : english;
    }
}
