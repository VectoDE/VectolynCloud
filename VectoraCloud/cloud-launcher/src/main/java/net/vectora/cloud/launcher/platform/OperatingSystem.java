package net.vectora.cloud.launcher.platform;

import java.util.Locale;

/** Operating system family, as far as the cloud cares. */
public enum OperatingSystem {
    /** Microsoft Windows. */
    WINDOWS,
    /** Linux, including containers. */
    LINUX,
    /** macOS. */
    MACOS,
    /** Anything else (BSDs, Solaris, ...). Treated like a Unix system. */
    OTHER;

    /**
     * Maps the value of {@code os.name}.
     *
     * @param osName value of {@code os.name}, may be {@code null}
     * @return the family
     */
    public static OperatingSystem fromOsName(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return WINDOWS;
        }
        if (name.startsWith("linux")) {
            return LINUX;
        }
        if (name.startsWith("mac") || name.startsWith("darwin")) {
            return MACOS;
        }
        return OTHER;
    }
}
