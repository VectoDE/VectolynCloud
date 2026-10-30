package net.vectolyn.cloud.node;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The settings the launcher passes to {@link NodeMain#start(Map)}, checked and typed. The keys and
 * their meaning are listed in {@code docs/cloud/launcher.md}.
 *
 * @param home            absolute, normalized cloud home
 * @param language        {@code de} or {@code en}
 * @param nonInteractive  whether the setup must not ask questions
 * @param setupFile       answers for the setup assistant, if given
 * @param rootAllowed     whether an elevated start was explicitly allowed
 * @param launcherVersion version of {@code VectolynCloud.jar}, {@code dev} outside the jar
 * @param platform        the {@code platform.*} entries without the prefix (os, arch, docker,
 *                        pterodactyl, terminal)
 */
public record NodeSettings(Path home, String language, boolean nonInteractive, Optional<Path> setupFile,
                           boolean rootAllowed, String launcherVersion, Map<String, String> platform) {

    /** Checks the values and copies the map. */
    public NodeSettings {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(setupFile, "setupFile");
        Objects.requireNonNull(launcherVersion, "launcherVersion");
        platform = Map.copyOf(platform);
        if (!home.isAbsolute()) {
            throw new IllegalArgumentException("home must be absolute");
        }
    }

    /**
     * Reads the settings map of the launcher.
     *
     * @param settings the map passed to {@code NodeMain.start}
     * @return the checked settings
     * @throws IllegalArgumentException if {@code home} is missing or not an absolute path, or a
     *                                  value has the wrong form
     */
    public static NodeSettings parse(Map<String, String> settings) {
        Objects.requireNonNull(settings, "settings");
        String homeText = settings.get("home");
        if (homeText == null || homeText.isBlank()) {
            throw new IllegalArgumentException("setting 'home' is missing");
        }
        Path home = path("home", homeText);
        if (!home.isAbsolute()) {
            throw new IllegalArgumentException("setting 'home' must be an absolute path");
        }
        String language = settings.getOrDefault("language", "en");
        if (!language.equals("de") && !language.equals("en")) {
            throw new IllegalArgumentException("setting 'language' must be de or en");
        }
        Optional<Path> setupFile = Optional.ofNullable(settings.get("setup-file")).map(v -> path("setup-file", v));
        Map<String, String> platform = new java.util.TreeMap<>();
        settings.forEach((key, value) -> {
            if (key.startsWith("platform.") && value != null) {
                platform.put(key.substring("platform.".length()), value);
            }
        });
        return new NodeSettings(home.normalize(), language, bool(settings, "non-interactive"), setupFile,
                bool(settings, "root-allowed"), settings.getOrDefault("launcher.version", "dev"), platform);
    }

    /**
     * The settings again in the launcher's form, for parts that read that map themselves (the setup
     * assistant's {@code SetupEnvironment.of}): {@code home}, {@code language},
     * {@code non-interactive}, {@code setup-file} if given, {@code root-allowed},
     * {@code launcher.version} and every {@code platform.*} entry.
     *
     * @return a new, unmodifiable map; {@link #parse} of it gives equal settings
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new java.util.TreeMap<>();
        map.put("home", home.toString());
        map.put("language", language);
        map.put("non-interactive", Boolean.toString(nonInteractive));
        setupFile.ifPresent(file -> map.put("setup-file", file.toString()));
        map.put("root-allowed", Boolean.toString(rootAllowed));
        map.put("launcher.version", launcherVersion);
        platform.forEach((key, value) -> map.put("platform." + key, value));
        return java.util.Collections.unmodifiableMap(map);
    }

    private static boolean bool(Map<String, String> settings, String key) {
        String value = settings.getOrDefault(key, "false");
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("setting '" + key + "' must be true or false");
        };
    }

    private static Path path(String key, String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("setting '" + key + "' is not a valid path", e);
        }
    }
}
