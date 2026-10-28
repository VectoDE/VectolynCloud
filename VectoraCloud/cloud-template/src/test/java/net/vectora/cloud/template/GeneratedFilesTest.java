package net.vectora.cloud.template;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        Path out = runtime.resolve("out");
        PlaceholderValues values = PlaceholderValues.of(Map.of(
                "VECTORA_SERVER_PORT", "25566",
                "VECTORA_FORWARDING_SECRET", FWD,
                "VECTORA_FLOODGATE_KEY_B64", KEY_B64));

        RenderReport report = TemplateInstaller.install(template, out, ServerPlatform.PAPER, values, true);

        assertEquals("server-port=25566\n", Files.readString(out.resolve("server.properties")));
        assertArrayEquals(KEY, Files.readAllBytes(out.resolve("plugins/floodgate/key.pem")));
        assertTrue(Files.exists(out.resolve("eula.txt")));
        assertFalse(Files.exists(out.resolve("forwarding.secret")));
        assertEquals(java.util.List.of("eula.txt", "plugins/floodgate/key.pem"), report.generated());
    }

    @Test
    void installOfTheProxyWritesTheForwardingSecretAndNoEula() throws Exception {
        Path template = Files.createTempDirectory(runtime, "template");
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
    void installWithoutARequiredSecretFails() throws Exception {
        Path template = Files.createTempDirectory(runtime, "template");
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
