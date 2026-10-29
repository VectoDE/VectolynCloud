package net.vectora.cloud.console.terminal;

import java.io.Console;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.jline.terminal.spi.SystemStream;
import org.jline.terminal.spi.TerminalProvider;

/**
 * Chooses how the console talks to the terminal.
 *
 * <p>Order in automatic mode:
 * <ol>
 *   <li>No terminal on stdin or stdout (Docker without {@code -t}, Pterodactyl, systemd, a pipe):
 *       {@link Mode#PLAIN}, line by line from stdin.</li>
 *   <li>JLine provider {@code ffm} on Java {@value #FFM_MIN_JAVA} and newer (Foreign Function &amp;
 *       Memory API; this is the only place that uses a Java 22+ API, in practice Java 25).</li>
 *   <li>JLine provider {@code jni}, the Java 21 path (native helper for Windows, Linux, macOS).</li>
 *   <li>JLine's {@code dumb} terminal, if no native provider loads (no line editing, but a real
 *       terminal).</li>
 * </ol>
 *
 * <p>The choice depends on the running Java version, never on which jars happen to be on the
 * class path first: on Java 21 the ffm provider is not even loaded. An operator can override it
 * with the system property {@value #PROPERTY} or the environment variable {@value #ENVIRONMENT}:
 * {@code auto} (default), {@code ffm}, {@code jni}, {@code dumb} or {@code plain}. A provider that
 * is not available falls back to the next one in the order above, with the reason in the log.
 */
public final class TerminalSelector {

    /** System property that overrides the choice. */
    public static final String PROPERTY = "vectora.console";
    /** Environment variable that overrides the choice (the property wins). */
    public static final String ENVIRONMENT = "VECTORA_CONSOLE";
    /** First Java version with the final Foreign Function &amp; Memory API. */
    public static final int FFM_MIN_JAVA = 22;

    /** JLine provider names. */
    public static final String FFM = "ffm";
    /** JNI provider. */
    public static final String JNI = "jni";
    /** Dumb terminal. */
    public static final String DUMB = "dumb";
    /** No JLine at all. */
    public static final String PLAIN = "plain";
    /** Automatic choice. */
    public static final String AUTO = "auto";

    private static final System.Logger LOG = System.getLogger(TerminalSelector.class.getName());

    private TerminalSelector() {
    }

    /** How the console runs. */
    public enum Mode {
        /** JLine on a real terminal. */
        JLINE,
        /** Plain lines from stdin. */
        PLAIN
    }

    /**
     * The choice.
     *
     * @param mode     JLine or plain
     * @param provider the JLine provider ({@code ffm}, {@code jni}, {@code dumb}); null for plain
     * @param reason   why, for the log
     */
    public record Selection(Mode mode, String provider, String reason) {
        /** Checks the combination. */
        public Selection {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(reason, "reason");
            if ((mode == Mode.JLINE) != (provider != null)) {
                throw new IllegalArgumentException("a JLine selection needs a provider, a plain one none");
            }
        }
    }

    /**
     * The decision itself, without looking at the machine.
     *
     * @param javaFeature    running Java version ({@code Runtime.version().feature()})
     * @param requested      value of the override, or null
     * @param loadable       providers that loaded and work here
     * @param terminal       whether stdin and stdout are both a terminal
     */
    public static Selection choose(int javaFeature, String requested, Set<String> loadable, boolean terminal) {
        String wish = requested == null ? AUTO : requested.strip().toLowerCase(Locale.ROOT);
        List<String> notes = new ArrayList<>();
        if (!List.of(AUTO, FFM, JNI, DUMB, PLAIN).contains(wish)) {
            notes.add(PROPERTY + " must be auto, ffm, jni, dumb or plain; using auto");
            wish = AUTO;
        }
        if (wish.equals(PLAIN)) {
            return new Selection(Mode.PLAIN, null, "requested with " + PROPERTY + "=plain");
        }
        if (!terminal) {
            notes.add(0, "stdin or stdout is not a terminal");
            return new Selection(Mode.PLAIN, null, String.join("; ", notes));
        }
        List<String> order = switch (wish) {
            case JNI -> List.of(JNI, DUMB);
            case DUMB -> List.of(DUMB);
            default -> List.of(FFM, JNI, DUMB);
        };
        for (String provider : order) {
            if (provider.equals(DUMB)) {
                notes.add(0, wish.equals(DUMB) ? "requested" : "no native terminal provider available");
                return new Selection(Mode.JLINE, DUMB, String.join("; ", notes));
            }
            if (provider.equals(FFM) && javaFeature < FFM_MIN_JAVA) {
                if (wish.equals(FFM)) {
                    notes.add("ffm needs Java " + FFM_MIN_JAVA + "+, running " + javaFeature);
                }
                continue;
            }
            if (loadable.contains(provider)) {
                notes.add(0, (wish.equals(provider) ? "requested" : "automatic") + " on Java " + javaFeature);
                return new Selection(Mode.JLINE, provider, String.join("; ", notes));
            }
            if (wish.equals(provider)) {
                notes.add(provider + " is not available here");
            }
        }
        throw new IllegalStateException("unreachable: dumb ends every order");
    }

    /**
     * Looks at this machine: running Java version, the override, which providers load and whether
     * stdin and stdout are a terminal.
     */
    public static Selection detect() {
        int feature = Runtime.version().feature();
        String requested = System.getProperty(PROPERTY);
        if (requested == null) {
            requested = System.getenv(ENVIRONMENT);
        }
        if (requested != null && requested.strip().equalsIgnoreCase(PLAIN)) {
            return choose(feature, requested, Set.of(), false);
        }
        Set<String> loadable = new LinkedHashSet<>();
        Boolean terminal = null;
        List<String> candidates = feature >= FFM_MIN_JAVA ? List.of(FFM, JNI) : List.of(JNI);
        for (String name : candidates) {
            Boolean result = probe(name);
            if (result != null) {
                loadable.add(name);
                if (terminal == null) {
                    terminal = result;
                }
            }
        }
        if (terminal == null) {
            terminal = consoleIsTerminal();
        }
        return choose(feature, requested, loadable, terminal);
    }

    /** Loads a provider and asks it about stdin and stdout; null if it does not work here. */
    private static Boolean probe(String name) {
        try {
            TerminalProvider provider = TerminalProvider.load(name);
            return provider.isSystemStream(SystemStream.Input) && provider.isSystemStream(SystemStream.Output);
        } catch (Exception | LinkageError e) {
            LOG.log(System.Logger.Level.DEBUG, "JLine provider " + name + " is not available", e);
            return null;
        }
    }

    /**
     * Fallback when no native provider loads: {@link System#console()}. On Java 22+ that returns a
     * console even without a terminal, so {@code Console.isTerminal()} (added in 22) decides there.
     */
    private static boolean consoleIsTerminal() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            Method isTerminal = Console.class.getMethod("isTerminal");
            return (Boolean) isTerminal.invoke(console);
        } catch (ReflectiveOperationException e) {
            return true; // Java 21: a non-null console means a terminal
        }
    }
}
