// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.auth.ApiTokens;
import net.vectolyn.cloud.api.http.ApiException;
import net.vectolyn.cloud.api.http.ApiJson;
import net.vectolyn.cloud.api.http.ApiResponse;
import net.vectolyn.cloud.panel.auth.AuditLog;
import net.vectolyn.cloud.panel.auth.OidcClient;
import net.vectolyn.cloud.panel.auth.OidcClientTest;
import net.vectolyn.cloud.panel.auth.PanelUsers;
import net.vectolyn.cloud.panel.auth.TestAuthenticator;
import net.vectolyn.cloud.panel.auth.Totp;
import net.vectolyn.cloud.panel.config.PanelConfig;
import net.vectolyn.cloud.panel.config.PanelConfigLoader;
import net.vectolyn.cloud.panel.web.WebAssets;
import net.vectolyn.cloud.config.secret.Secret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The account endpoints, called the way the HTTP handler calls them. */
class PanelApiTest {

    private static final String HOST = "localhost:8090";
    private static final String ORIGIN = "http://localhost:8090";
    private static final String PASSWORD = "a long enough phrase";

    @TempDir
    Path home;

    /** The node's API as the panel sees it: one setup token, and calls that report their principal. */
    static final class FakeNode implements NodeAccess {

        boolean setupUsed;
        final AtomicReference<ApiToken> lastPrincipal = new AtomicReference<>();

        @Override
        public <T> Optional<T> useSetupToken(String token, ApiTokens.SetupUse<T> use) throws IOException {
            if (setupUsed || !"vcs_good".equals(token)) {
                return Optional.empty();
            }
            T result = use.apply("owner");
            setupUsed = true;
            return Optional.of(result);
        }

        @Override
        public ApiResponse call(ApiToken principal, String method, String target, byte[] body, String ifMatch)
                throws ApiException {
            lastPrincipal.set(principal);
            if (target.startsWith("/config")) {
                throw new ApiException(403, "missing-scope", "no");
            }
            return ApiResponse.ok(Map.of("target", target));
        }
    }

    /** A browser: keeps the session cookie and the CSRF token. */
    final class Browser {

        String session;
        String csrf;
        String oidcCookie;

        Reply get(String path) {
            return send("GET", path, null, false);
        }

        Reply post(String path, Map<String, ?> body) {
            return send("POST", path, body, true);
        }

        Reply send(String method, String path, Map<String, ?> body, boolean withCsrf) {
            HttpHeaders headers = new DefaultHttpHeaders();
            headers.set("Host", HOST);
            if (!method.equals("GET")) {
                headers.set("Origin", ORIGIN);
                headers.set("Sec-Fetch-Site", "same-origin");
            }
            if (withCsrf) {
                headers.set(PanelApi.CSRF_HEADER, csrf == null ? "1" : csrf);
            }
            List<String> cookies = new ArrayList<>();
            if (session != null) {
                cookies.add(PanelApi.SESSION_COOKIE + "=" + session);
            }
            if (oidcCookie != null) {
                cookies.add(PanelApi.OIDC_COOKIE + "=" + oidcCookie);
            }
            if (!cookies.isEmpty()) {
                headers.set("Cookie", String.join("; ", cookies));
            }
            int q = path.indexOf('?');
            Map<String, String> query = new LinkedHashMap<>();
            if (q >= 0) {
                for (String pair : path.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    query.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
            byte[] bytes = body == null ? new byte[0] : ApiJson.write(body);
            FullHttpResponse response = api.handle(new PanelApi.Call(method, q < 0 ? path : path.substring(0, q), query,
                    q < 0 ? "" : path.substring(q + 1), bytes, headers, "127.0.0.1"));
            Reply reply = new Reply(response.status().code(), response.content().toString(StandardCharsets.UTF_8),
                    response.headers().getAll("Set-Cookie"), response.headers().get("Location"));
            response.release();
            for (String cookie : reply.cookies()) {
                assertTrue(cookie.contains("HttpOnly") && cookie.contains("Secure") && cookie.contains("SameSite="), cookie);
                if (cookie.startsWith(PanelApi.SESSION_COOKIE + "=")) {
                    String value = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
                    session = value.isEmpty() ? null : value;
                    assertTrue(cookie.contains("SameSite=Strict"), cookie);
                }
                if (cookie.startsWith(PanelApi.OIDC_COOKIE + "=")) {
                    String value = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
                    oidcCookie = value.isEmpty() ? null : value;
                }
            }
            if (reply.status() == 200 && reply.json().get("csrf") instanceof String token) {
                csrf = token;
            }
            return reply;
        }
    }

    record Reply(int status, String body, List<String> cookies, String location) {

        @SuppressWarnings("unchecked")
        Map<String, Object> json() {
            try {
                return ApiJson.mapper().readValue(body, Map.class);
            } catch (RuntimeException e) {
                return Map.of();
            }
        }
    }

    private PanelApi api;
    private FakeNode node;
    private PanelUsers users;
    private AuditLog audit;

    private void start(OidcClient oidc) throws Exception {
        users = PanelUsers.load(home.resolve("secrets").resolve(PanelUsers.FILE_NAME));
        audit = new AuditLog(home.resolve("logs").resolve(AuditLog.FILE_NAME), Clock.systemUTC());
        node = new FakeNode();
        AtomicLong nanos = new AtomicLong();
        api = new PanelApi(users, audit, node, oidc, Clock.systemUTC(), nanos::get);
        PanelConfigLoader.Settings settings = new PanelConfigLoader.Settings(
                new PanelConfig(1, true, "127.0.0.1", 0, null), Optional.empty());
        PanelServer.builder(settings).assets(WebAssets.of(Map.of("index.html", "<p>x</p>".getBytes(StandardCharsets.UTF_8)), false))
                .api(api).build();
    }

    private Browser owner() {
        Browser browser = new Browser();
        Reply setup = browser.post("/api/panel/setup", Map.of("token", "vcs_good", "password", PASSWORD));
        assertEquals(200, setup.status(), setup.body());
        return browser;
    }

    @Test
    void theFirstAccountNeedsTheSetupTokenAndOnlyOnce() throws Exception {
        start(null);
        Browser browser = new Browser();
        assertEquals(true, browser.get("/api/panel/info").json().get("setup-required"));
        assertEquals(401, browser.post("/api/panel/setup", Map.of("token", "vcs_wrong", "password", PASSWORD)).status());
        assertEquals(400, browser.post("/api/panel/setup", Map.of("token", "vcs_good", "password", "short")).status());
        Reply ok = browser.post("/api/panel/setup", Map.of("token", "vcs_good", "password", PASSWORD));
        assertEquals(200, ok.status(), ok.body());
        assertEquals("owner", ok.json().get("user"));
        assertEquals(409, new Browser().post("/api/panel/setup", Map.of("token", "vcs_good", "password", PASSWORD)).status());
        Map<String, Object> session = browser.get("/api/panel/session").json();
        assertEquals(List.of("owner"), session.get("roles"));
        assertTrue(((List<?>) session.get("rights")).contains("users:manage"));
        assertEquals(false, browser.get("/api/panel/info").json().get("setup-required"));
        String file = Files.readString(home.resolve("secrets").resolve(PanelUsers.FILE_NAME));
        assertFalse(file.contains(PASSWORD), "only the hash is stored");
        assertTrue(Files.readString(home.resolve("logs").resolve(AuditLog.FILE_NAME)).contains("\"event\":\"setup\""));
    }

    @Test
    void stateChangesNeedTheOwnOriginAndTheCsrfToken() throws Exception {
        start(null);
        Browser browser = owner();
        Browser noCsrf = new Browser();
        noCsrf.session = browser.session;
        Reply missing = noCsrf.send("POST", "/api/panel/logout", Map.of(), false);
        assertEquals(403, missing.status());
        noCsrf.csrf = "wrong";
        assertEquals(403, noCsrf.post("/api/panel/logout", Map.of()).status(), "a token of no session");

        HttpHeaders evil = new DefaultHttpHeaders();
        evil.set("Host", HOST);
        evil.set("Origin", "https://evil.example");
        evil.set(PanelApi.CSRF_HEADER, browser.csrf);
        evil.set("Cookie", PanelApi.SESSION_COOKIE + "=" + browser.session);
        FullHttpResponse refused = api.handle(new PanelApi.Call("POST", "/api/panel/logout", Map.of(), "", new byte[0], evil, "x"));
        assertEquals(403, refused.status().code());
        refused.release();
        evil.set("Origin", ORIGIN);
        evil.set("Sec-Fetch-Site", "cross-site");
        FullHttpResponse crossSite = api.handle(new PanelApi.Call("POST", "/api/panel/logout", Map.of(), "", new byte[0], evil, "x"));
        assertEquals(403, crossSite.status().code());
        crossSite.release();

        assertEquals(null, new Browser().get("/api/panel/session").json().get("user"), "no cookie, no session");
        assertEquals(200, browser.post("/api/panel/logout", Map.of()).status());
        assertEquals(null, browser.get("/api/panel/session").json().get("user"));
    }

    @Test
    void wrongPasswordsLockTheAccountAndAreAudited() throws Exception {
        start(null);
        owner();
        Browser attacker = new Browser();
        for (int i = 0; i < 5; i++) {
            assertEquals(401, attacker.post("/api/panel/login", Map.of("name", "owner", "password", "wrong password!")).status());
        }
        Reply locked = attacker.post("/api/panel/login", Map.of("name", "owner", "password", PASSWORD));
        assertEquals(429, locked.status(), "even the right password waits");
        String log = Files.readString(home.resolve("logs").resolve(AuditLog.FILE_NAME));
        assertTrue(log.contains("\"event\":\"login.failed\""));
        assertTrue(log.contains("\"event\":\"account.locked\""));
        assertFalse(log.contains("wrong password!"), "no secret in the audit log");
    }

    @Test
    void totpBecomesTheSecondStepOfTheSignIn() throws Exception {
        start(null);
        Browser browser = owner();
        Reply started = browser.post("/api/panel/account/totp/start", Map.of());
        String secret = (String) started.json().get("secret");
        String code = code(secret, 0);
        assertEquals(400, browser.post("/api/panel/account/totp/confirm", Map.of("ticket", started.json().get("ticket"),
                "code", code.equals("000000") ? "111111" : "000000")).status());
        Reply again = browser.post("/api/panel/account/totp/start", Map.of());
        secret = (String) again.json().get("secret");
        Reply confirmed = browser.post("/api/panel/account/totp/confirm", Map.of("ticket", again.json().get("ticket"),
                "code", code(secret, 0)));
        assertEquals(200, confirmed.status(), confirmed.body());
        assertEquals(true, confirmed.json().get("totp"));

        Browser fresh = new Browser();
        Reply first = fresh.post("/api/panel/login", Map.of("name", "OWNER", "password", PASSWORD));
        assertEquals("totp", first.json().get("second-factor"));
        assertTrue(fresh.session == null, "no session before the second factor");
        assertEquals(401, fresh.post("/api/panel/login/totp", Map.of("ticket", first.json().get("ticket"), "code", code(secret, 0))).status(),
                "the code of the confirmation does not work twice");
        Reply second = fresh.post("/api/panel/login", Map.of("name", "owner", "password", PASSWORD));
        Reply done = fresh.post("/api/panel/login/totp", Map.of("ticket", second.json().get("ticket"), "code", code(secret, 1)));
        assertEquals(200, done.status(), done.body());
        assertNotNull(fresh.session);
    }

    private static String code(String secret, int stepsAhead) {
        byte[] key = Totp.decodeBase32(secret).orElseThrow();
        long step = Math.floorDiv(Clock.systemUTC().instant().getEpochSecond(), 30) + stepsAhead;
        try {
            var method = Totp.class.getDeclaredMethod("code", byte[].class, long.class);
            method.setAccessible(true);
            return String.format("%06d", (int) method.invoke(null, key, step));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void passkeysRegisterAndSignIn() throws Exception {
        start(null);
        Browser browser = owner();
        Reply options = browser.post("/api/panel/account/passkeys/options", Map.of());
        assertEquals(200, options.status(), options.body());
        TestAuthenticator authenticator = new TestAuthenticator("localhost");
        TestAuthenticator.Created created = authenticator.create((String) options.json().get("challenge"), ORIGIN);
        Reply added = browser.post("/api/panel/account/passkeys", Map.of("ticket", options.json().get("ticket"),
                "client-data-json", TestAuthenticator.b64(created.clientDataJson()),
                "attestation-object", TestAuthenticator.b64(created.attestationObject()), "label", "Laptop"));
        assertEquals(200, added.status(), added.body());

        Browser fresh = new Browser();
        Reply login = fresh.post("/api/panel/passkey/options", Map.of());
        assertEquals("localhost", login.json().get("rp-id"));
        TestAuthenticator.Assertion assertion = authenticator.get((String) login.json().get("challenge"), ORIGIN);
        Map<String, Object> body = Map.of("ticket", login.json().get("ticket"), "id", authenticator.id(),
                "client-data-json", TestAuthenticator.b64(assertion.clientDataJson()),
                "authenticator-data", TestAuthenticator.b64(assertion.authenticatorData()),
                "signature", TestAuthenticator.b64(assertion.signature()));
        Reply signedIn = fresh.post("/api/panel/passkey/login", body);
        assertEquals(200, signedIn.status(), signedIn.body());
        assertEquals(401, new Browser().post("/api/panel/passkey/login", body).status(), "the ticket is used up");
    }

    @Test
    void rolesLimitTheApiAndNobodyGivesMoreThanTheyHold() throws Exception {
        start(null);
        Browser owner = owner();
        assertEquals(200, owner.post("/api/panel/roles", Map.of("name", "helpdesk",
                "rights", List.of("services:read", "users:manage"))).status());
        assertEquals(200, owner.post("/api/panel/users", Map.of("name", "dana", "password", PASSWORD,
                "roles", List.of("helpdesk"))).status());
        Browser dana = new Browser();
        assertEquals(200, dana.post("/api/panel/login", Map.of("name", "dana", "password", PASSWORD)).status());
        assertEquals(200, dana.get("/api/v1/services").status());
        assertEquals(java.util.Set.of(net.vectolyn.cloud.api.auth.Scope.SERVICES_READ), node.lastPrincipal.get().scopes());
        assertEquals("local:dana", node.lastPrincipal.get().id());
        assertEquals(403, dana.get("/api/panel/audit").status(), "no audit:read");
        assertEquals(403, dana.post("/api/panel/users/update", Map.of("name", "dana", "roles", List.of("owner"))).status(),
                "no way up to owner");
        assertEquals(403, dana.post("/api/panel/roles", Map.of("name", "more", "rights", List.of("admin"))).status());
        assertEquals(403, dana.post("/api/panel/users/delete", Map.of("name", "owner")).status());
        assertEquals(409, owner.post("/api/panel/users/update", Map.of("name", "owner", "roles", List.of("viewer"))).status(),
                "the last owner stays");
        assertEquals(409, owner.post("/api/panel/roles/delete", Map.of("name", "helpdesk")).status(), "role in use");
        assertEquals(200, owner.post("/api/panel/users/update", Map.of("name", "dana", "disabled", true)).status());
        assertEquals(null, dana.get("/api/panel/session").json().get("user"), "a disabled user is signed out at once");
        String log = Files.readString(home.resolve("logs").resolve(AuditLog.FILE_NAME));
        assertTrue(log.contains("\"event\":\"role.saved\"") && log.contains("\"event\":\"user.created\"")
                && log.contains("\"event\":\"user.updated\""), log);
        Map<String, Object> entries = owner.get("/api/panel/audit?limit=5").json();
        assertEquals(5, ((List<?>) entries.get("entries")).size());
    }

    @Test
    void singleSignOnSignsInLinkedAccountsOnly() throws Exception {
        try (OidcClientTest.TestProvider provider = new OidcClientTest.TestProvider()) {
            start(new OidcClient(new PanelConfig.Oidc(provider.issuer, "panel", "Company login", null),
                    Secret.of("s"), ORIGIN, Clock.systemUTC()));
            Browser owner = owner();
            Browser stranger = new Browser();
            Reply start = stranger.get("/api/panel/oidc/start");
            assertEquals(303, start.status());
            String state = query(start.location(), "state");
            provider.claims.set(provider.idToken("u-9", query(start.location(), "nonce")));
            Reply refused = stranger.get("/api/panel/oidc/callback?state=" + state + "&code=c");
            assertEquals("/login/?error=oidc-not-linked", refused.location());
            assertTrue(stranger.session == null);

            Reply link = owner.post("/api/panel/account/oidc/start", Map.of());
            String url = (String) link.json().get("url");
            provider.claims.set(provider.idToken("u-9", query(url, "nonce")));
            Reply linked = owner.get("/api/panel/oidc/callback?state=" + query(url, "state") + "&code=c");
            assertEquals("/account/?linked=oidc", linked.location());

            Browser again = new Browser();
            Reply second = again.get("/api/panel/oidc/start");
            provider.claims.set(provider.idToken("u-9", query(second.location(), "nonce")));
            assertEquals("/login/?error=oidc-state", new Browser().get("/api/panel/oidc/callback?state="
                    + query(second.location(), "state") + "&code=c").location(), "another browser cannot finish it");
            Reply third = again.get("/api/panel/oidc/start");
            provider.claims.set(provider.idToken("u-9", query(third.location(), "nonce")));
            Reply ok = again.get("/api/panel/oidc/callback?state=" + query(third.location(), "state") + "&code=c");
            assertEquals("/", ok.location());
            assertNotNull(again.session);
        }
    }

    @Test
    void aViewerCannotReadTemplateFiles() throws Exception {
        start(null);
        Browser owner = owner();
        assertEquals(200, owner.post("/api/panel/users", Map.of("name", "vic", "password", PASSWORD,
                "roles", List.of("viewer"))).status());
        Browser viewer = new Browser();
        assertEquals(200, viewer.post("/api/panel/login", Map.of("name", "vic", "password", PASSWORD)).status());
        node.lastPrincipal.set(null);
        Reply files = viewer.get("/api/v1/templates/lobby/files/server.properties");
        assertEquals(403, files.status(), files.body());
        assertEquals(null, node.lastPrincipal.get(), "the API is not even called");
        assertEquals(403, viewer.get("/api/v1/templates/lobby/files").status());
        assertEquals(200, viewer.get("/api/v1/services").status());
        assertFalse(node.lastPrincipal.get().scopes().contains(net.vectolyn.cloud.api.auth.Scope.TEMPLATES_READ),
                "the viewer role has no template scope");
        assertEquals(200, owner.get("/api/v1/templates/lobby/files/server.properties").status(), "the owner reads them");
    }

    private static String query(String url, String key) {
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            if (pair.startsWith(key + "=")) {
                return pair.substring(key.length() + 1);
            }
        }
        throw new AssertionError(key + " missing in " + url);
    }
}
