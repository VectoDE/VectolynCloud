package net.vectora.cloud.setup;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.vectora.cloud.config.secret.OwnerOnlyPermissions;
import net.vectora.cloud.console.Text;

/**
 * Says who can read {@code secrets/vectora.env} once the assistant has written it.
 *
 * <p>The secret store restricts the file to its owner. On Linux and macOS root can still read it. On
 * Windows the access list holds only the owner, but members of the local Administrators group
 * ({@code S-1-5-32-544}) may take ownership of any file and then read it. The group's name is
 * localized ("Administratoren" on a German Windows), so the assistant looks it up with
 * {@code whoami /groups} (by its full path in {@code System32}, never through the {@code PATH}) and
 * always adds the SID, which is the same on every Windows.
 */
public final class SecretAccess {

    /** SID of the local Administrators group. */
    public static final String ADMINISTRATORS_SID = "S-1-5-32-544";
    /** Used when the localized name cannot be found. */
    public static final String ADMINISTRATORS_FALLBACK = "BUILTIN\\Administrators";

    private SecretAccess() {
    }

    /**
     * The note shown after saving.
     *
     * @param file      {@code secrets/vectora.env}
     * @param windows   whether the cloud runs on Windows
     * @param groupList output of {@code whoami /groups /fo csv /nh}, or empty if it cannot run
     */
    public static Text describe(Path file, boolean windows, Supplier<Optional<String>> groupList) {
        String owner;
        boolean restricted;
        try {
            owner = Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).getName();
            restricted = OwnerOnlyPermissions.isOwnerOnly(file);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return SetupMessages.ACCESS_OPEN;
        }
        if (!restricted) {
            return SetupMessages.ACCESS_OPEN;
        }
        if (windows) {
            return SetupMessages.fill(SetupMessages.ACCESS_WINDOWS, owner, administratorsGroup(groupList.get()));
        }
        return SetupMessages.fill(SetupMessages.ACCESS_POSIX, owner);
    }

    /**
     * The name of the Administrators group with its SID, for example
     * {@code VORDEFINIERT\Administratoren (S-1-5-32-544)}.
     *
     * @param groupList output of {@code whoami /groups /fo csv /nh}: one line per group,
     *                  {@code "name","type","SID","attributes"}
     */
    public static String administratorsGroup(Optional<String> groupList) {
        String name = ADMINISTRATORS_FALLBACK;
        if (groupList.isPresent()) {
            for (String line : groupList.get().split("\\R")) {
                List<String> fields = csv(line);
                if (fields.size() >= 3 && fields.get(2).equals(ADMINISTRATORS_SID) && isPrintable(fields.get(0))) {
                    name = fields.get(0);
                    break;
                }
            }
        }
        return name + " (" + ADMINISTRATORS_SID + ")";
    }

    /** Runs {@code %SystemRoot%\System32\whoami.exe /groups /fo csv /nh}; empty if that fails. */
    public static Optional<String> whoamiGroups() {
        String systemRoot = System.getenv("SystemRoot");
        Path whoami = Path.of(systemRoot == null || systemRoot.isBlank() ? "C:\\Windows" : systemRoot, "System32", "whoami.exe");
        if (!Files.isRegularFile(whoami)) {
            return Optional.empty();
        }
        try {
            Process process = new ProcessBuilder(whoami.toString(), "/groups", "/fo", "csv", "/nh")
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            byte[] output;
            try (InputStream in = process.getInputStream()) {
                output = in.readNBytes(256 * 1024);
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 ? Optional.of(new String(output, consoleCharset())) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            if (e instanceof InterruptedIOException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    private static Charset consoleCharset() {
        String name = System.getProperty("native.encoding");
        try {
            return name == null ? Charset.defaultCharset() : Charset.forName(name);
        } catch (RuntimeException e) {
            return Charset.defaultCharset();
        }
    }

    /** Splits one CSV line with quoted fields ({@code ""} inside a field is a quote). */
    static List<String> csv(String line) {
        java.util.ArrayList<String> fields = new java.util.ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    /** A name that can go to the console as it is: 1 to 128 characters, no control or replacement characters. */
    private static boolean isPrintable(String name) {
        if (name.isBlank() || name.length() > 128) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0) || c == '\uFFFD') {
                return false;
            }
        }
        return true;
    }
}
