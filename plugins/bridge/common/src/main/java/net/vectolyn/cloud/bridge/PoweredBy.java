package net.vectolyn.cloud.bridge;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import net.vectolyn.protocol.ServiceEnvironment;

/**
 * {@code /version} on the proxies ({@code branding.powered-by} in {@code cloud.yml}, default on).
 * The node passes the switch in {@value ServiceEnvironment#POWERED_BY} and its own version in
 * {@value ServiceEnvironment#CLOUD_VERSION}; while the switch is on, the Velocity bridge registers
 * {@value #COMMAND} and answers with two lines:
 *
 * <pre>
 * This network runs VectolynCloud 0.2.0
 * Open source (GPL-3.0): github.com/VectoDE/VectolynCloud
 * </pre>
 *
 * German for a client set to German, English for every other language and for the console, as in
 * {@link TransferMessages}. No Velocity type here; the bridge adds colours and the link.
 */
public final class PoweredBy {

    /** The proxy command. */
    public static final String COMMAND = "version";

    /** Address of the source code as the players read it. */
    public static final String REPOSITORY = "github.com/VectoDE/VectolynCloud";

    /** Address of the source code the link opens. */
    public static final String REPOSITORY_URL = "https://" + REPOSITORY;

    /** Version shown when the node passed none or one that is no version. */
    public static final String UNKNOWN_VERSION = "dev";

    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}");

    /**
     * One line of the answer: a gray label and the part that stands out (the version in cyan, the
     * address in white).
     *
     * @param label the label, ending in a space
     * @param value the highlighted part
     */
    public record Line(String label, String value) {

        /** Checks the parts. */
        public Line {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
        }

        /** Label and value as plain text. */
        public String text() {
            return label + value;
        }
    }

    private PoweredBy() {
    }

    /**
     * Whether {@value #COMMAND} is answered. {@code true} without a value (the cloud's default) and
     * for {@code true}; {@code false} for {@code false} and every other value.
     *
     * @param environment reads an environment variable, {@code System::getenv}
     * @return whether the command is registered
     */
    public static boolean enabled(UnaryOperator<String> environment) {
        String value = environment.apply(ServiceEnvironment.POWERED_BY);
        if (value == null || value.isBlank()) {
            return true;
        }
        return Boolean.parseBoolean(value.strip());
    }

    /**
     * The cloud's version from {@value ServiceEnvironment#CLOUD_VERSION}; {@value #UNKNOWN_VERSION}
     * if it is missing or contains anything but letters, digits and {@code . + _ -}, so a player
     * never reads an unchecked value.
     *
     * @param environment reads an environment variable, {@code System::getenv}
     * @return the version to show
     */
    public static String version(UnaryOperator<String> environment) {
        String value = environment.apply(ServiceEnvironment.CLOUD_VERSION);
        if (value == null) {
            return UNKNOWN_VERSION;
        }
        String stripped = value.strip();
        return VERSION.matcher(stripped).matches() ? stripped : UNKNOWN_VERSION;
    }

    /**
     * The answer to {@value #COMMAND}.
     *
     * @param version the cloud's version, see {@link #version(UnaryOperator)}
     * @param locale  the client's language, or {@code null} for the console and an unknown language
     * @return the version line, then the source code line
     */
    public static List<Line> lines(String version, Locale locale) {
        Objects.requireNonNull(version, "version");
        boolean german = locale != null && "de".equals(locale.getLanguage());
        return List.of(
                new Line(german ? "Dieses Netzwerk läuft mit " : "This network runs ", "VectolynCloud " + version),
                new Line(german ? "Open Source (GPL-3.0): " : "Open source (GPL-3.0): ", REPOSITORY));
    }
}
