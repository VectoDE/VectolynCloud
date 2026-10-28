package net.vectora.cloud.config.secret;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Restricts files and folders to their owner.
 *
 * <ul>
 *   <li>POSIX (Linux, macOS): folders {@code 700}, files {@code 600}.</li>
 *   <li>Windows (ACL): the access list is replaced by a single entry that gives the owner full
 *       control; on folders it is inherited by new files. Inherited entries of the parent folder
 *       (for example "Users: read") are dropped. The list is then marked protected
 *       ({@code icacls /inheritance:d}), so entries an administrator later adds to a parent folder
 *       are not inherited. Java's ACL API cannot set that flag itself.</li>
 *   <li>Other file systems (FAT, some network shares): nothing can be restricted;
 *       {@link #isOwnerOnly(Path)} then returns {@code false}.</li>
 * </ul>
 */
public final class OwnerOnlyPermissions {

    private static final Set<PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");

    private OwnerOnlyPermissions() {
    }

    /** Restricts a folder to its owner ({@code 700}). */
    public static void restrictDirectory(Path dir) throws IOException {
        restrict(dir, true);
    }

    /** Restricts a file to its owner ({@code 600}). */
    public static void restrictFile(Path file) throws IOException {
        restrict(file, false);
    }

    /** Whether only the owner has access. */
    public static boolean isOwnerOnly(Path path) throws IOException {
        PosixFileAttributeView posix = posix(path);
        if (posix != null) {
            Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
            return permissions.stream().allMatch(p -> p.name().startsWith("OWNER_"));
        }
        AclFileAttributeView acl = acl(path);
        if (acl != null) {
            UserPrincipal owner = acl.getOwner();
            return acl.getAcl().stream().allMatch(e -> e.principal().equals(owner) || e.type() == AclEntryType.DENY);
        }
        return false;
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix = posix(path);
        if (posix != null) {
            posix.setPermissions(directory ? DIRECTORY : FILE);
            return;
        }
        AclFileAttributeView acl = acl(path);
        if (acl != null) {
            AclEntry.Builder entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) {
                entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            }
            acl.setAcl(List.of(entry.build()));
            if (isWindows()) {
                protectAcl(path);
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * Marks the access list of {@code path} protected, so it no longer takes entries inherited from
     * the parent folder. {@code /inheritance:d} keeps the current entries as explicit ones. It must
     * not be {@code /inheritance:r}: when the parent folder already passes the same owner entry
     * down, Windows records the entry set by {@link AclFileAttributeView#setAcl} as inherited, and
     * {@code /inheritance:r} would leave an empty list that locks out the owner too. Java's
     * {@code setAcl} clears the flag on files again, so this runs after it. icacls is called by its full path in
     * {@code %SystemRoot%\System32}, without a shell; the path is a single argument.
     */
    private static void protectAcl(Path path) throws IOException {
        String systemRoot = System.getenv("SystemRoot");
        Path icacls = Path.of(systemRoot == null || systemRoot.isBlank() ? "C:\\Windows" : systemRoot, "System32", "icacls.exe");
        Process process = new ProcessBuilder(icacls.toString(), path.toAbsolutePath().toString(), "/inheritance:d", "/q")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("icacls did not finish in time while protecting " + path);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while protecting " + path);
        }
        if (process.exitValue() != 0) {
            throw new IOException("icacls could not protect the access list of " + path + " (exit code " + process.exitValue() + ")");
        }
    }

    private static PosixFileAttributeView posix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static AclFileAttributeView acl(Path path) {
        return Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    }
}
