package net.vectora.cloud.config.secret;

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
import net.vectora.cloud.config.CloudHome;
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
        assertTrue(store.load().hasValue("VECTORA_DB_PASSWORD"), "the owner can still read it");
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
