package net.vectora.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * How the node runs the process of each service of a group. Optional in {@code groups/<name>.yml};
 * a missing section means {@link #defaults()}.
 *
 * <p>The heap is not set here but in the group's {@code heap} section, which also fills
 * {@code VECTORA_XMS}/{@code VECTORA_XMX}. {@code -Xms}, {@code -Xmx} and anything that changes
 * what the JVM runs ({@code -jar}, {@code -cp}) are therefore refused in {@code jvm-flags}.
 *
 * @param jvmFlags    extra JVM options, for example {@code -XX:+UseG1GC}; never null
 * @param stopCommand console command that stops the server gracefully; null means the default
 *                    of the software ({@code shutdown} for Velocity, {@code stop} otherwise)
 * @param stopTimeout how long the node waits after the stop command before it ends the process
 * @param restart     restart after a crash
 */
@JsonPropertyOrder({"jvm-flags", "stop-command", "stop-timeout", "restart"})
public record ProcessConfig(List<String> jvmFlags, String stopCommand, TimeSpan stopTimeout, RestartConfig restart) {

    /** Largest number of entries in {@code jvm-flags}. */
    public static final int MAX_JVM_FLAGS = 64;
    /** Pattern of one JVM flag: starts with {@code -}, no whitespace, quotes or control characters. */
    public static final String JVM_FLAG = "-[A-Za-z0-9_.:+=/,@%-]{1,255}";
    /** Pattern of the stop command. */
    public static final String STOP_COMMAND = "[A-Za-z0-9][A-Za-z0-9 _.:-]{0,63}";
    /** Shortest stop timeout in seconds. */
    public static final long MIN_STOP_TIMEOUT_SECONDS = 1;
    /** Longest stop timeout in seconds (10 minutes). */
    public static final long MAX_STOP_TIMEOUT_SECONDS = 600;

    /** Copies the flag list; missing values become their defaults (the stop command stays null). */
    public ProcessConfig {
        jvmFlags = jvmFlags == null ? List.of() : List.copyOf(jvmFlags);
        stopTimeout = stopTimeout == null ? TimeSpan.ofSeconds(60) : stopTimeout;
        restart = restart == null ? RestartConfig.defaults() : restart;
    }

    /** No extra flags, the software's stop command, 60 s stop timeout, {@link RestartConfig#defaults()}. */
    public static ProcessConfig defaults() {
        return new ProcessConfig(List.of(), null, null, null);
    }

    /**
     * Tells whether a JVM flag is refused because another setting owns it or because it changes
     * what the JVM runs: {@code -Xms}, {@code -Xmx}, {@code -jar}, {@code -cp}, {@code -classpath},
     * {@code --class-path}, {@code -version}, {@code --version}, {@code -help}, {@code --help},
     * {@code -?}, {@code -X} on its own, {@code -XX:Flags=}, {@code -XX:VMOptionsFile=}.
     *
     * @param flag one entry of {@code jvm-flags}
     * @return whether the flag is not allowed
     */
    public static boolean isReservedJvmFlag(String flag) {
        if (flag.startsWith("-Xms") || flag.startsWith("-Xmx")) {
            return true;
        }
        if (flag.startsWith("-XX:Flags=") || flag.startsWith("-XX:VMOptionsFile=")) {
            return true;
        }
        return switch (flag) {
            case "-jar", "-cp", "-classpath", "--class-path", "-version", "--version", "-showversion",
                 "--show-version", "-help", "--help", "-?", "-X", "--help-extra" -> true;
            default -> flag.startsWith("--class-path=");
        };
    }
}
