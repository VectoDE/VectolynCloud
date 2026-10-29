package net.vectora.cloud.backup.archive;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * What a service backup leaves out. A restore keeps exactly these paths of the current folder, so
 * the server jar, downloaded libraries and logs survive it.
 *
 * <ul>
 *   <li>Folders (at any depth, any case): {@code logs}, {@code cache}, {@code crash-reports},
 *       {@code debug}, {@code libraries}, {@code versions}, {@code bundler}, {@code .paper-remapped},
 *       {@code .fabric}, {@code .mixin.out}, {@code secrets}.</li>
 *   <li>Files: {@code *.jar}, logs ({@code *.log}, {@code *.log.gz}), lock and temp files
 *       ({@code session.lock}, {@code *.lock.db}, {@code *.trace.db}, {@code *.tmp}), and key and
 *       secret files ({@code *.secret}, {@code *.key}, {@code *.pem}, {@code *.p12}, {@code *.jks},
 *       {@code *.env}, {@code .env}). The cloud renders these from {@code secrets/} at every start.</li>
 * </ul>
 *
 * Symbolic links and other non-regular files are never followed or stored; the walker skips them.
 */
public final class BackupFilter {

    private static final Set<String> FOLDERS = Set.of("logs", "cache", "crash-reports", "debug", "libraries",
            "versions", "bundler", ".paper-remapped", ".fabric", ".mixin.out", "secrets");
    private static final Set<String> FILES = Set.of("session.lock", ".env");
    private static final String[] SUFFIXES = {".jar", ".log", ".log.gz", ".lock.db", ".trace.db", ".tmp", ".secret",
        ".key", ".pem", ".p12", ".jks", ".env"};

    private BackupFilter() {
    }

    /**
     * Whether a folder is left out.
     *
     * @param relative the folder relative to the service folder
     * @return whether to skip it and everything below it
     */
    public static boolean excludesFolder(Path relative) {
        Path name = relative.getFileName();
        return name != null && FOLDERS.contains(name.toString().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a file is left out.
     *
     * @param relative the file relative to the service folder
     * @return whether to skip it
     */
    public static boolean excludesFile(Path relative) {
        Path name = relative.getFileName();
        if (name == null) {
            return true;
        }
        String lower = name.toString().toLowerCase(Locale.ROOT);
        if (FILES.contains(lower)) {
            return true;
        }
        for (String suffix : SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
