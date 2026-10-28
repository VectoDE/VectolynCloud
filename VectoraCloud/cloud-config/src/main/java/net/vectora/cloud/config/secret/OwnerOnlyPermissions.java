package net.vectora.cloud.config.secret;

import java.io.IOException;
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
import java.util.Set;

/**
 * Restricts files and folders to their owner.
 *
 * <ul>
 *   <li>POSIX (Linux, macOS): folders {@code 700}, files {@code 600}.</li>
 *   <li>Windows (ACL): the access list is replaced by a single entry that gives the owner full
 *       control; on folders it is inherited by new files. Inherited entries of the parent folder
 *       (for example "Users: read") are dropped. Note: Windows applies this as an explicit list but
 *       does not mark it protected, so an administrator who later re-applies inheritance on a parent
 *       folder can widen it again.</li>
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
        }
    }

    private static PosixFileAttributeView posix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static AclFileAttributeView acl(Path path) {
        return Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    }
}
