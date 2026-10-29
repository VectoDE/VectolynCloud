package net.vectora.cloud.setup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.io.AtomicFileWriter;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.config.secret.SecretStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The writer behind both ways of the setup: checks first, configuration next, secrets only after it. */
class SetupApplierTest {

    @TempDir
    Path root;

    private ConfigService config;
    private SecretStore secrets;
    private SetupEnvironment env;

    @BeforeEach
    void open() throws Exception {
        config = ConfigService.open(CloudHome.of(root));
        secrets = new SecretStore(config.home());
        env = InteractiveSetupTest.env(false, true);
    }

    @AfterEach
    void close() throws IOException {
        config.close();
    }

    private SetupAnswers answers() {
        return SetupPlan.defaults(config.snapshot(), env).withEula(true).withNetworkName("Applied");
    }

    private SetupAnswers external(String password) {
        DatabaseConfig db = SetupPlan.switchMode(answers().database(), DatabaseMode.EXTERNAL);
        return answers().withDatabase(db, Optional.of(Secret.of(password)));
    }

    @Test
    void aDeclinedEulaIsRefusedAndWritesNothing() throws Exception {
        byte[] before = Files.readAllBytes(config.home().cloudFile());
        Set<String> groups = config.snapshot().groups().keySet();

        SetupException e = assertThrows(SetupException.class,
                () -> SetupApplier.apply(config, secrets, answers().withEula(false), env));

        assertTrue(e.problems().contains(SetupMessages.FILE_EULA), e.describe(Language.EN));
        assertArrayEquals(before, Files.readAllBytes(config.home().cloudFile()), "cloud.yml unchanged");
        assertEquals(groups, config.snapshot().groups().keySet(), "no group created");
        assertFalse(Files.exists(config.home().secretsFile()), "no secrets written");
        assertFalse(config.snapshot().cloud().eulaAccepted());
        assertFalse(config.snapshot().cloud().setupComplete());

        // Counter-check: the same answers with the EULA accepted complete the setup.
        assertEquals(SetupResult.Status.COMPLETED, SetupApplier.apply(config, secrets, answers(), env).status());
        assertTrue(config.snapshot().cloud().eulaAccepted());
        assertTrue(config.snapshot().cloud().setupComplete());
    }

    @Test
    void aFailingSecretWriteRollsTheConfigurationBack() throws Exception {
        ConfigSnapshot before = config.snapshot();
        byte[] cloudBefore = Files.readAllBytes(config.home().cloudFile());
        // The configuration can be written, the secrets file cannot (a full disk, for example).
        AtomicBoolean broken = new AtomicBoolean(true);
        SecretStore failing = new SecretStore(config.home().secretsFile(), new AtomicFileWriter((from, to, options) -> {
            if (broken.get()) {
                throw new IOException("test: the disk is full");
            }
            Files.move(from, to, options);
        }));

        IOException e = assertThrows(IOException.class,
                () -> SetupApplier.apply(config, failing, external("first-Pw-42"), env));
        assertEquals("test: the disk is full", e.getMessage());

        assertArrayEquals(cloudBefore, Files.readAllBytes(config.home().cloudFile()), "cloud.yml back as before");
        assertEquals(before.groups().keySet(), config.snapshot().groups().keySet(), "created groups removed again");
        assertEquals(before.tasks().keySet(), config.snapshot().tasks().keySet(), "created tasks removed again");
        assertFalse(config.snapshot().cloud().setupComplete());
        assertEquals(before.cloud().database(), config.snapshot().cloud().database(), "no host without its password");

        // Counter-check: once the disk works, the same answers are written completely.
        broken.set(false);
        assertEquals(SetupResult.Status.COMPLETED,
                SetupApplier.apply(config, failing, external("first-Pw-42"), env).status());
        assertEquals(DatabaseMode.EXTERNAL, config.snapshot().cloud().database().mode());
        assertEquals("Applied", config.snapshot().cloud().networkName());
        assertEquals("first-Pw-42", secrets.load().get("VECTORA_DB_PASSWORD").orElseThrow().reveal());
    }

    @Test
    void aConfigurationThatCannotBeWrittenWritesNoSecret() throws Exception {
        // The first setup creates the lobby group; a folder where its file belongs makes that write fail.
        Path groupFile = config.home().root().resolve(CloudHome.GROUPS_DIR).resolve("lobby" + CloudHome.EXTENSION);
        Files.createDirectories(groupFile);

        assertThrows(IOException.class, () -> SetupApplier.apply(config, secrets, external("first-Pw-42"), env));

        assertFalse(Files.exists(config.home().secretsFile()), "the password is only written after the configuration");
        assertFalse(config.snapshot().cloud().setupComplete());

        // Counter-check: once the folder is gone, the setup goes through with its password.
        Files.delete(groupFile);
        assertEquals(SetupResult.Status.COMPLETED,
                SetupApplier.apply(config, secrets, external("first-Pw-42"), env).status());
        assertEquals("first-Pw-42", secrets.load().get("VECTORA_DB_PASSWORD").orElseThrow().reveal());
    }
}
