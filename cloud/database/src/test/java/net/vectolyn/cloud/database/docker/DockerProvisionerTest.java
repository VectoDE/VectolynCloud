package net.vectolyn.cloud.database.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import net.vectolyn.cloud.config.model.DatabaseConfig;
import net.vectolyn.cloud.config.model.DatabaseMode;
import net.vectolyn.cloud.config.secret.Secret;
import net.vectolyn.cloud.database.DatabaseEndpoint;
import net.vectolyn.cloud.database.DatabaseException;
import net.vectolyn.cloud.database.DatabaseSecrets;
import net.vectolyn.cloud.database.FakeSqlServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

class DockerProvisionerTest {

    private static final String ROOT_PW = "RootPasswordForTests0123";
    private static final String APP_PW = "AppPasswordForTests45678";
    private static final DatabaseSecrets SECRETS = new DatabaseSecrets(Secret.of(ROOT_PW), Secret.of(APP_PW));
    private static final DatabaseConfig CONFIG =
            new DatabaseConfig(DatabaseMode.DOCKER, "127.0.0.1", 3308, "luckperms", "luckperms", "mariadb:12.3");

    @TempDir
    Path home;

    private final FakeSqlServer server = new FakeSqlServer();

    @Test
    void composeFileHasNoSecretAndPublishesOnLoopbackOnly() {
        String yaml = DockerComposeFile.render(CONFIG);
        assertFalse(yaml.contains(ROOT_PW));
        JsonNode root = new YAMLMapper().readTree(yaml);
        JsonNode service = root.get("services").get("vectolyn-database");
        assertEquals("mariadb:12.3", service.get("image").asString());
        assertEquals("127.0.0.1:3308:3306", service.get("ports").get(0).asString());
        assertEquals("${VECTOLYN_DB_ROOT_PASSWORD:?missing in secrets/vectolyn.env}",
                service.get("environment").get("MARIADB_ROOT_PASSWORD").asString());
        assertFalse(service.get("environment").has("MARIADB_USER"), "the cloud creates the user itself");
        assertTrue(service.get("command").toString().contains("--max-connections=150"));
        assertFalse(yaml.contains("docker.sock"));
        assertTrue(root.get("volumes").has("vectolyn-database"));
    }

    @Test
    void composeFileRefusesBadInput() {
        assertThrows(IllegalArgumentException.class, () -> DockerComposeFile.render(new DatabaseConfig(DatabaseMode.DOCKER,
                "127.0.0.1", 3308, "lp", "lp", "mariadb:12.3\"\n  privileged: true")));
        assertThrows(IllegalArgumentException.class, () -> DockerComposeFile.render(new DatabaseConfig(DatabaseMode.MANAGED,
                "127.0.0.1", 3308, "lp", "lp", null)));
    }

    @Test
    void notRunningContainerGivesTheStartCommand() throws Exception {
        server.up = false;
        DockerProvisioner p = new DockerProvisioner(CONFIG, SECRETS, home, server, Duration.ofMillis(50));
        DatabaseException e = assertThrows(DatabaseException.class, p::provision);
        assertEquals(DatabaseException.Reason.UNREACHABLE, e.reason());
        assertTrue(e.getMessage().contains(DockerComposeFile.startCommand()), e.getMessage());
        assertTrue(Files.isRegularFile(p.composeFile()));
    }

    @Test
    void runningContainerIsSecuredAndGetsTheUser() throws Exception {
        server.passwords.put("root", ROOT_PW);
        server.accounts.add(List.of("root", "%"));
        server.accounts.add(List.of("root", "localhost"));
        server.accounts.add(List.of("", "%"));
        DockerProvisioner p = new DockerProvisioner(CONFIG, SECRETS, home, server, Duration.ofSeconds(1));
        DatabaseEndpoint endpoint = p.provision();
        List<String> root = server.statementsOf("root");
        assertFalse(root.contains("DROP USER IF EXISTS 'root'@'%'"), "the image's root@% stays");
        assertTrue(root.contains("DROP USER IF EXISTS ''@'%'"));
        assertTrue(root.contains("CREATE USER IF NOT EXISTS 'luckperms'@'%' IDENTIFIED BY '" + APP_PW + "'"));
        assertEquals("127.0.0.1", endpoint.host());
        assertEquals(3308, endpoint.port());
        assertTrue(p.health().healthy());
        p.shutdown();
        assertTrue(server.up, "the cloud never stops the container");
    }
}
