package net.vectora.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.protocol.NodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JoinTokensTest {

    private static final NodeId NODE = new NodeId("node-2");
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    @TempDir
    Path dir;

    private JoinTokens tokens() {
        return new JoinTokens(dir.resolve("cluster").resolve("join-tokens.yml"));
    }

    private static String[] split(JoinTokens.Created created) {
        return JoinTokens.split(created.token().reveal()).orElseThrow();
    }

    @Test
    void aTokenOpensOneJoin() throws Exception {
        JoinTokens tokens = tokens();
        String[] t = split(tokens.create(NODE, Duration.ofHours(1), NOW));
        assertEquals(JoinTokens.Redemption.VALID, tokens.redeem(t[0], Secret.of(t[1]), NODE, NOW));
        assertEquals(JoinTokens.Redemption.INVALID, tokens.redeem(t[0], Secret.of(t[1]), NODE, NOW));
        assertTrue(tokens.list(NOW).isEmpty());
    }

    @Test
    void anExpiredTokenIsInvalid() throws Exception {
        JoinTokens tokens = tokens();
        String[] t = split(tokens.create(NODE, Duration.ofMinutes(5), NOW));
        assertEquals(JoinTokens.Redemption.INVALID, tokens.redeem(t[0], Secret.of(t[1]), NODE, NOW.plusSeconds(301)));
        assertTrue(tokens.list(NOW).isEmpty(), "an expired token is dropped from the file");
    }

    @Test
    void theRightSecretForAnotherNodeUsesTheTokenUp() throws Exception {
        JoinTokens tokens = tokens();
        String[] t = split(tokens.create(NODE, Duration.ofHours(1), NOW));
        assertEquals(JoinTokens.Redemption.INVALID, tokens.redeem(t[0], Secret.of(t[1]), new NodeId("node-3"), NOW));
        assertEquals(JoinTokens.Redemption.INVALID, tokens.redeem(t[0], Secret.of(t[1]), NODE, NOW));
    }

    @Test
    void threeWrongSecretsUseTheTokenUp() throws Exception {
        JoinTokens tokens = tokens();
        String[] t = split(tokens.create(NODE, Duration.ofHours(1), NOW));
        Secret wrong = Secret.of("0".repeat(32));
        assertEquals(JoinTokens.Redemption.INVALID, tokens.redeem(t[0], wrong, NODE, NOW));
        assertEquals(JoinTokens.Redemption.INVALID, tokens.redeem(t[0], wrong, NODE, NOW));
        assertEquals(1, tokens.list(NOW).size(), "two wrong secrets keep it");
        assertEquals(JoinTokens.Redemption.INVALID, tokens.redeem(t[0], wrong, NODE, NOW));
        assertTrue(tokens.list(NOW).isEmpty());
        assertEquals(JoinTokens.Redemption.INVALID, tokens.redeem(t[0], Secret.of(t[1]), NODE, NOW));
    }

    @Test
    void anUnknownIdIsInvalid() throws Exception {
        assertEquals(JoinTokens.Redemption.INVALID, tokens().redeem("abcdef", Secret.of("a".repeat(32)), NODE, NOW));
    }

    @Test
    void theFileHoldsNoSecretAndIsOwnerOnly() throws Exception {
        JoinTokens tokens = tokens();
        JoinTokens.Created created = tokens.create(NODE, Duration.ofHours(1), NOW);
        Path file = dir.resolve("cluster").resolve("join-tokens.yml");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(content.contains(split(created)[1]));
        assertTrue(content.contains(created.info().id()));
        assertFalse(created.toString().contains(split(created)[1]), "toString redacts the token");
        assertTrue(net.vectora.cloud.config.secret.OwnerOnlyPermissions.isOwnerOnly(file)
                || !net.vectora.cloud.config.secret.OwnerOnlyPermissions.isOwnerOnly(dir), "owner only where possible");
    }

    @Test
    void lifetimeAndCountAreLimited() throws Exception {
        JoinTokens tokens = tokens();
        assertThrows(IllegalArgumentException.class, () -> tokens.create(NODE, Duration.ofSeconds(59), NOW));
        assertThrows(IllegalArgumentException.class, () -> tokens.create(NODE, Duration.ofHours(25), NOW));
        for (int i = 0; i < JoinTokens.MAX_TOKENS; i++) {
            tokens.create(NODE, Duration.ofHours(1), NOW);
        }
        assertThrows(IllegalStateException.class, () -> tokens.create(NODE, Duration.ofHours(1), NOW));
    }

    @Test
    void writtenTokensSplit() {
        assertTrue(JoinTokens.split("abc123." + "x".repeat(32)).isPresent());
        assertFalse(JoinTokens.split("ABC123." + "x".repeat(32)).isPresent());
        assertFalse(JoinTokens.split("abc123" + "x".repeat(32)).isPresent());
        assertFalse(JoinTokens.split("abc123." + "x".repeat(31)).isPresent());
        assertFalse(JoinTokens.split(null).isPresent());
    }
}
