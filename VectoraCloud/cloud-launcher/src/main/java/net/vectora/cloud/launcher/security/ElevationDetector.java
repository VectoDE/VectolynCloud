package net.vectora.cloud.launcher.security;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectora.cloud.launcher.platform.OperatingSystem;
import net.vectora.cloud.launcher.platform.SystemAccess;

/**
 * Finds out whether the process runs as root or as an elevated Windows administrator.
 *
 * <p><b>Linux, macOS and other Unix systems:</b> UID 0. Sources in this order:
 * {@code com.sun.security.auth.module.UnixSystem} (in-process, no fork), then the effective UID
 * from the {@code Uid:} line of {@code /proc/self/status}, then {@code id -u}.
 *
 * <p><b>Windows:</b> the integrity level of the process token. "Run as administrator" gives a
 * token with the mandatory label {@code S-1-16-12288} (High); services under LocalSystem have
 * {@code S-1-16-16384} (System). A member of the Administrators group in a normal console has
 * Medium ({@code S-1-16-8192}) and is <i>not</i> refused, because UAC filters its rights. The
 * Administrators SID {@code S-1-5-32-544} is not used: in a filtered token it is still listed
 * (as deny-only), so it would refuse every admin account. Sources: the token groups from
 * {@code com.sun.security.auth.module.NTSystem} (in-process, independent of the display language),
 * then {@code whoami /groups /fo csv /nh}, whose group names are localized but whose SIDs are not.
 *
 * <p>If no source works the result is {@link Elevation.State#UNKNOWN}.
 */
public final class ElevationDetector {

    /** Lowest integrity level that counts as elevated: High. */
    static final long HIGH_INTEGRITY_RID = 12288;

    private static final Pattern INTEGRITY_SID = Pattern.compile("S-1-16-(\\d{1,10})");
    private static final Pattern UID_LINE = Pattern.compile("(?m)^Uid:\\s+(\\d+)\\s+(\\d+)");

    private ElevationDetector() {
    }

    /**
     * Runs the check.
     *
     * @param os     operating system family
     * @param system access to the operating system
     * @return the result, never {@code null}
     */
    public static Elevation detect(OperatingSystem os, SystemAccess system) {
        return os == OperatingSystem.WINDOWS ? detectWindows(system) : detectUnix(system);
    }

    private static Elevation detectUnix(SystemAccess system) {
        Optional<Long> uid = system.unixUid();
        if (uid.isPresent()) {
            return unixResult(uid.get(), "UnixSystem uid=" + uid.get());
        }
        Optional<String> status = system.readFile(Path.of("/proc/self/status"));
        if (status.isPresent()) {
            Matcher matcher = UID_LINE.matcher(status.get());
            if (matcher.find()) {
                long effective = Long.parseLong(matcher.group(2));
                return unixResult(effective, "/proc/self/status euid=" + effective);
            }
        }
        Optional<String> id = system.run(List.of("id", "-u"));
        if (id.isPresent()) {
            String value = id.get().trim();
            if (value.matches("\\d{1,10}")) {
                return unixResult(Long.parseLong(value), "id -u=" + value);
            }
        }
        return new Elevation(Elevation.State.UNKNOWN, "no UID source available");
    }

    private static Elevation unixResult(long uid, String evidence) {
        return new Elevation(uid == 0 ? Elevation.State.ELEVATED : Elevation.State.NOT_ELEVATED, evidence);
    }

    private static Elevation detectWindows(SystemAccess system) {
        Optional<List<String>> sids = system.windowsGroupSids();
        if (sids.isPresent()) {
            Optional<Elevation> result = fromIntegrity(String.join(" ", sids.get()), "NTSystem");
            if (result.isPresent()) {
                return result.get();
            }
        }
        Optional<String> whoami = system.run(List.of("whoami", "/groups", "/fo", "csv", "/nh"));
        if (whoami.isPresent()) {
            Optional<Elevation> result = fromIntegrity(whoami.get(), "whoami");
            if (result.isPresent()) {
                return result.get();
            }
        }
        return new Elevation(Elevation.State.UNKNOWN, "no integrity level found");
    }

    private static Optional<Elevation> fromIntegrity(String text, String source) {
        Matcher matcher = INTEGRITY_SID.matcher(text);
        long highest = -1;
        while (matcher.find()) {
            highest = Math.max(highest, Long.parseLong(matcher.group(1)));
        }
        if (highest < 0) {
            return Optional.empty();
        }
        Elevation.State state = highest >= HIGH_INTEGRITY_RID ? Elevation.State.ELEVATED : Elevation.State.NOT_ELEVATED;
        return Optional.of(new Elevation(state, source + " S-1-16-" + highest));
    }
}
