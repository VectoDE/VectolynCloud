package net.vectolyn.cloud.launcher.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Everything the launcher reads from the operating system, behind one interface. The detection
 * logic ({@link PlatformDetector}, {@code ElevationDetector}) only talks to this interface, so the
 * tests can replay the output of a root shell, an elevated Windows console or a Pterodactyl
 * container without needing one.
 *
 * <p>Every method answers "don't know" with an empty result instead of throwing.
 */
public interface SystemAccess {

    /**
     * Reads an environment variable.
     *
     * @param name variable name
     * @return its value, or empty if unset
     */
    Optional<String> env(String name);

    /**
     * Reads a JVM system property.
     *
     * @param name property name
     * @return its value, or empty if unset
     */
    Optional<String> property(String name);

    /**
     * Checks whether a file exists.
     *
     * @param path absolute path
     * @return {@code true} if something exists at that path
     */
    boolean exists(Path path);

    /**
     * Reads a small text file such as {@code /proc/self/status}.
     *
     * @param path absolute path
     * @return the content, or empty if the file is missing or unreadable
     */
    Optional<String> readFile(Path path);

    /**
     * Runs a short command and returns its standard output. Used only as a fallback
     * ({@code id -u}, {@code whoami /groups}).
     *
     * @param command program and arguments
     * @return standard output if the command ran and exited with 0, otherwise empty
     */
    Optional<String> run(List<String> command);

    /**
     * Reads the user ID through {@code com.sun.security.auth.module.UnixSystem}, which is part of
     * the JDK module {@code jdk.security.auth} on Linux and macOS.
     *
     * @return the user ID, or empty if the class is not available
     */
    Optional<Long> unixUid();

    /**
     * Reads the group SIDs of the process token through
     * {@code com.sun.security.auth.module.NTSystem} (module {@code jdk.security.auth}, Windows only).
     * The list contains the integrity level SID ({@code S-1-16-...}).
     *
     * @return the SIDs, or empty if the class is not available
     */
    Optional<List<String>> windowsGroupSids();

    /**
     * Tells whether standard input and output are an interactive terminal.
     *
     * @return {@code true} for a terminal, {@code false} for pipes, files, Docker without
     *         {@code -t}, Pterodactyl or systemd
     */
    boolean interactiveTerminal();
}
