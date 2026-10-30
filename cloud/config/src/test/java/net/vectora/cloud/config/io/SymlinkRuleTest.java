package net.vectora.cloud.config.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.Samples;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.secret.SecretGenerator;
import net.vectora.cloud.config.secret.SecretStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The symbolic link rule (docs/cloud/config.md, "Symbolic links"): configuration files, the folders
 * {@code groups/}, {@code tasks/}, {@code secrets/} and {@code vectora.env} must be real files and
 * folders. A link is an error everywhere, never skipped silently and never followed or replaced.
 * The home itself may be a link.
 *
 * <p>Creating links on Windows needs developer mode or administrator rights; without them the
 * tests are skipped there. Linux CI always runs them.
 */
class SymlinkRuleTest {

    @TempDir
    Path root;

    @TempDir
    Path outside;

    @Test
    void linkedCloudFileIsAnError() throws Exception {
        Path target = outside.resolve("cloud.yml");
        Files.write(target, ConfigYaml.write(Samples.snapshot().cloud()));
        Files.createDirectories(root.resolve("config"));
        link(root.resolve("config/cloud.yml"), target);

        ConfigException e = assertThrows(ConfigException.class, () -> new ConfigStore(CloudHome.of(root)).load());
        assertEquals(List.of(new Expected("cloud.yml")), expected(e));
    }

    @Test
    void danglingLinkIsAnErrorAndNotReplacedWithDefaults() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Path cloud = root.resolve("config/cloud.yml");
        link(cloud, outside.resolve("missing.yml"));

        assertThrows(ConfigException.class, () -> ConfigService.open(CloudHome.of(root)));
        assertTrue(Files.isSymbolicLink(cloud), "the link stays as it is");
        assertFalse(Files.exists(outside.resolve("missing.yml")), "nothing is written through the link");
    }

    @Test
    void linkedGroupFileIsAnErrorNotSkipped() throws Exception {
        Files.createDirectories(root.resolve("config/groups"));
        Path target = outside.resolve("lobby.yml");
        Files.write(target, ConfigYaml.write(Samples.lobbyGroup()));
        link(root.resolve("config/groups/lobby.yml"), target);

        ConfigException e = assertThrows(ConfigException.class, () -> new ConfigStore(CloudHome.of(root)).load());
        assertEquals(List.of(new Expected("groups/lobby.yml")), expected(e));
    }

    @Test
    void linkedTasksFolderIsAnError() throws Exception {
        Files.createDirectories(outside.resolve("tasks"));
        Files.createDirectories(root.resolve("config"));
        link(root.resolve("config/tasks"), outside.resolve("tasks"));

        ConfigException e = assertThrows(ConfigException.class, () -> new ConfigStore(CloudHome.of(root)).load());
        assertEquals(List.of(new Expected("tasks/")), expected(e));
    }

    @Test
    void regularFileWhereAFolderBelongsIsAnError() throws Exception {
        // Not a link, but the same rule: groups/ must be a real folder. Runs on every OS.
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/groups"), "oops");
        ConfigException e = assertThrows(ConfigException.class, () -> new ConfigStore(CloudHome.of(root)).load());
        assertEquals(List.of(new Expected("groups/")), expected(e));
    }

    @Test
    void linkedHomeIsAllowed() throws Exception {
        Path real = outside.resolve("home");
        Files.createDirectories(real);
        Path home = root.resolve("home-link");
        link(home, real);
        try (ConfigService service = ConfigService.open(CloudHome.of(home))) {
            assertTrue(Files.isRegularFile(real.resolve("config/cloud.yml")));
            assertEquals(Samples.snapshot().cloud().schemaVersion(), service.snapshot().cloud().schemaVersion());
        }
    }

    @Test
    void linkedSecretsFileIsRefused() throws Exception {
        Path target = outside.resolve("vectora.env");
        Files.writeString(target, "VECTORA_DB_PASSWORD=abcdefgh\n");
        Files.createDirectories(root.resolve("secrets"));
        link(root.resolve("secrets/vectora.env"), target);
        SecretStore store = new SecretStore(CloudHome.of(root));

        assertThrows(FileSystemException.class, store::load);
        assertThrows(FileSystemException.class, () -> store.ensureGenerated(new SecretGenerator()));
        assertEquals("VECTORA_DB_PASSWORD=abcdefgh\n", Files.readString(target), "the link target is untouched");
    }

    @Test
    void linkedSecretsFolderIsRefused() throws Exception {
        Files.createDirectories(outside.resolve("secrets"));
        link(root.resolve("secrets"), outside.resolve("secrets"));
        SecretStore store = new SecretStore(CloudHome.of(root));

        assertThrows(FileSystemException.class, () -> store.ensureGenerated(new SecretGenerator()));
        try (var files = Files.list(outside.resolve("secrets"))) {
            assertEquals(0, files.count(), "no secret is written through the link");
        }
    }

    private record Expected(String file) {
    }

    private static List<Expected> expected(ConfigException e) {
        for (ConfigError error : e.errors()) {
            assertEquals(ErrorCode.NOT_REGULAR, error.code(), error.toString());
        }
        return e.errors().stream().map(err -> new Expected(err.file())).toList();
    }

    private static void link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic links cannot be created here: " + e);
        }
    }
}
