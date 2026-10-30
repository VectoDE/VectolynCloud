package net.vectolyn.cloud.config.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import net.vectolyn.cloud.config.CloudHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class OwnerOnlyPermissionsTest {

    @TempDir
    Path root;

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void posixSecretsAre700And600() throws Exception {
        SecretStore store = new SecretStore(CloudHome.of(root));
        store.ensureGenerated(new SecretGenerator());
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file().getParent())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())));
        assertTrue(OwnerOnlyPermissions.isOwnerOnly(store.file()));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void posixTooOpenFileIsTightenedOnNextStart() throws Exception {
        SecretStore store = new SecretStore(CloudHome.of(root));
        store.ensureGenerated(new SecretGenerator());
        Files.setPosixFilePermissions(store.file(), PosixFilePermissions.fromString("rw-r--r--"));
        assertFalse(OwnerOnlyPermissions.isOwnerOnly(store.file()));
        store.ensureGenerated(new SecretGenerator());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsSecretsAreOwnerOnlyByAcl() throws Exception {
        SecretStore store = new SecretStore(CloudHome.of(root));
        store.ensureGenerated(new SecretGenerator());
        for (Path path : List.of(store.file().getParent(), store.file())) {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            List<AclEntry> acl = view.getAcl();
            assertEquals(1, acl.size(), path + ": " + acl);
            assertEquals(view.getOwner(), acl.getFirst().principal());
            assertEquals(AclEntryType.ALLOW, acl.getFirst().type());
            assertTrue(OwnerOnlyPermissions.isOwnerOnly(path));
        }
        assertTrue(store.load().hasValue("VECTOLYN_DB_PASSWORD"), "the owner can still read it");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsAclIsProtectedSoParentInheritanceCannotWidenIt() throws Exception {
        Path secrets = Files.createDirectories(root.resolve("secrets"));
        Path file = Files.writeString(secrets.resolve("vectolyn.env"), "KEY=value\n");
        OwnerOnlyPermissions.restrictDirectory(secrets);
        OwnerOnlyPermissions.restrictFile(file);
        assertTrue(isProtected(secrets), "folder ACL must be protected");
        assertTrue(isProtected(file), "file ACL must be protected");

        // An administrator grants "Users: read" on the home and lets it inherit: the folder keeps its list.
        run("icacls", root.toString(), "/grant", "*S-1-5-32-545:(OI)(CI)R");
        assertTrue(OwnerOnlyPermissions.isOwnerOnly(secrets), "protected folder keeps its owner-only list");

        // Counter-check: a second file in the folder, restricted the old way (setAcl only). Windows
        // records its owner entry as inherited from the folder, so the list is not protected.
        Path legacy = Files.writeString(secrets.resolve("legacy.env"), "KEY=value\n");
        AclFileAttributeView view = Files.getFileAttributeView(legacy, AclFileAttributeView.class);
        view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build()));
        assertFalse(isProtected(legacy));

        // Someone widens the secrets folder itself: only the unprotected file takes the new entry.
        run("icacls", secrets.toString(), "/grant", "*S-1-5-32-545:(OI)(CI)R");
        assertFalse(OwnerOnlyPermissions.isOwnerOnly(legacy), "without protection the grant is inherited");
        assertTrue(OwnerOnlyPermissions.isOwnerOnly(file), "protected file keeps its owner-only list");
    }

    private static boolean isProtected(Path path) throws Exception {
        String literal = path.toString().replace("'", "''");
        return run("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-Acl -LiteralPath '" + literal + "').AreAccessRulesProtected").strip().equalsIgnoreCase("True");
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
        assertEquals(0, process.waitFor(), String.join(" ", command) + ": " + output);
        return output;
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsInheritedAccessIsRemoved() throws Exception {
        Path file = Files.writeString(root.resolve("plain.txt"), "x");
        assertFalse(OwnerOnlyPermissions.isOwnerOnly(file), "a temp file normally inherits SYSTEM and Administrators");
        OwnerOnlyPermissions.restrictFile(file);
        assertTrue(OwnerOnlyPermissions.isOwnerOnly(file));
    }
}
