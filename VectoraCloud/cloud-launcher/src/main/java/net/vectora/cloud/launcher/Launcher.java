package net.vectora.cloud.launcher;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.vectora.cloud.launcher.libs.LibraryException;
import net.vectora.cloud.launcher.platform.DefaultSystemAccess;
import net.vectora.cloud.launcher.platform.OperatingSystem;
import net.vectora.cloud.launcher.platform.Platform;
import net.vectora.cloud.launcher.platform.PlatformDetector;
import net.vectora.cloud.launcher.platform.SystemAccess;
import net.vectora.cloud.launcher.security.Elevation;
import net.vectora.cloud.launcher.security.ElevationDetector;
import net.vectora.cloud.launcher.security.RootGuard;

/**
 * The launcher of {@code VectoraCloud.jar}, called by {@code Bootstrap} once the Java version is
 * known to be 21 or newer. The order of the remaining steps is fixed:
 * <ol start="2">
 *   <li>root/administrator check ({@link ElevationDetector}, {@link RootGuard}),</li>
 *   <li>argument parsing ({@link ArgumentParser}),</li>
 *   <li>node start ({@link NodeStarter}).</li>
 * </ol>
 * The root check needs {@code --allow-root} and {@code --lang} before the arguments are parsed,
 * so it reads just those two from the raw arguments.
 */
public final class Launcher {

    /** Normal end, {@code --help} or {@code --version}. */
    public static final int EXIT_OK = 0;
    /** The node failed while starting. */
    public static final int EXIT_NODE_FAILED = 1;
    /** Started as root or administrator without {@code --allow-root}. */
    public static final int EXIT_ELEVATED = 2;
    /** Java older than 21; returned by {@code Bootstrap}, listed here for completeness. */
    public static final int EXIT_JAVA_TOO_OLD = 3;
    /** The embedded libraries are missing, damaged or modified. */
    public static final int EXIT_LIBRARIES = 4;
    /** This build has no node entry point, or it is invalid. */
    public static final int EXIT_NO_NODE = 5;
    /** Unknown or invalid command line argument (BSD {@code EX_USAGE}). */
    public static final int EXIT_USAGE = 64;

    /** Starts the node once all checks have passed. Replaceable for tests. */
    @FunctionalInterface
    public interface NodeLauncher {

        /**
         * Starts the node.
         *
         * @param home     absolute cloud home
         * @param settings settings for {@code NodeMain.start}
         * @throws LibraryException   if the embedded libraries cannot be used
         * @throws NodeStartException if the node is missing or fails
         */
        void launch(Path home, Map<String, String> settings) throws LibraryException, NodeStartException;
    }

    private final SystemAccess system;
    private final PrintStream out;
    private final PrintStream err;
    private final NodeLauncher nodeLauncher;

    /**
     * Creates a launcher.
     *
     * @param system       access to the operating system
     * @param out          standard output
     * @param err          standard error
     * @param nodeLauncher what starts the node
     */
    public Launcher(SystemAccess system, PrintStream out, PrintStream err, NodeLauncher nodeLauncher) {
        this.system = Objects.requireNonNull(system, "system");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.nodeLauncher = Objects.requireNonNull(nodeLauncher, "nodeLauncher");
    }

    /**
     * Entry point, called reflectively by {@code Bootstrap}. Exits the JVM only on failure; on
     * success the node's own threads keep it running.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        Launcher launcher = new Launcher(new DefaultSystemAccess(), System.out, System.err, Launcher::startEmbeddedNode);
        int code = launcher.run(args);
        if (code != EXIT_OK) {
            System.exit(code);
        }
    }

    /**
     * Runs steps 2 to 4.
     *
     * @param args command line arguments
     * @return exit code, one of the {@code EXIT_*} constants
     */
    public int run(String[] args) {
        Objects.requireNonNull(args, "args");
        // Step 2: root/administrator check. Only --allow-root and --lang are read here.
        Language earlyLanguage = Language.early(args, system.property("user.language").orElse(null));
        Messages early = new Messages(earlyLanguage);
        OperatingSystem os = OperatingSystem.fromOsName(system.property("os.name").orElse(null));
        Elevation elevation = ElevationDetector.detect(os, system);
        boolean allowRootFlag = containsSwitch(args, "--allow-root");
        RootGuard.Decision decision = RootGuard.decide(elevation, allowRootFlag, system.env(RootGuard.ALLOW_ROOT_ENV));
        switch (decision) {
            case REFUSE -> {
                err.print(early.rootRefused(os, elevation.evidence()));
                return EXIT_ELEVATED;
            }
            case PROCEED_WITH_WARNING -> err.print(early.rootAllowedWarning(elevation.evidence()));
            case PROCEED_UNCHECKED -> err.print(early.elevationUnknown(elevation.evidence()));
            case PROCEED -> {
                // Nothing to say.
            }
            default -> throw new IllegalStateException(decision.name());
        }

        // Step 3: arguments.
        LauncherArguments arguments;
        try {
            arguments = ArgumentParser.parse(args);
        } catch (ArgumentException e) {
            err.println(early.argumentError(e));
            err.println();
            err.print(early.help());
            return EXIT_USAGE;
        }
        Language language = arguments.language().orElse(earlyLanguage);
        Messages messages = new Messages(language);
        if (arguments.help()) {
            out.print(messages.help());
            return EXIT_OK;
        }
        if (arguments.version()) {
            out.println(messages.version(version(), system.property("java.version").orElse("?")));
            return EXIT_OK;
        }

        // Step 4: node.
        Path home = arguments.home()
                .orElseGet(() -> Path.of(system.property("user.dir").orElse(".")))
                .toAbsolutePath().normalize();
        Platform platform = PlatformDetector.detect(system);
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("home", home.toString());
        settings.put("language", language.code());
        settings.put("non-interactive", Boolean.toString(arguments.nonInteractive()));
        arguments.setupFile().ifPresent(file -> settings.put("setup-file", file.toAbsolutePath().normalize().toString()));
        settings.put("root-allowed", Boolean.toString(decision == RootGuard.Decision.PROCEED_WITH_WARNING));
        settings.put("launcher.version", version());
        settings.putAll(platform.toSettings());
        try {
            nodeLauncher.launch(home, settings);
            return EXIT_OK;
        } catch (LibraryException e) {
            err.println(messages.libraryError(e));
            return EXIT_LIBRARIES;
        } catch (NodeStartException e) {
            err.println(messages.nodeError(e));
            if (e.kind() == NodeStartException.Kind.FAILED && e.getCause() != null) {
                e.getCause().printStackTrace(err);
                return EXIT_NODE_FAILED;
            }
            return EXIT_NO_NODE;
        } catch (RuntimeException e) {
            err.println(messages.nodeError(new NodeStartException(NodeStartException.Kind.FAILED, "unexpected", e)));
            e.printStackTrace(err);
            return EXIT_NODE_FAILED;
        }
    }

    /**
     * Version of this build, from the {@code Implementation-Version} of {@code VectoraCloud.jar}.
     *
     * @return the version, or {@code "dev"} outside the jar
     */
    public static String version() {
        String version = Launcher.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    private static boolean containsSwitch(String[] args, String name) {
        for (String arg : args) {
            if (arg.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void startEmbeddedNode(Path home, Map<String, String> settings)
            throws LibraryException, NodeStartException {
        Path libs = home.resolve("cache").resolve("libs");
        ClassLoader loader = NodeStarter.classLoader(Launcher.class.getClassLoader(), libs);
        NodeStarter.start(loader, NodeStarter.NODE_MAIN_CLASS, settings);
    }
}
