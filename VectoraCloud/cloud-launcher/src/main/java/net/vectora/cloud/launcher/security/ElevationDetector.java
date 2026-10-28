package net.vectora.cloud.launcher.security;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectora.cloud.launcher.platform.OperatingSystem;
import net.vectora.cloud.launcher.platform.SystemAccess;

/**
 * Finds out whether the process runs as root or as an elevated Windows administrator.
 *
 * <p><b>Linux, macOS and other Unix systems:</b> UID 0. Sources in this order:
 * {@code com.sun.security.auth.module.UnixSystem} (in-process, no fork), then the effective UID
 * from the {@code Uid:} line of {@code /proc/self/status}, then {@code /usr/bin/id -u} or, if that
 * file does not exist, {@code /bin/id -u}.
 *
 * <p><b>Windows:</b> the integrity level of the process token. "Run as administrator" gives a
 * token with the mandatory label {@code S-1-16-12288} (High); services under LocalSystem have
 * {@code S-1-16-16384} (System). A member of the Administrators group in a normal console has
 * Medium ({@code S-1-16-8192}) and is <i>not</i> refused, because UAC filters its rights. The
 * Administrators SID {@code S-1-5-32-544} is not used: in a filtered token it is still listed
 * (as deny-only), so it would refuse every admin account. Sources: the token groups from
 * {@code com.sun.security.auth.module.NTSystem} (in-process, independent of the display language),
 * then {@code %SystemRoot%\System32\whoami.exe /groups /fo csv /nh}, whose group names are
 * localized but whose SIDs are not.
 *
 * <p>Fallback commands are started by absolute path, never looked up on the {@code PATH}. If no
 * source works, or a source reports a number that does not fit, the result is
 * {@link Elevation.State#UNKNOWN}.
 */
public final class ElevationDetector {

    /** Lowest integrity level that counts as elevated: High. */
    static final long HIGH_INTEGRITY_RID = 12288;

    /** {@code id} on most Linux distributions and on macOS. */
    private static final String ID_USR_BIN = "/usr/bin/id";
    /** {@code id} on systems that only have it in {@code /bin}. */
    private static final String ID_BIN = "/bin/id";

    private static final Pattern INTEGRITY_SID = Pattern.compile("S-1-16-(\\d{1,10})");
    private static final Pattern UID_LINE = Pattern.compile("(?m)^Uid:\\s+(\\d{1,10})\\s+(\\d{1,10})(?!\\d)");

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
                OptionalLong effective = parseId(matcher.group(2));
                if (effective.isPresent()) {
                    return unixResult(effective.getAsLong(), "/proc/self/status euid=" + effective.getAsLong());
                }
            }
        }
        Optional<String> idCommand = system.exists(Path.of(ID_USR_BIN)) ? Optional.of(ID_USR_BIN)
                : system.exists(Path.of(ID_BIN)) ? Optional.of(ID_BIN) : Optional.empty();
        Optional<String> id = idCommand.flatMap(command -> system.run(List.of(command, "-u")));
        if (id.isPresent()) {
            String value = id.get().trim();
            OptionalLong parsed = value.matches("\\d{1,10}") ? parseId(value) : OptionalLong.empty();
            if (parsed.isPresent()) {
                return unixResult(parsed.getAsLong(), "id -u=" + value);
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
        Optional<String> whoami = whoamiPath(system)
                .flatMap(path -> system.run(List.of(path, "/groups", "/fo", "csv", "/nh")));
        if (whoami.isPresent()) {
            Optional<Elevation> result = fromIntegrity(whoami.get(), "whoami");
            if (result.isPresent()) {
                return result.get();
            }
        }
        return new Elevation(Elevation.State.UNKNOWN, "no integrity level found");
    }

    /**
     * Absolute path of {@code whoami.exe} below {@code %SystemRoot%}.
     *
     * @param system access to the environment
     * @return the path, or empty if {@code SystemRoot} is not set
     */
    static Optional<String> whoamiPath(SystemAccess system) {
        return system.env("SystemRoot")
                .map(String::trim)
                .filter(root -> !root.isEmpty())
                .map(root -> (root.endsWith("\\") ? root : root + "\\") + "System32\\whoami.exe");
    }

    private static Optional<Elevation> fromIntegrity(String text, String source) {
        Matcher matcher = INTEGRITY_SID.matcher(text);
        long highest = -1;
        while (matcher.find()) {
            OptionalLong rid = parseId(matcher.group(1));
            if (rid.isPresent()) {
                highest = Math.max(highest, rid.getAsLong());
            }
        }
        if (highest < 0) {
            return Optional.empty();
        }
        Elevation.State state = highest >= HIGH_INTEGRITY_RID ? Elevation.State.ELEVATED : Elevation.State.NOT_ELEVATED;
        return Optional.of(new Elevation(state, source + " S-1-16-" + highest));
    }

    /**
     * Parses a user ID or relative ID.
     *
     * @param digits decimal digits
     * @return the number, or empty if it is not a number or does not fit into a {@code long}
     */
    static OptionalLong parseId(String digits) {
        try {
            return OptionalLong.of(Long.parseLong(digits));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
