// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.vectolyn.cloud.config.secret.OwnerOnlyPermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiTokensTest {

    @TempDir
    Path dir;

    private ApiTokens open() throws IOException {
        return ApiTokens.open(dir.resolve("secrets/api-tokens.json"),
                Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC), new SecureRandom());
    }

    @Test
    void aTokenIsShownOnceAndStoredOnlyAsHash() throws Exception {
        ApiTokens tokens = open();
        ApiTokens.Created created = tokens.create("dashboard", EnumSet.of(Scope.SERVICES_READ, Scope.CONFIG_READ));
        String token = created.token().reveal();
        assertTrue(token.matches("vct_[A-Za-z0-9_-]{43}"), "format");
        assertEquals("***", created.token().toString());
        String file = Files.readString(tokens.file(), StandardCharsets.UTF_8);
        assertFalse(file.contains(token), "the token must not be stored");
        assertFalse(file.contains(token.substring(4)), "not even without its prefix");
        assertTrue(file.contains("\"sha256\""));
        assertTrue(OwnerOnlyPermissions.isOwnerOnly(tokens.file()));
        // list() never shows the token or its hash.
        assertFalse(tokens.list().toString().contains(token));
        assertEquals(Set.of(Scope.SERVICES_READ, Scope.CONFIG_READ), tokens.list().getFirst().scopes());

        assertEquals("dashboard", tokens.authenticate(token).orElseThrow().name());
        ApiTokens reopened = open();
        assertEquals(created.info(), reopened.authenticate(token).orElseThrow());
    }

    @Test
    void theSetupTokenIsShownOnceStoredAsHashAndClaimedExactlyOnce() throws Exception {
        ApiTokens tokens = open();
        ApiTokens.SetupToken issued = tokens.issueSetupToken("owner", ApiTokens.SETUP_TOKEN_TTL);
        String token = issued.token().reveal();
        assertTrue(token.matches("vcs_[A-Za-z0-9_-]{43}"), "format");
        assertEquals(Instant.parse("2026-10-30T10:00:00Z"), issued.expiresAt(), "24 hours by default");
        Path file = dir.resolve("secrets/" + ApiTokens.SETUP_FILE_NAME);
        String stored = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(stored.contains(token) || stored.contains(token.substring(4)), "only the hash is stored");
        assertTrue(stored.contains("\"sha256\"") && stored.contains("\"admin\" : \"owner\""), stored);
        assertTrue(OwnerOnlyPermissions.isOwnerOnly(file));

        assertTrue(tokens.authenticate(token).isEmpty(), "the setup token is no API token");
        assertEquals("owner", tokens.setupAdmin(token).orElseThrow());
        assertEquals("owner", open().setupAdmin(token).orElseThrow(), "it survives a restart");
        assertTrue(tokens.setupAdmin("vcs_" + "A".repeat(43)).isEmpty());

        ApiTokens.Created admin = tokens.claimSetupToken(token).orElseThrow();
        assertEquals("owner", admin.info().name());
        assertEquals(Set.of(Scope.ADMIN), admin.info().scopes());
        assertEquals(admin.info(), tokens.authenticate(admin.token().reveal()).orElseThrow());
        assertTrue(tokens.claimSetupToken(token).isEmpty(), "used up");
        assertTrue(tokens.setupAdmin(token).isEmpty());
        assertFalse(Files.exists(file), "the file is gone");
        assertTrue(open().setupAdmin(token).isEmpty(), "also after a restart");
    }

    @Test
    void anExpiredOrReplacedSetupTokenFailsAndATakenNameGetsANumber() throws Exception {
        ApiTokens tokens = open();
        String first = tokens.issueSetupToken("admin", java.time.Duration.ofMinutes(5)).token().reveal();
        String second = tokens.issueSetupToken("admin", java.time.Duration.ofMinutes(5)).token().reveal();
        assertTrue(tokens.setupAdmin(first).isEmpty(), "a new setup token replaces the old one");
        tokens.create("admin", EnumSet.of(Scope.SERVICES_READ));
        assertEquals("admin-2", tokens.claimSetupToken(second).orElseThrow().info().name());

        ApiTokens later = ApiTokens.open(dir.resolve("secrets/api-tokens.json"),
                Clock.fixed(Instant.parse("2026-10-29T10:05:00Z"), ZoneOffset.UTC), new SecureRandom());
        String expiring = later.issueSetupToken("admin", java.time.Duration.ofMinutes(5)).token().reveal();
        ApiTokens afterExpiry = ApiTokens.open(dir.resolve("secrets/api-tokens.json"),
                Clock.fixed(Instant.parse("2026-10-29T10:10:00Z"), ZoneOffset.UTC), new SecureRandom());
        assertTrue(afterExpiry.setupAdmin(expiring).isEmpty(), "expired");
        assertTrue(afterExpiry.claimSetupToken(expiring).isEmpty());

        assertThrows(IllegalArgumentException.class, () -> tokens.issueSetupToken("Not Valid", ApiTokens.SETUP_TOKEN_TTL));
        assertThrows(IllegalArgumentException.class, () -> tokens.issueSetupToken("admin", java.time.Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> tokens.issueSetupToken("admin", java.time.Duration.ofDays(8)));
    }

    @Test
    void aMalformedSetupTokenFileIsRefusedWithoutItsContent() throws Exception {
        Path file = dir.resolve("secrets/" + ApiTokens.SETUP_FILE_NAME);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schema-version\":1,\"token\":{\"admin\":\"owner\",\"expires-at\":\"x\",\"sha256\":\"secret-ish\"}}");
        IOException e = assertThrows(IOException.class, this::open);
        assertFalse(e.getMessage().contains("secret-ish"), e.getMessage());
    }

    @Test
    void wrongAndRevokedTokensFail() throws Exception {
        ApiTokens tokens = open();
        String token = tokens.create("script", EnumSet.of(Scope.SERVICES_WRITE)).token().reveal();
        String other = tokens.create("other", EnumSet.of(Scope.ADMIN)).token().reveal();
        List<String> wrong = new ArrayList<>(List.of("", "vct_", "vct_short", token + "x", token.substring(0, 46) + "A",
                token.toUpperCase(java.util.Locale.ROOT), "Bearer " + token, " " + token));
        for (String candidate : wrong) {
            if (!candidate.equals(token)) {
                assertTrue(tokens.authenticate(candidate).isEmpty(), candidate.length() + " characters");
            }
        }
        assertTrue(tokens.authenticate(null).isEmpty());

        List<ApiToken> revoked = new ArrayList<>();
        tokens.onRevoke(revoked::add);
        assertEquals("script", tokens.revoke("script").orElseThrow().name());
        assertTrue(tokens.authenticate(token).isEmpty());
        assertEquals(1, revoked.size());
        assertFalse(tokens.isValid(revoked.getFirst().id()));
        assertTrue(open().authenticate(token).isEmpty(), "revocation is persisted");
        // Counter-check: the other token still works, also by id.
        String otherId = tokens.authenticate(other).orElseThrow().id();
        assertTrue(tokens.isValid(otherId));
        assertTrue(tokens.revoke(otherId).isPresent());
        assertTrue(tokens.revoke("missing").isEmpty());
    }

    @Test
    void namesAndScopesAreChecked() throws Exception {
        ApiTokens tokens = open();
        assertThrows(IllegalArgumentException.class, () -> tokens.create("Bad Name", EnumSet.of(Scope.ADMIN)));
        assertThrows(IllegalArgumentException.class, () -> tokens.create("ok", EnumSet.noneOf(Scope.class)));
        tokens.create("ok", EnumSet.of(Scope.ADMIN));
        assertThrows(IllegalArgumentException.class, () -> tokens.create("ok", EnumSet.of(Scope.ADMIN)));
    }

    @Test
    void scopesAreSeparateAndAdminHasAll() {
        ApiToken reader = new ApiToken("00000001", "r", Set.of(Scope.SERVICES_READ), Instant.EPOCH);
        assertTrue(reader.allows(Scope.SERVICES_READ));
        assertFalse(reader.allows(Scope.SERVICES_WRITE));
        assertFalse(reader.allows(Scope.CONFIG_READ));
        ApiToken writer = new ApiToken("00000002", "w", Set.of(Scope.CONFIG_WRITE), Instant.EPOCH);
        assertFalse(writer.allows(Scope.CONFIG_READ), "write does not include read");
        ApiToken admin = new ApiToken("00000003", "a", Set.of(Scope.ADMIN), Instant.EPOCH);
        for (Scope scope : Scope.values()) {
            assertTrue(admin.allows(scope));
        }
        assertEquals(Scope.CONFIG_WRITE, Scope.of(" Config:Write ").orElseThrow());
        assertTrue(Scope.of("config:*").isEmpty());
    }

    @Test
    void malformedFilesAreRefusedWithoutShowingValues() throws Exception {
        Path file = dir.resolve("secrets/api-tokens.json");
        Files.createDirectories(file.getParent());
        String secretLooking = "vct_" + "S".repeat(43);
        for (String content : List.of("{", "{\"schema-version\":2,\"tokens\":[]}",
                "{\"schema-version\":1,\"tokens\":[],\"extra\":\"" + secretLooking + "\"}",
                "{\"schema-version\":1,\"tokens\":[{\"id\":\"zz\",\"name\":\"a\",\"scopes\":[\"admin\"],"
                        + "\"created-at\":\"2026-10-29T10:00:00Z\",\"sha256\":\"" + "0".repeat(64) + "\"}]}",
                "{\"schema-version\":1,\"tokens\":[{\"id\":\"00000001\",\"name\":\"a\",\"scopes\":[\"root\"],"
                        + "\"created-at\":\"2026-10-29T10:00:00Z\",\"sha256\":\"" + "0".repeat(64) + "\"}]}",
                "{\"schema-version\":1,\"tokens\":[{\"id\":\"00000001\",\"name\":\"a\",\"scopes\":[\"admin\"],"
                        + "\"created-at\":\"yesterday\",\"sha256\":\"" + "0".repeat(64) + "\"}]}")) {
            Files.writeString(file, content);
            IOException e = assertThrows(IOException.class, this::open, content);
            assertFalse(e.getMessage().contains(secretLooking));
        }
        // Counter-check: an empty list is fine.
        Files.writeString(file, "{\"schema-version\":1,\"tokens\":[]}");
        assertTrue(open().list().isEmpty());
    }
}
