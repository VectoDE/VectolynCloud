package net.vectora.cloud.template;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedFilesTest {

    private static final byte[] KEY = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, (byte) 0xFF};
    private static final String KEY_B64 = Base64.getEncoder().encodeToString(KEY);
    private static final String FWD = "forwarding-secret-for-tests";

    @TempDir
    Path runtime;

    @Test
    void forwardingSecretIsWrittenAsIs() throws Exception {
        GeneratedFiles.writeForwardingSecret(runtime, "s3cr3t+forwarding/key=");
        assertArrayEquals("s3cr3t+forwarding/key=".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Files.readAllBytes(runtime.resolve("forwarding.secret")));
    }

    @Test
    void secretsAreOnlyReadableByTheOwnerWherePosixPermissionsExist() throws Exception {
        GeneratedFiles.writeForwardingSecret(runtime, FWD);
        GeneratedFiles.writeFloodgateKey(runtime, ServerPlatform.PAPER, KEY_B64);
        if (Files.getFileStore(runtime).supportsFileAttributeView("posix")) {
            EnumSet<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            assertEquals(ownerOnly, Files.getPosixFilePermissions(runtime.resolve("forwarding.secret")));
            assertEquals(ownerOnly, Files.getPosixFilePermissions(runtime.resolve("plugins/floodgate/key.pem")));
        }
    }

    @Test
    void secretsAreOnlyReadableByTheOwnerWhereAccessListsExist() throws Exception {
        GeneratedFiles.writeForwardingSecret(runtime, FWD);
        Path file = runtime.resolve("forwarding.secret");
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null && !Files.getFileStore(runtime).supportsFileAttributeView("posix")) {
            UserPrincipal owner = acl.getOwner();
            assertFalse(acl.getAcl().isEmpty());
            for (AclEntry entry : acl.getAcl()) {
                assertEquals(owner, entry.principal(), "only the owner may have an entry");
            }
        }
    }

    @Test
    void anExistingWorldReadableSecretIsReplacedNotRewrittenInPlace() throws Exception {
        Path file = runtime.resolve("forwarding.secret");
        Files.writeString(file, "old-content-that-is-long-enough");
        if (Files.getFileStore(runtime).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
        GeneratedFiles.writeForwardingSecret(runtime, FWD);
        assertEquals(FWD, Files.readString(file));
        if (Files.getFileStore(runtime).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
        assertNoTempFiles(runtime);
    }

    @Test
    void aHardLinkAtTheTargetIsReplacedAndNotWrittenThrough(@TempDir Path elsewhere) throws Exception {
        Path outside = elsewhere.resolve("victim.txt");
        Files.writeString(outside, "must stay as it is");
        Path link = runtime.resolve("forwarding.secret");
        try {
            Files.createLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.assumeTrue(false, "hard links are not supported here: " + e);
        }
        GeneratedFiles.writeForwardingSecret(runtime, FWD);
        assertEquals(FWD, Files.readString(link));
        assertEquals("must stay as it is", Files.readString(outside), "the other name of the file kept its content");
        assertNoTempFiles(runtime);
    }

    @Test
    void aFailedWriteLeavesTheOldFileAndNoTemporaryFile() throws Exception {
        Path file = runtime.resolve("forwarding.secret");
        Files.writeString(file, "previous-forwarding-secret");
        // A folder where the Floodgate key should go: the write fails after the checks.
        Files.createDirectories(runtime.resolve("plugins/floodgate/key.pem"));
        assertThrows(TemplateException.class,
                () -> GeneratedFiles.writeFloodgateKey(runtime, ServerPlatform.PAPER, KEY_B64));
        assertEquals("previous-forwarding-secret", Files.readString(file));
        assertNoTempFiles(runtime);
        assertNoTempFiles(runtime.resolve("plugins/floodgate"));
    }

    private static void assertNoTempFiles(Path folder) throws java.io.IOException {
        try (var files = Files.list(folder)) {
            List<String> temps = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(RuntimePaths.TEMP_PREFIX)).toList();
            assertEquals(List.of(), temps);
        }
    }

    @Test
    void unsafeForwardingSecretIsAnErrorWithoutTheValue() {
        for (String bad : List.of(" ", "abcdefghijklmnop\nq", "abc #comment\"'", "abcdefghijklmnop #x",
                "abcdefghijklmnop'", "s3cr3t-ÄÖ-s3cr3t-ÄÖ", "fwd")) {
            TemplateException e = assertThrows(TemplateException.class,
                    () -> GeneratedFiles.writeForwardingSecret(runtime, bad), bad);
            assertTrue(e.getMessage().contains("VECTORA_FORWARDING_SECRET"), e.getMessage());
            if (!bad.isBlank()) {
                assertFalse(e.getMessage().contains(bad), e.getMessage());
            }
        }
        assertFalse(Files.exists(runtime.resolve("forwarding.secret")));
    }

    @Test
    void installRefusesAnUnsafeForwardingSecretBeforeWriting() throws Exception {
        Path template = Files.createTempDirectory(runtime, "template");
        Files.writeString(template.resolve("velocity.toml"), "bind = \"0.0.0.0:25565\"\n");
        Path out = runtime.resolve("out");
        PlaceholderValues values = PlaceholderValues.of(Map.of(
                "VECTORA_FORWARDING_SECRET", "abc #comment\"'",
                "VECTORA_FLOODGATE_KEY_B64", KEY_B64));
        assertThrows(TemplateException.class,
                () -> TemplateInstaller.install(template, out, ServerPlatform.VELOCITY, values, true));
        assertFalse(Files.exists(out));
    }

    @Test
    void floodgateKeyIsDecodedToSixteenRawBytesAtThePlatformPath() throws Exception {
        assertEquals("plugins/floodgate/key.pem",
                GeneratedFiles.writeFloodgateKey(runtime, ServerPlatform.VELOCITY, KEY_B64));
        assertEquals("plugins/floodgate/key.pem",
                GeneratedFiles.writeFloodgateKey(runtime, ServerPlatform.PAPER, KEY_B64));
        Path fabric = Files.createDirectories(runtime.resolve("fabric"));
        assertEquals("config/floodgate/key.pem",
                GeneratedFiles.writeFloodgateKey(fabric, ServerPlatform.FABRIC, " " + KEY_B64 + "\n"));

        assertArrayEquals(KEY, Files.readAllBytes(runtime.resolve("plugins/floodgate/key.pem")));
        assertArrayEquals(KEY, Files.readAllBytes(fabric.resolve("config/floodgate/key.pem")));
    }

    @Test
    void floodgateKeyOfTheWrongSizeIsAnErrorWithoutTheValue() {
        String fifteen = Base64.getEncoder().encodeToString(new byte[15]);
        TemplateException e = assertThrows(TemplateException.class,
                () -> GeneratedFiles.writeFloodgateKey(runtime, ServerPlatform.PAPER, fifteen));
        assertFalse(e.getMessage().contains(fifteen), e.getMessage());
        assertTrue(e.getMessage().contains("VECTORA_FLOODGATE_KEY_B64"), e.getMessage());
        assertThrows(TemplateException.class,
                () -> GeneratedFiles.writeFloodgateKey(runtime, ServerPlatform.PAPER, "not base64 !"));
        assertFalse(Files.exists(runtime.resolve("plugins")));
    }

    @Test
    void platformWithoutFloodgateGetsNoKey() {
        assertThrows(IllegalArgumentException.class,
                () -> GeneratedFiles.writeFloodgateKey(runtime, ServerPlatform.NONE, KEY_B64));
    }

    @Test
    void eulaIsOnlyWrittenAfterConsent() throws Exception {
        assertEquals(Optional.empty(), GeneratedFiles.writeEula(runtime, false));
        assertFalse(Files.exists(runtime.resolve("eula.txt")));

        assertEquals(Optional.of("eula.txt"), GeneratedFiles.writeEula(runtime, true));
        String eula = Files.readString(runtime.resolve("eula.txt"));
        assertTrue(eula.endsWith("\neula=true\n"), eula);
        assertTrue(eula.contains("https://aka.ms/MinecraftEULA"), eula);
    }

    @Test
    void eulaWithoutConsentLeavesAnExistingFileAlone() throws Exception {
        Files.writeString(runtime.resolve("eula.txt"), "eula=true\n");
        GeneratedFiles.writeEula(runtime, false);
        assertEquals("eula=true\n", Files.readString(runtime.resolve("eula.txt")));
    }

    @Test
    void installRendersAndGeneratesPerPlatform() throws Exception {
        Path template = Files.createTempDirectory(runtime, "template");
        Files.writeString(template.resolve("server.properties"), "server-port={{VECTORA_SERVER_PORT}}\n");
        Files.createDirectories(template.resolve("plugins/floodgate"));
        Files.writeString(template.resolve("plugins/floodgate/config.yml"), "key-file-name: key.pem\n");
        Path out = runtime.resolve("out");
        PlaceholderValues values = PlaceholderValues.of(Map.of(
                "VECTORA_SERVER_PORT", "25566",
                "VECTORA_FORWARDING_SECRET", FWD,
                "VECTORA_FLOODGATE_KEY_B64", KEY_B64));

        RenderReport report = TemplateInstaller.install(template, out, ServerPlatform.PAPER, values, true);

        // Command blocks are always off; the template did not say so, so the node adds the line.
        assertEquals("server-port=25566\nenable-command-block=false\n", Files.readString(out.resolve("server.properties")));
        assertArrayEquals(KEY, Files.readAllBytes(out.resolve("plugins/floodgate/key.pem")));
        assertTrue(Files.exists(out.resolve("eula.txt")));
        assertFalse(Files.exists(out.resolve("forwarding.secret")));
        // The template has no spigot.yml, so the node writes one with Spigot's restart turned off.
        assertEquals(java.util.List.of("eula.txt", "plugins/floodgate/key.pem", "server.properties", "spigot.yml"),
                report.generated());
    }

    @Test
    void theKeyOfAnEarlierStartIsDeletedOnceTheTemplateDropsFloodgate() throws Exception {
        Path template = Files.createTempDirectory(runtime, "template");
        Files.writeString(template.resolve("server.properties"), "server-port={{VECTORA_SERVER_PORT}}\n");
        Files.createDirectories(template.resolve("plugins/floodgate"));
        Path out = runtime.resolve("out");
        TemplateInstaller.install(template, out, ServerPlatform.PAPER, PlaceholderValues.of(Map.of(
                "VECTORA_SERVER_PORT", "25566", "VECTORA_FLOODGATE_KEY_B64", KEY_B64)), true);
        assertTrue(Files.isRegularFile(out.resolve("plugins/floodgate/key.pem")));
        Files.writeString(out.resolve("plugins/floodgate/config.yml"), "kept\n");

        Files.delete(template.resolve("plugins/floodgate"));
        RenderReport report = TemplateInstaller.install(template, out, ServerPlatform.PAPER,
                PlaceholderValues.of(Map.of("VECTORA_SERVER_PORT", "25566")), true);

        assertFalse(Files.exists(out.resolve("plugins/floodgate/key.pem")), "the old key stayed behind");
        assertEquals("kept\n", Files.readString(out.resolve("plugins/floodgate/config.yml")), "only the key goes");
        // spigot.yml of the first install is right already and is not written again; server.properties
        // is rendered again from the template and gets enable-command-block=false again.
        assertEquals(java.util.List.of("eula.txt", "server.properties"), report.generated());
    }

    @Test
    void aTemplateWithoutFloodgateGetsNoKeyAndNeedsNone() throws Exception {
        // A custom Paper template that does not ship Floodgate's folder runs no Floodgate.
        Path template = Files.createTempDirectory(runtime, "template");
        Files.writeString(template.resolve("server.properties"), "server-port={{VECTORA_SERVER_PORT}}\n");
        Path out = runtime.resolve("out");
        PlaceholderValues values = PlaceholderValues.of(Map.of("VECTORA_SERVER_PORT", "25566"));

        RenderReport report = TemplateInstaller.install(template, out, ServerPlatform.PAPER, values, true);

        assertFalse(Files.exists(out.resolve("plugins/floodgate/key.pem")));
        assertFalse(Files.exists(out.resolve("plugins/floodgate")), "not even the folder");
        assertEquals(java.util.List.of("eula.txt", "server.properties", "spigot.yml"), report.generated());

        // Counter-check: a file named like the folder is no Floodgate folder either.
        Files.createDirectories(template.resolve("plugins"));
        Files.writeString(template.resolve("plugins/floodgate"), "not a folder");
        TemplateInstaller.install(template, runtime.resolve("out2"), ServerPlatform.PAPER,
                PlaceholderValues.of(Map.of("VECTORA_SERVER_PORT", "25566")), true);
        assertFalse(Files.exists(runtime.resolve("out2/plugins/floodgate/key.pem")));
    }

    @Test
    void installOfTheProxyWritesTheForwardingSecretAndNoEula() throws Exception {
        Path template = Files.createTempDirectory(runtime, "template");
        Files.createDirectories(template.resolve("plugins/floodgate"));
        Path out = runtime.resolve("out");
        PlaceholderValues values = PlaceholderValues.of(Map.of(
                "VECTORA_FORWARDING_SECRET", FWD,
                "VECTORA_FLOODGATE_KEY_B64", KEY_B64));

        RenderReport report = TemplateInstaller.install(template, out, ServerPlatform.VELOCITY, values, true);

        assertEquals(FWD, Files.readString(out.resolve("forwarding.secret")));
        assertFalse(Files.exists(out.resolve("eula.txt")));
        assertEquals(java.util.List.of("forwarding.secret", "plugins/floodgate/key.pem"), report.generated());
    }

    @Test
    void installOfTheLegacyProxyWritesItsOwnSecret() throws Exception {
        Path template = Files.createTempDirectory(runtime, "template");
        Path out = runtime.resolve("out");
        String legacy = "LegacySecretForTests0123456789";
        PlaceholderValues values = PlaceholderValues.of(Map.of(
                "VECTORA_FORWARDING_SECRET", FWD,
                "VECTORA_LEGACY_FORWARDING_SECRET", legacy));

        RenderReport report = TemplateInstaller.install(template, out, ServerPlatform.VELOCITY_LEGACY, values, true);

        assertEquals(legacy, Files.readString(out.resolve("forwarding.secret")));
        assertEquals(java.util.List.of("forwarding.secret"), report.generated());
    }

    @Test
    void theLegacyProxyRefusesTheModernSecret() throws Exception {
        Path template = Files.createTempDirectory(runtime, "template");
        Path out = runtime.resolve("out");
        PlaceholderValues same = PlaceholderValues.of(Map.of(
                "VECTORA_FORWARDING_SECRET", FWD,
                "VECTORA_LEGACY_FORWARDING_SECRET", FWD));
        TemplateException e = assertThrows(TemplateException.class,
                () -> TemplateInstaller.install(template, out, ServerPlatform.VELOCITY_LEGACY, same, true));
        assertFalse(e.getMessage().contains(FWD), "the message never shows the secret");
        assertFalse(Files.exists(out.resolve("forwarding.secret")));

        PlaceholderValues missing = PlaceholderValues.of(Map.of("VECTORA_FORWARDING_SECRET", FWD));
        assertThrows(TemplateException.class,
                () -> TemplateInstaller.install(template, out, ServerPlatform.VELOCITY_LEGACY, missing, true));
    }

    @Test
    void installWithoutARequiredSecretFails() throws Exception {
        Path template = Files.createTempDirectory(runtime, "template");
        Files.createDirectories(template.resolve("config/floodgate"));
        TemplateException e = assertThrows(TemplateException.class, () -> TemplateInstaller.install(
                template, runtime.resolve("out"), ServerPlatform.FABRIC, PlaceholderValues.none(), false));
        assertTrue(e.getMessage().contains("VECTORA_FLOODGATE_KEY_B64"), e.getMessage());
    }

    @Test
    void databaseTemplateGetsNoGeneratedFiles() throws Exception {
        Path template = Files.createTempDirectory(runtime, "template");
        RenderReport report = TemplateInstaller.install(
                template, runtime.resolve("out"), ServerPlatform.NONE, PlaceholderValues.none(), true);
        assertEquals(java.util.List.of(), report.generated());
    }
}
