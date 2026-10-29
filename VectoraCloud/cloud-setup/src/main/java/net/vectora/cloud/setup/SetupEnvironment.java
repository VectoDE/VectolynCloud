package net.vectora.cloud.setup;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.database.managed.ManagedPlatform;
import net.vectora.cloud.download.provider.TargetPlatform;

/**
 * What the assistant knows about the machine and the start: the launcher settings (see
 * {@code docs/cloud/launcher.md}) and a few environment variables.
 *
 * @param language        language for the first question, from {@code --lang} or the system
 * @param interactive     false with {@code --non-interactive}: the assistant must not ask anything
 * @param setupFile       the file from {@code --setup-file}, if given
 * @param windows         whether the cloud runs on Windows (for the note about administrators)
 * @param container       whether the cloud runs in a container
 * @param pterodactyl     whether the cloud runs on Pterodactyl; the Docker database is hidden there
 * @param managedDatabase whether MariaDB publishes a server build for this system (Windows x64,
 *                        Linux x86_64 with glibc); elsewhere {@code managed} falls back to H2
 * @param serverMemory    memory limit of the Pterodactyl server in megabytes ({@code SERVER_MEMORY}), if set
 * @param serverPort      port Pterodactyl assigned ({@code SERVER_PORT}), the default Java port there
 * @param totalMemory     physical memory of the machine in megabytes, if the JVM can tell
 */
public record SetupEnvironment(Language language, boolean interactive, Optional<Path> setupFile, boolean windows,
                               boolean container, boolean pterodactyl, boolean managedDatabase,
                               OptionalLong serverMemory, OptionalInt serverPort, OptionalLong totalMemory) {

    /** Checks the arguments. */
    public SetupEnvironment {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(setupFile, "setupFile");
        Objects.requireNonNull(serverMemory, "serverMemory");
        Objects.requireNonNull(serverPort, "serverPort");
        Objects.requireNonNull(totalMemory, "totalMemory");
    }

    /**
     * From the launcher settings and the environment of the process.
     *
     * <p>Pterodactyl is taken from {@code platform.pterodactyl} if the launcher set it, otherwise from
     * the same rule the launcher uses: {@code P_SERVER_UUID} alone, or {@code STARTUP},
     * {@code SERVER_MEMORY} and {@code SERVER_PORT} together. Wings sets the last three in every
     * server container, the panel adds {@code P_SERVER_UUID}.
     *
     * @param settings the launcher settings ({@code home}, {@code language}, {@code non-interactive},
     *                 {@code setup-file}, {@code platform.*})
     * @param env      environment variables
     */
    public static SetupEnvironment of(Map<String, String> settings, Map<String, String> env) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(env, "env");
        Language language = "de".equals(settings.get("language")) ? Language.DE : Language.EN;
        boolean interactive = !"true".equals(settings.get("non-interactive"));
        Optional<Path> setupFile = Optional.ofNullable(settings.get("setup-file")).filter(s -> !s.isBlank()).map(Path::of);
        String os = settings.getOrDefault("platform.os", System.getProperty("os.name", "").toLowerCase(Locale.ROOT));
        boolean windows = os.startsWith("windows");
        boolean pterodactyl = settings.containsKey("platform.pterodactyl")
                ? "true".equals(settings.get("platform.pterodactyl"))
                : isPterodactyl(env);
        boolean container = pterodactyl || "true".equals(settings.get("platform.docker"));
        boolean managed;
        try {
            managed = ManagedPlatform.find(TargetPlatform.current()).isPresent();
        } catch (RuntimeException e) {
            managed = false;
        }
        return new SetupEnvironment(language, interactive, setupFile, windows, container, pterodactyl, managed,
                positiveLong(env.get("SERVER_MEMORY")), port(pterodactyl ? env.get("SERVER_PORT") : null), physicalMemory());
    }

    /** The launcher's Pterodactyl rule on {@code env}. */
    public static boolean isPterodactyl(Map<String, String> env) {
        if (present(env.get("P_SERVER_UUID"))) {
            return true;
        }
        return present(env.get("STARTUP")) && present(env.get("SERVER_MEMORY")) && present(env.get("SERVER_PORT"));
    }

    /** A copy with another language (tests, and the language the operator picked). */
    public SetupEnvironment withLanguage(Language value) {
        return new SetupEnvironment(value, interactive, setupFile, windows, container, pterodactyl, managedDatabase,
                serverMemory, serverPort, totalMemory);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static OptionalLong positiveLong(String value) {
        if (value == null || !value.matches("[0-9]{1,9}")) {
            return OptionalLong.empty();
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? OptionalLong.of(parsed) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private static OptionalInt port(String value) {
        if (value == null || !value.matches("[0-9]{1,5}")) {
            return OptionalInt.empty();
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 1 && parsed <= 65535 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private static OptionalLong physicalMemory() {
        try {
            if (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean os) {
                long bytes = os.getTotalMemorySize();
                return bytes > 0 ? OptionalLong.of(bytes / (1024 * 1024)) : OptionalLong.empty();
            }
        } catch (RuntimeException | LinkageError e) {
            // A runtime without jdk.management: the summary just leaves the hint out.
        }
        return OptionalLong.empty();
    }
}
