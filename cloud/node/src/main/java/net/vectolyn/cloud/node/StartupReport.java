package net.vectolyn.cloud.node;

import java.util.List;
import java.util.Locale;
import net.vectolyn.cloud.node.service.ServiceInfo;

/**
 * The start output of the node ({@code docs/cloud/node.md#start-output}): a banner with the
 * version, then one line per step in a fixed order and width, then one line per service.
 *
 * <pre>
 * [1/5] Configuration  ok       9 groups, 9 tasks, node node-1
 * [2/5] Database       ok       mariadb on 127.0.0.1:3307
 * [3/5] Software       ok       lobby: paper 1.21.10 + 6 plugins; proxy: velocity + 5 plugins
 * [4/5] Templates      ok       2 written (Lobby-Server, Proxy), 0 present
 * [5/5] Services       ok       2 of 2 started
 *       proxy-1        starting port 25565
 * </pre>
 *
 * Every line goes through the log, so it has the one log format and also lands in
 * {@code logs/cloud.log}.
 */
final class StartupReport {

    private static final System.Logger LOG = System.getLogger(StartupReport.class.getName());

    /** The steps, in their order. */
    enum Step {
        /** Configuration files read and checked. */
        CONFIGURATION("Configuration"),
        /** The database set up. */
        DATABASE("Database"),
        /** Server software in place. */
        SOFTWARE("Software"),
        /** Templates of the wanted servers in place. */
        TEMPLATES("Templates"),
        /** The configured services started. */
        SERVICES("Services");

        private final String title;

        Step(String title) {
            this.title = title;
        }
    }

    /** Status of a step. */
    enum Status {
        /** Done. */
        OK("ok"),
        /** Done, with problems in a part. */
        PARTLY("partly"),
        /** Not done. */
        FAILED("failed"),
        /** Not run, for example while the setup is not complete. */
        SKIPPED("skipped");

        private final String word;

        Status(String word) {
            this.word = word;
        }
    }

    private StartupReport() {
    }

    /**
     * The banner, plain ASCII so every console shows it.
     *
     * @param version version of {@code VectolynCloud.jar}
     * @param home    the cloud home
     * @return the lines
     */
    static List<String> banner(String version, java.nio.file.Path home) {
        String java = System.getProperty("java.version", "?") + " (" + System.getProperty("java.vendor", "?") + ")";
        String os = System.getProperty("os.name", "?") + " " + System.getProperty("os.arch", "?");
        return List.of(
                "",
                "  __     __        _        _               ____ _                 _",
                "  \\ \\   / /__  ___| |_ ___ | |_   _ _ __   / ___| | ___  _   _  __| |",
                "   \\ \\ / / _ \\/ __| __/ _ \\| | | | | '_ \\ | |   | |/ _ \\| | | |/ _` |",
                "    \\ V /  __/ (__| || (_) | | |_| | | | || |___| | (_) | |_| | (_| |",
                "     \\_/ \\___|\\___|\\__\\___/|_|\\__, |_| |_| \\____|_|\\___/ \\__,_|\\__,_|",
                "                              |___/",
                "",
                "  VectolynCloud " + version + "  |  Java " + java + "  |  " + os,
                "  Home: " + home,
                "");
    }

    /**
     * Logs one step.
     *
     * @param step   the step
     * @param status how it ended
     * @param detail what it found or did, in a few words
     */
    static void step(Step step, Status status, String detail) {
        String line = String.format(Locale.ROOT, "[%d/%d] %-14s %-8s %s", step.ordinal() + 1, Step.values().length,
                step.title, status.word, detail);
        System.Logger.Level level = switch (status) {
            case OK, SKIPPED -> System.Logger.Level.INFO;
            case PARTLY -> System.Logger.Level.WARNING;
            case FAILED -> System.Logger.Level.ERROR;
        };
        LOG.log(level, line);
    }

    /**
     * Logs one line per service under the services step.
     *
     * @param services the services after the start
     */
    static void services(List<ServiceInfo> services) {
        for (ServiceInfo service : services) {
            String state = service.state().name().toLowerCase(Locale.ROOT);
            String line = String.format(Locale.ROOT, "      %-14s %-8s port %d, %s", service.id().value(), state,
                    service.port(), service.mode().name().toLowerCase(Locale.ROOT));
            LOG.log(service.state().hasProcess() ? System.Logger.Level.INFO : System.Logger.Level.WARNING, line);
        }
    }
}
