package net.vectora.cloud.backup;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The console commands that make a running server's world files consistent, and the output lines
 * that confirm them. Covers Paper and Fabric (vanilla messages) and PandaSpigot 1.8.8.
 */
public final class SaveCommands {

    /** Stops automatic saving. */
    public static final String SAVE_OFF = "save-off";
    /** Writes every loaded chunk and flushes the region files. */
    public static final String SAVE_ALL_FLUSH = "save-all flush";
    /** Turns automatic saving back on. */
    public static final String SAVE_ON = "save-on";

    /** Confirms {@link #SAVE_OFF}. */
    public static final Predicate<String> SAVE_OFF_DONE = containsAny(
            "automatic saving is now disabled", "saving is already turned off", "turned off world auto-saving");
    /** Confirms {@link #SAVE_ALL_FLUSH}. */
    public static final Predicate<String> SAVE_ALL_DONE = containsAny("saved the game", "saved the world");
    /** Confirms {@link #SAVE_ON}. */
    public static final Predicate<String> SAVE_ON_DONE = containsAny(
            "automatic saving is now enabled", "saving is already turned on", "turned on world auto-saving");

    private SaveCommands() {
    }

    private static Predicate<String> containsAny(String... needles) {
        List<String> values = List.of(needles);
        return line -> {
            if (line == null) {
                return false;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            return values.stream().anyMatch(lower::contains);
        };
    }
}
