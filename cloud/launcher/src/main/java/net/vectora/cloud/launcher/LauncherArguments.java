package net.vectora.cloud.launcher;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Parsed command line of {@code VectoraCloud.jar}.
 *
 * @param allowRoot      {@code --allow-root}: start even with root or administrator rights
 * @param home           {@code --home <path>}: cloud home; empty means the working directory
 * @param nonInteractive {@code --non-interactive}: never ask on the console (Docker, Pterodactyl, systemd)
 * @param setupFile      {@code --setup-file <path>}: answers for the setup assistant
 * @param language       {@code --lang de|en}; empty means {@code user.language}
 * @param help           {@code --help}
 * @param version        {@code --version}
 */
public record LauncherArguments(
        boolean allowRoot,
        Optional<Path> home,
        boolean nonInteractive,
        Optional<Path> setupFile,
        Optional<Language> language,
        boolean help,
        boolean version) {

    /** Validates that no component is {@code null}. */
    public LauncherArguments {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(setupFile, "setupFile");
        Objects.requireNonNull(language, "language");
    }

    /**
     * Arguments of a start without any option.
     *
     * @return all switches off, all values empty
     */
    public static LauncherArguments defaults() {
        return new LauncherArguments(false, Optional.empty(), false, Optional.empty(), Optional.empty(), false, false);
    }
}
