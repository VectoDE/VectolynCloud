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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
                        + "\"created-at\":\"yesterday\",\"sha256\":\"" + "0".repeat(64) + "\"}]}",
                "{\"schema-version\":1,\"tokens\":[{\"id\":\"00000001\",\"name\":\"a\",\"scopes\":[\"admin\"],"
                        + "\"created-at\":\"2026-10-29T10:00:00Z\",\"expires-at\":\"2026-10-29T09:00:00Z\","
                        + "\"sha256\":\"" + "0".repeat(64) + "\"}]}")) {
            Files.writeString(file, content);
            IOException e = assertThrows(IOException.class, this::open, content);
            assertFalse(e.getMessage().contains(secretLooking));
        }
        // Counter-check: an empty list is fine.
        Files.writeString(file, "{\"schema-version\":1,\"tokens\":[]}");
        assertTrue(open().list().isEmpty());
    }

    @Test
    void aTokenWithALifetimeStopsWorkingWhenItExpires() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-29T10:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        Path file = dir.resolve("secrets/api-tokens.json");
        ApiTokens tokens = ApiTokens.open(file, clock, new SecureRandom());
        ApiTokens.Created created = tokens.create("ci", EnumSet.of(Scope.SERVICES_READ), Duration.ofDays(30));
        String token = created.token().reveal();
        assertEquals(Instant.parse("2026-11-28T10:00:00Z"), created.info().expiresAt());
        assertTrue(tokens.authenticate(token).isPresent());
        assertTrue(tokens.isValid(created.info().id()));

        now.set(Instant.parse("2026-11-28T09:59:59Z"));
        assertTrue(tokens.authenticate(token).isPresent(), "valid up to the last second");
        now.set(Instant.parse("2026-11-28T10:00:00Z"));
        assertTrue(tokens.authenticate(token).isEmpty(), "expired from expires-at on");
        assertFalse(tokens.isValid(created.info().id()));
        assertTrue(tokens.list().getFirst().isExpired(now.get()), "still listed, marked expired");

        // The expiry is stored and survives a restart.
        ApiTokens reopened = ApiTokens.open(file, clock, new SecureRandom());
        assertTrue(reopened.authenticate(token).isEmpty());
        assertEquals(created.info(), reopened.list().getFirst());
        // A token without a lifetime keeps working.
        String forever = tokens.create("forever", EnumSet.of(Scope.NODE_READ)).token().reveal();
        now.set(Instant.parse("2036-10-29T10:00:00Z"));
        assertTrue(tokens.authenticate(forever).isPresent());
        // Revoking an expired token works as for any other.
        assertTrue(tokens.revoke("ci").isPresent());
    }

    @Test
    void lifetimesAreBounded() throws Exception {
        assertEquals(Duration.ofHours(1), ApiTokens.parseLifetime("1h"));
        assertEquals(Duration.ofDays(90), ApiTokens.parseLifetime("90d"));
        assertEquals(Duration.ofDays(3650), ApiTokens.parseLifetime("3650d"));
        for (String bad : new String[] {"0h", "0d", "3651d", "87601h", "90", "d", "-1d", "1w", "01d", " 1d", ""}) {
            assertThrows(IllegalArgumentException.class, () -> ApiTokens.parseLifetime(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ApiTokens.parseLifetime(null));
        ApiTokens tokens = open();
        assertThrows(IllegalArgumentException.class,
                () -> tokens.create("short", EnumSet.of(Scope.NODE_READ), Duration.ofMinutes(59)));
        assertThrows(IllegalArgumentException.class,
                () -> tokens.create("long", EnumSet.of(Scope.NODE_READ), Duration.ofDays(3651)));
        assertTrue(tokens.list().isEmpty(), "nothing was stored");
    }
}
