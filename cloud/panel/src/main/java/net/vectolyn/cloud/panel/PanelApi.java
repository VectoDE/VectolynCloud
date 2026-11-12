// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongSupplier;
import net.vectolyn.cloud.api.ApiServer;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.auth.RateLimiter;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.http.ApiException;
import net.vectolyn.cloud.api.http.ApiJson;
import net.vectolyn.cloud.api.http.ApiResponse;
import net.vectolyn.cloud.panel.auth.AuditLog;
import net.vectolyn.cloud.panel.auth.OidcClient;
import net.vectolyn.cloud.panel.auth.PanelRight;
import net.vectolyn.cloud.panel.auth.PanelUsers;
import net.vectolyn.cloud.panel.auth.PasswordHash;
import net.vectolyn.cloud.panel.auth.Sessions;
import net.vectolyn.cloud.panel.auth.Totp;
import net.vectolyn.cloud.panel.auth.WebAuthn;
import net.vectolyn.cloud.panel.http.Responses;

/**
 * The panel's signed-in endpoints: setup of the first account, sign-in with password, TOTP,
 * passkeys and single sign-on, the user's own account, users and roles, the audit log, and the REST
 * API of the node for the signed-in user ({@code /api/v1/...}).
 *
 * <p>Rules for every request that changes something ({@code POST}, {@code PUT}, {@code PATCH},
 * {@code DELETE}): the {@code Origin} header must be the panel's own origin and
 * {@code X-Vectolyn-CSRF} must be present; with a session it must equal the session's CSRF token.
 * A custom header cannot be sent by another site without a CORS preflight, which the panel never
 * allows. The session cookie is {@code HttpOnly}, {@code Secure} and {@code SameSite=Strict}.
 * Sign-in endpoints have their own rate limit per client address, and every account is locked for
 * 15 minutes after 5 failed sign-ins.
 */
public final class PanelApi {

    /** Name of the session cookie. */
    public static final String SESSION_COOKIE = "__Host-vectolyn-session";
    /** Name of the cookie that ties a single sign-on attempt to the browser. */
    public static final String OIDC_COOKIE = "__Host-vectolyn-oidc";
    /** Header with the CSRF token. */
    public static final String CSRF_HEADER = "X-Vectolyn-CSRF";
    /** Issuer shown in authenticator apps. */
    static final String ISSUER = "VectolynCloud";

    private static final int MAX_STRING = 4096;

    private final PanelUsers users;
    private final Sessions sessions;
    private final AuditLog audit;
    private final NodeAccess api;
    private final OidcClient oidc;
    private final Clock clock;
    private final RateLimiter<String> signInLimiter;
    private PanelServer server;

    /**
     * Creates the endpoints.
     *
     * @param users    the account store
     * @param audit    the audit log
     * @param api      the node's API, for setup and {@code /api/v1}; may be null in tests
     * @param oidc     the single sign-on client, or null when not configured
     * @param clock    the clock
     * @param nanoTime the monotonic clock of the rate limits
     */
    public PanelApi(PanelUsers users, AuditLog audit, NodeAccess api, OidcClient oidc, Clock clock, LongSupplier nanoTime) {
        this.users = Objects.requireNonNull(users, "users");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = api;
        this.oidc = oidc;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessions = new Sessions(clock);
        this.signInLimiter = new RateLimiter<>(10, 20, nanoTime);
    }

    void attach(PanelServer panelServer) {
        this.server = panelServer;
    }

    /**
     * Whether no account exists yet.
     *
     * @return true before setup
     */
    public boolean setupRequired() {
        return users.empty();
    }

    /** One request as the endpoints see it. */
    record Call(String method, String path, Map<String, String> query, String rawQuery, byte[] body,
                io.netty.handler.codec.http.HttpHeaders headers, String client) {

        String header(CharSequence name) {
            return headers.get(name);
        }
    }

    /** A failure answered as JSON. */
    static class Refusal extends Exception {

        private static final long serialVersionUID = 1L;

        private final int status;
        private final String code;

        Refusal(int status, String code, String message) {
            super(message, null, false, false);
            this.status = status;
            this.code = code;
        }
    }

    FullHttpResponse handle(Call call) {
        try {
            return route(call);
        } catch (Refusal r) {
            return Responses.error(r.status, r.code, r.getMessage(), secure());
        } catch (IOException e) {
            return Responses.error(500, "storage-failed", "The panel could not save the change.", secure());
        }
    }

    private boolean secure() {
        return server.secure();
    }

    private FullHttpResponse route(Call call) throws Refusal, IOException {
        String path = call.path();
        boolean get = call.method().equals("GET");
        if (path.startsWith("/api/v1/") || path.equals("/api/v1")) {
            return passThrough(call);
        }
        switch (path) {
            case "/api/panel/info" -> {
                requireMethod(call, "GET");
                return info();
            }
            case "/api/panel/oidc/callback" -> {
                requireMethod(call, "GET");
                return oidcCallback(call);
            }
            case "/api/panel/oidc/start" -> {
                requireMethod(call, "GET");
                return oidcStart(call, null);
            }
            default -> {
                // everything else below
            }
        }
        if (get) {
            Optional<Sessions.Session> current = session(call);
            if (path.equals("/api/panel/session") && current.isEmpty()) {
                // Signed out is a normal state of the page, not an error.
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("user", null);
                return Responses.json(200, out, secure());
            }
            Sessions.Session session = current.orElseThrow(Refusal401::new);
            return switch (path) {
                case "/api/panel/session" -> sessionInfo(session);
                case "/api/panel/users" -> {
                    require(session, PanelRight.USERS_MANAGE);
                    yield listUsers();
                }
                case "/api/panel/roles" -> {
                    require(session, PanelRight.USERS_MANAGE);
                    yield listRoles();
                }
                case "/api/panel/audit" -> {
                    require(session, PanelRight.AUDIT_READ);
                    yield readAudit(call);
                }
                default -> throw notFound();
            };
        }
        requireMethod(call, "POST");
        checkOrigin(call);
        String csrf = call.header(CSRF_HEADER);
        if (csrf == null || csrf.isEmpty()) {
            throw new Refusal(403, "csrf", "The request lacks the " + CSRF_HEADER + " header.");
        }
        Map<String, Object> body = body(call);
        switch (path) {
            case "/api/panel/setup" -> {
                limitSignIn(call);
                return setup(call, body);
            }
            case "/api/panel/login" -> {
                limitSignIn(call);
                return login(call, body);
            }
            case "/api/panel/login/totp" -> {
                limitSignIn(call);
                return loginTotp(call, body);
            }
            case "/api/panel/passkey/options" -> {
                limitSignIn(call);
                return passkeyLoginOptions(call);
            }
            case "/api/panel/passkey/login" -> {
                limitSignIn(call);
                return passkeyLogin(call, body);
            }
            default -> {
                // signed-in below
            }
        }
        Sessions.Session session = session(call).orElseThrow(Refusal401::new);
        if (!Sessions.csrfMatches(session, csrf)) {
            throw new Refusal(403, "csrf", "The CSRF token does not belong to this session.");
        }
        return switch (path) {
            case "/api/panel/logout" -> logout(call, session);
            case "/api/panel/account/password" -> changePassword(call, session, body);
            case "/api/panel/account/totp/start" -> totpStart(session);
            case "/api/panel/account/totp/confirm" -> totpConfirm(call, session, body);
            case "/api/panel/account/totp/delete" -> totpDelete(call, session, body);
            case "/api/panel/account/passkeys/options" -> passkeyCreateOptions(call, session);
            case "/api/panel/account/passkeys" -> passkeyAdd(call, session, body);
            case "/api/panel/account/passkeys/delete" -> passkeyDelete(call, session, body);
            case "/api/panel/account/oidc/start" -> oidcStart(call, session);
            case "/api/panel/account/oidc/delete" -> oidcUnlink(call, session, body);
            case "/api/panel/users" -> {
                require(session, PanelRight.USERS_MANAGE);
                yield createUser(call, session, body);
            }
            case "/api/panel/users/update" -> {
                require(session, PanelRight.USERS_MANAGE);
                yield updateUser(call, session, body);
            }
            case "/api/panel/users/delete" -> {
                require(session, PanelRight.USERS_MANAGE);
                yield deleteUser(call, session, body);
            }
            case "/api/panel/roles" -> {
                require(session, PanelRight.USERS_MANAGE);
                yield saveRole(call, session, body);
            }
            case "/api/panel/roles/delete" -> {
                require(session, PanelRight.USERS_MANAGE);
                yield deleteRole(call, session, body);
            }
            default -> throw notFound();
        };
    }

    /** 401 without a session. */
    private static final class Refusal401 extends Refusal {

        private static final long serialVersionUID = 1L;

        Refusal401() {
            super(401, "not-signed-in", "Sign in first.");
        }
    }

    private static Refusal notFound() {
        return new Refusal(404, "not-found", "No such endpoint.");
    }

    private static void requireMethod(Call call, String method) throws Refusal {
        if (!call.method().equals(method)) {
            throw new Refusal(405, "method-not-allowed", "Allowed: " + method + ".");
        }
    }

    // ---- info and session ------------------------------------------------------------------

    private FullHttpResponse info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "VectolynCloud");
        info.put("version", server.version());
        info.put("frontend", !server.assets().placeholder());
        info.put("setup-required", users.empty());
        info.put("oidc", oidc == null ? null : oidc.name());
        return Responses.json(200, info, secure());
    }

    private FullHttpResponse sessionInfo(Sessions.Session session) throws Refusal {
        PanelUsers.User user = currentUser(session);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user.name());
        out.put("roles", user.roles());
        out.put("rights", users.rights(user).stream().map(PanelRight::id).sorted().toList());
        out.put("csrf", session.csrf());
        out.put("method", session.method());
        out.put("totp", user.totp());
        List<Map<String, Object>> keys = new ArrayList<>();
        for (PanelUsers.Passkey key : user.passkeys()) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("id", key.id());
            k.put("label", key.label());
            k.put("created-at", key.createdAt());
            keys.add(k);
        }
        out.put("passkeys", keys);
        out.put("oidc-links", user.oidc().stream().map(l -> Map.of("issuer", l.issuer(), "subject", l.subject())).toList());
        out.put("oidc", oidc == null ? null : oidc.name());
        return Responses.json(200, out, secure());
    }

    private Optional<Sessions.Session> session(Call call) {
        String id = cookie(call, SESSION_COOKIE);
        Optional<Sessions.Session> session = sessions.touch(id);
        if (session.isEmpty()) {
            return Optional.empty();
        }
        Optional<PanelUsers.User> user = users.find(session.get().user());
        if (user.isEmpty() || user.get().disabled()) {
            sessions.end(id);
            return Optional.empty();
        }
        return session;
    }

    private PanelUsers.User currentUser(Sessions.Session session) throws Refusal {
        return users.find(session.user()).orElseThrow(Refusal401::new);
    }

    private void require(Sessions.Session session, PanelRight right) throws Refusal {
        if (!users.rights(currentUser(session)).contains(right)) {
            throw new Refusal(403, "missing-right", "This user lacks the right " + right.id() + ".");
        }
    }

    // ---- setup and sign-in -----------------------------------------------------------------

    private FullHttpResponse setup(Call call, Map<String, Object> body) throws Refusal, IOException {
        if (!users.empty()) {
            throw new Refusal(409, "setup-done", "The first account exists already. Sign in instead.");
        }
        if (api == null) {
            throw new Refusal(503, "api-missing", "The setup needs the API of the node.");
        }
        String token = text(body, "token", 256);
        String password = text(body, "password", 256);
        String wanted = optionalText(body, "name", 32);
        if (wanted != null && !PanelUsers.USER_NAME.matcher(wanted).matches()) {
            throw invalidName();
        }
        String hash = passwordHash(password, wanted == null ? "" : wanted);
        Optional<String> created;
        try {
            created = api.useSetupToken(token, admin -> {
                String name = wanted != null ? wanted : safeName(admin);
                try {
                    users.add(new PanelUsers.User(name, hash, List.of(PanelUsers.OWNER), null, -1, List.of(), List.of(),
                            false, now()));
                } catch (PanelUsers.RefusedException e) {
                    throw new IOException(e.getMessage());
                }
                return name;
            });
        } catch (IOException e) {
            throw new Refusal(409, "setup-failed", "The first account could not be created.");
        }
        if (created.isEmpty()) {
            audit.record("setup.failed", null, null, call.client(), Map.of());
            throw new Refusal(401, "invalid-setup-token", "The setup token is wrong, used or expired. Run \"api token setup\" in the console for a new one.");
        }
        audit.record("setup", created.get(), created.get(), call.client(), Map.of("role", PanelUsers.OWNER));
        return signedIn(call, created.get(), "setup");
    }

    private static String safeName(String admin) {
        String cleaned = admin == null ? "" : admin.replaceAll("[^A-Za-z0-9_.-]", "");
        if (cleaned.isEmpty() || !Character.isLetterOrDigit(cleaned.charAt(0))) {
            cleaned = "admin";
        }
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    private FullHttpResponse login(Call call, Map<String, Object> body) throws Refusal {
        String name = text(body, "name", 64);
        String password = text(body, "password", 256);
        long locked = sessions.lockedSeconds(name);
        if (locked > 0) {
            audit.record("login.locked", null, name.length() <= 32 ? name : null, call.client(), Map.of());
            throw lockedRefusal(locked);
        }
        Optional<PanelUsers.User> user = users.find(name);
        boolean ok = PasswordHash.verify(password.toCharArray(), user.map(PanelUsers.User::passwordHash).orElse(null));
        if (!ok || user.get().disabled()) {
            failed(call, name, user.isPresent() && ok ? "disabled" : "password");
            throw invalidCredentials();
        }
        PanelUsers.User found = user.get();
        upgradeHash(found, password);
        if (found.totp()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("second-factor", "totp");
            out.put("ticket", sessions.issue("totp", found.name()));
            return Responses.json(200, out, secure());
        }
        audit.record("login.ok", found.name(), null, call.client(), Map.of("method", "password"));
        return signedIn(call, found.name(), "password");
    }

    private void upgradeHash(PanelUsers.User user, String password) {
        if (!PasswordHash.outdated(user.passwordHash())) {
            return;
        }
        try {
            String hash = PasswordHash.hash(password.toCharArray());
            users.update(user.name(), u -> withPassword(u, hash));
        } catch (IOException | PanelUsers.RefusedException e) {
            // the old hash keeps working; the next sign-in tries again
        }
    }

    private FullHttpResponse loginTotp(Call call, Map<String, Object> body) throws Refusal, IOException {
        String ticket = text(body, "ticket", 64);
        String code = text(body, "code", 16);
        String name = sessions.take(ticket, "totp", String.class)
                .orElseThrow(() -> new Refusal(401, "ticket-expired", "The sign-in took too long. Start again."));
        long locked = sessions.lockedSeconds(name);
        if (locked > 0) {
            throw lockedRefusal(locked);
        }
        PanelUsers.User user = users.find(name).filter(u -> !u.disabled() && u.totp()).orElseThrow(PanelApi::invalidCredentials);
        OptionalLong step = Totp.verify(user.totpSecret(), code, clock.instant().getEpochSecond(), user.totpLastStep());
        if (step.isEmpty()) {
            failed(call, name, "totp");
            // a new ticket, so a typo does not need the password again
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", "invalid-code");
            out.put("message", "The code is wrong or was used already.");
            out.put("ticket", sessions.issue("totp", name));
            return Responses.json(401, out, secure());
        }
        try {
            users.update(name, u -> withTotp(u, u.totpSecret(), step.getAsLong()));
        } catch (PanelUsers.RefusedException e) {
            throw invalidCredentials();
        }
        audit.record("login.ok", user.name(), null, call.client(), Map.of("method", "totp"));
        return signedIn(call, user.name(), "totp");
    }

    private void failed(Call call, String name, String reason) {
        boolean nowLocked = sessions.fail(name);
        String target = name.length() <= 32 && PanelUsers.USER_NAME.matcher(name).matches() ? name : null;
        audit.record("login.failed", null, target, call.client(), Map.of("factor", reason));
        if (nowLocked) {
            audit.record("account.locked", null, target, call.client(), Map.of("minutes", Sessions.LOCKOUT.toMinutes()));
        }
    }

    private static Refusal invalidCredentials() {
        return new Refusal(401, "invalid-credentials", "Name or password is wrong.");
    }

    private static Refusal lockedRefusal(long seconds) {
        return new Refusal(429, "locked", "Too many failed sign-ins. Try again in " + ((seconds + 59) / 60) + " minutes.");
    }

    private FullHttpResponse signedIn(Call call, String user, String method) {
        String old = cookie(call, SESSION_COOKIE);
        sessions.end(old);
        Sessions.Session session = sessions.create(user, method);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user);
        out.put("csrf", session.csrf());
        FullHttpResponse response = Responses.json(200, out, secure());
        response.headers().add(HttpHeaderNames.SET_COOKIE,
                SESSION_COOKIE + "=" + session.id() + "; Path=/; HttpOnly; Secure; SameSite=Strict");
        return response;
    }

    private FullHttpResponse logout(Call call, Sessions.Session session) {
        sessions.end(session.id());
        audit.record("logout", session.user(), null, call.client(), Map.of());
        FullHttpResponse response = Responses.json(200, Map.of("signed-out", true), secure());
        response.headers().add(HttpHeaderNames.SET_COOKIE,
                SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict");
        return response;
    }

    // ---- passkeys --------------------------------------------------------------------------

    /** A pending passkey ceremony. */
    record Challenge(byte[] bytes, String user) {
    }

    private WebAuthn.Expected expected(Call call, byte[] challenge) throws Refusal {
        String origin = origin(call);
        String host = PanelServer.hostOf(origin.substring(origin.indexOf("://") + 3));
        if (host.startsWith("[") || host.matches("[0-9.]+")) {
            throw new Refusal(400, "passkeys-need-host-name",
                    "Passkeys need a host name such as localhost or panel.example.org, not an IP address.");
        }
        return new WebAuthn.Expected(challenge, origin, host);
    }

    private FullHttpResponse passkeyLoginOptions(Call call) throws Refusal {
        byte[] challenge = new byte[32];
        new java.security.SecureRandom().nextBytes(challenge);
        WebAuthn.Expected expected = expected(call, challenge);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticket", sessions.issue("passkey-login", new Challenge(challenge, null)));
        out.put("challenge", b64(challenge));
        out.put("rp-id", expected.rpId());
        out.put("timeout", Sessions.TICKET.toMillis());
        return Responses.json(200, out, secure());
    }

    private FullHttpResponse passkeyLogin(Call call, Map<String, Object> body) throws Refusal, IOException {
        Challenge challenge = sessions.take(text(body, "ticket", 64), "passkey-login", Challenge.class)
                .orElseThrow(() -> new Refusal(401, "ticket-expired", "The sign-in took too long. Start again."));
        String credentialId = text(body, "id", 2048);
        PanelUsers.User user = users.findByPasskey(credentialId).filter(u -> !u.disabled())
                .orElseThrow(() -> passkeyRefused(call, null));
        long locked = sessions.lockedSeconds(user.name());
        if (locked > 0) {
            throw lockedRefusal(locked);
        }
        PanelUsers.Passkey key = user.passkeys().stream().filter(p -> p.id().equals(credentialId)).findFirst()
                .orElseThrow(() -> passkeyRefused(call, user.name()));
        long count;
        try {
            count = WebAuthn.verifyAssertion(
                    new WebAuthn.Credential(unb64(key.id()), unb64(key.publicKey()), key.algorithm(), key.signCount()),
                    bytes(body, "client-data-json"), bytes(body, "authenticator-data"), bytes(body, "signature"),
                    expected(call, challenge.bytes()));
        } catch (WebAuthn.WebAuthnException e) {
            sessions.fail(user.name());
            throw passkeyRefused(call, user.name());
        }
        try {
            users.update(user.name(), u -> withPasskeys(u, u.passkeys().stream()
                    .map(p -> p.id().equals(credentialId)
                            ? new PanelUsers.Passkey(p.id(), p.publicKey(), p.algorithm(), count, p.label(), p.createdAt())
                            : p).toList()));
        } catch (PanelUsers.RefusedException e) {
            throw passkeyRefused(call, user.name());
        }
        audit.record("login.ok", user.name(), null, call.client(), Map.of("method", "passkey"));
        return signedIn(call, user.name(), "passkey");
    }

    private Refusal passkeyRefused(Call call, String user) {
        audit.record("login.failed", null, user, call.client(), Map.of("factor", "passkey"));
        return new Refusal(401, "passkey-refused", "This passkey was not accepted.");
    }

    private FullHttpResponse passkeyCreateOptions(Call call, Sessions.Session session) throws Refusal {
        PanelUsers.User user = currentUser(session);
        if (user.passkeys().size() >= 16) {
            throw new Refusal(409, "too-many-passkeys", "An account can have at most 16 passkeys.");
        }
        byte[] challenge = new byte[32];
        new java.security.SecureRandom().nextBytes(challenge);
        WebAuthn.Expected expected = expected(call, challenge);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticket", sessions.issue("passkey-create", new Challenge(challenge, user.name())));
        out.put("challenge", b64(challenge));
        out.put("rp", Map.of("id", expected.rpId(), "name", ISSUER));
        out.put("user", Map.of("id", b64(user.name().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)),
                "name", user.name(), "display-name", user.name()));
        out.put("algorithms", List.of(WebAuthn.ES256, WebAuthn.EDDSA, WebAuthn.RS256));
        out.put("exclude", user.passkeys().stream().map(PanelUsers.Passkey::id).toList());
        out.put("timeout", Sessions.TICKET.toMillis());
        return Responses.json(200, out, secure());
    }

    private FullHttpResponse passkeyAdd(Call call, Sessions.Session session, Map<String, Object> body)
            throws Refusal, IOException {
        Challenge challenge = sessions.take(text(body, "ticket", 64), "passkey-create", Challenge.class)
                .filter(c -> c.user().equalsIgnoreCase(session.user()))
                .orElseThrow(() -> new Refusal(400, "ticket-expired", "That took too long. Start again."));
        String label = label(optionalText(body, "label", 64), "Passkey");
        WebAuthn.Credential credential;
        try {
            credential = WebAuthn.register(bytes(body, "client-data-json"), bytes(body, "attestation-object"),
                    expected(call, challenge.bytes()));
        } catch (WebAuthn.WebAuthnException e) {
            throw new Refusal(400, "passkey-refused", "The passkey could not be verified.");
        }
        String id = b64(credential.id());
        if (users.findByPasskey(id).isPresent()) {
            throw new Refusal(409, "passkey-exists", "This passkey is registered already.");
        }
        PanelUsers.Passkey key = new PanelUsers.Passkey(id, b64(credential.publicKey()), credential.algorithm(),
                credential.signCount(), label, now());
        change(() -> users.update(session.user(), u -> {
            List<PanelUsers.Passkey> keys = new ArrayList<>(u.passkeys());
            keys.add(key);
            return withPasskeys(u, keys);
        }));
        audit.record("passkey.added", session.user(), session.user(), call.client(), Map.of("label", label));
        return sessionInfo(session);
    }

    private FullHttpResponse passkeyDelete(Call call, Sessions.Session session, Map<String, Object> body)
            throws Refusal, IOException {
        String id = text(body, "id", 2048);
        PanelUsers.User user = currentUser(session);
        if (user.passkeys().stream().noneMatch(p -> p.id().equals(id))) {
            throw new Refusal(404, "no-such-passkey", "No such passkey.");
        }
        change(() -> users.update(session.user(), u -> withPasskeys(u,
                u.passkeys().stream().filter(p -> !p.id().equals(id)).toList())));
        audit.record("passkey.removed", session.user(), session.user(), call.client(), Map.of());
        return sessionInfo(session);
    }

    // ---- account ---------------------------------------------------------------------------

    private FullHttpResponse changePassword(Call call, Sessions.Session session, Map<String, Object> body)
            throws Refusal, IOException {
        PanelUsers.User user = currentUser(session);
        if (!PasswordHash.verify(text(body, "current", 256).toCharArray(), user.passwordHash())) {
            failed(call, user.name(), "password");
            throw new Refusal(403, "wrong-password", "The current password is wrong.");
        }
        String hash = passwordHash(text(body, "password", 256), user.name());
        change(() -> users.update(user.name(), u -> withPassword(u, hash)));
        sessions.endAll(user.name(), session.id());
        audit.record("password.changed", user.name(), user.name(), call.client(), Map.of());
        return sessionInfo(session);
    }

    /** A TOTP secret waiting for its first code. */
    record PendingTotp(String user, String secret) {
    }

    private FullHttpResponse totpStart(Sessions.Session session) throws Refusal {
        PanelUsers.User user = currentUser(session);
        String secret = Totp.newSecret();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticket", sessions.issue("totp-enrol", new PendingTotp(user.name(), secret)));
        out.put("secret", secret);
        out.put("uri", Totp.uri(secret, user.name(), ISSUER));
        FullHttpResponse response = Responses.json(200, out, secure());
        return response;
    }

    private FullHttpResponse totpConfirm(Call call, Sessions.Session session, Map<String, Object> body)
            throws Refusal, IOException {
        PendingTotp pending = sessions.take(text(body, "ticket", 64), "totp-enrol", PendingTotp.class)
                .filter(p -> p.user().equalsIgnoreCase(session.user()))
                .orElseThrow(() -> new Refusal(400, "ticket-expired", "That took too long. Start again."));
        OptionalLong step = Totp.verify(pending.secret(), text(body, "code", 16), clock.instant().getEpochSecond(), -1);
        if (step.isEmpty()) {
            throw new Refusal(400, "invalid-code", "The code does not match. Check the time of the device and start again.");
        }
        change(() -> users.update(session.user(), u -> withTotp(u, pending.secret(), step.getAsLong())));
        audit.record("totp.enabled", session.user(), session.user(), call.client(), Map.of());
        return sessionInfo(session);
    }

    private FullHttpResponse totpDelete(Call call, Sessions.Session session, Map<String, Object> body)
            throws Refusal, IOException {
        PanelUsers.User user = currentUser(session);
        if (!PasswordHash.verify(text(body, "password", 256).toCharArray(), user.passwordHash())) {
            failed(call, user.name(), "password");
            throw new Refusal(403, "wrong-password", "The password is wrong.");
        }
        change(() -> users.update(user.name(), u -> withTotp(u, null, -1)));
        audit.record("totp.disabled", user.name(), user.name(), call.client(), Map.of());
        return sessionInfo(session);
    }

    // ---- single sign-on --------------------------------------------------------------------

    /** A pending single sign-on attempt and, for linking, the signed-in user. */
    record OidcPending(OidcClient.Attempt attempt, String linkUser) {
    }

    private FullHttpResponse oidcStart(Call call, Sessions.Session session) throws Refusal {
        if (oidc == null) {
            throw new Refusal(404, "oidc-off", "Single sign-on is not configured.");
        }
        if (session == null) {
            limitSignIn(call);
        }
        OidcClient.Attempt attempt;
        try {
            attempt = oidc.start();
        } catch (OidcClient.OidcException e) {
            throw new Refusal(502, "oidc-unavailable", "The single sign-on provider cannot be reached.");
        }
        String ticket = sessions.issue("oidc", new OidcPending(attempt, session == null ? null : session.user()));
        String cookie = OIDC_COOKIE + "=" + ticket + "; Path=/; Max-Age=" + Sessions.TICKET.toSeconds()
                + "; HttpOnly; Secure; SameSite=Lax";
        FullHttpResponse response;
        if (session == null) {
            response = Responses.text(303, "See other.", secure());
            response.headers().set(HttpHeaderNames.LOCATION, attempt.url());
        } else {
            response = Responses.json(200, Map.of("url", attempt.url()), secure());
        }
        response.headers().add(HttpHeaderNames.SET_COOKIE, cookie);
        return response;
    }

    private FullHttpResponse oidcCallback(Call call) throws IOException {
        String ticket = cookie(call, OIDC_COOKIE);
        Optional<OidcPending> pending = sessions.take(ticket, "oidc", OidcPending.class);
        String state = call.query().get("state");
        FullHttpResponse response;
        if (oidc == null || pending.isEmpty() || state == null
                || !java.security.MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII),
                pending.get().attempt().state().getBytes(StandardCharsets.US_ASCII))) {
            response = redirect("/login/?error=oidc-state");
        } else if (call.query().containsKey("error")) {
            response = redirect(pending.get().linkUser() != null ? "/account/?error=oidc-denied" : "/login/?error=oidc-denied");
        } else {
            response = oidcFinish(call, pending.get());
        }
        response.headers().add(HttpHeaderNames.SET_COOKIE, OIDC_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax");
        return response;
    }

    private FullHttpResponse oidcFinish(Call call, OidcPending pending) throws IOException {
        String code = decode(call.query().get("code"));
        OidcClient.Identity identity;
        try {
            identity = oidc.finish(pending.attempt(), code);
        } catch (OidcClient.OidcException e) {
            audit.record("login.failed", null, null, call.client(), Map.of("factor", "oidc"));
            return redirect(pending.linkUser() != null ? "/account/?error=oidc-failed" : "/login/?error=oidc-failed");
        }
        Optional<PanelUsers.User> linked = users.findByOidc(identity.issuer(), identity.subject());
        if (pending.linkUser() != null) {
            if (linked.isPresent()) {
                return redirect("/account/?error=oidc-linked-elsewhere");
            }
            try {
                users.update(pending.linkUser(), u -> {
                    List<PanelUsers.OidcLink> links = new ArrayList<>(u.oidc());
                    links.add(new PanelUsers.OidcLink(identity.issuer(), identity.subject()));
                    return withOidc(u, links);
                });
            } catch (PanelUsers.RefusedException e) {
                return redirect("/account/?error=oidc-failed");
            }
            audit.record("oidc.linked", pending.linkUser(), pending.linkUser(), call.client(), Map.of("issuer", identity.issuer()));
            return redirect("/account/?linked=oidc");
        }
        if (linked.isEmpty() || linked.get().disabled()) {
            audit.record("login.failed", null, linked.map(PanelUsers.User::name).orElse(null), call.client(),
                    Map.of("factor", "oidc", "reason", linked.isEmpty() ? "not-linked" : "disabled"));
            return redirect("/login/?error=oidc-not-linked");
        }
        audit.record("login.ok", linked.get().name(), null, call.client(), Map.of("method", "oidc"));
        FullHttpResponse signed = signedIn(call, linked.get().name(), "oidc");
        FullHttpResponse response = redirect("/");
        response.headers().add(HttpHeaderNames.SET_COOKIE, signed.headers().get(HttpHeaderNames.SET_COOKIE));
        signed.release();
        return response;
    }

    private FullHttpResponse oidcUnlink(Call call, Sessions.Session session, Map<String, Object> body)
            throws Refusal, IOException {
        PanelUsers.OidcLink link = new PanelUsers.OidcLink(text(body, "issuer", 512), text(body, "subject", 255));
        if (!currentUser(session).oidc().contains(link)) {
            throw new Refusal(404, "no-such-link", "No such link.");
        }
        change(() -> users.update(session.user(), u -> withOidc(u, u.oidc().stream().filter(l -> !l.equals(link)).toList())));
        audit.record("oidc.unlinked", session.user(), session.user(), call.client(), Map.of("issuer", link.issuer()));
        return sessionInfo(session);
    }

    private FullHttpResponse redirect(String location) {
        FullHttpResponse response = Responses.text(303, "See other.", secure());
        response.headers().set(HttpHeaderNames.LOCATION, location);
        return response;
    }

    private static String decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- users and roles -------------------------------------------------------------------

    private FullHttpResponse listUsers() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PanelUsers.User user : users.users()) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("name", user.name());
            u.put("roles", user.roles());
            u.put("disabled", user.disabled());
            u.put("totp", user.totp());
            u.put("passkeys", user.passkeys().size());
            u.put("oidc", !user.oidc().isEmpty());
            u.put("created-at", user.createdAt());
            list.add(u);
        }
        return Responses.json(200, Map.of("users", list), secure());
    }

    private FullHttpResponse listRoles() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PanelUsers.Role role : users.roles()) {
            list.add(Map.of("name", role.name(), "rights", role.rights().stream().map(PanelRight::id).sorted().toList(),
                    "built-in", PanelUsers.BUILT_IN.containsKey(role.name())));
        }
        return Responses.json(200, Map.of("roles", list,
                "rights", java.util.Arrays.stream(PanelRight.values()).map(PanelRight::id).toList()), secure());
    }

    /** The actor may hand out a role only if it holds every right of that role. */
    private void checkGrantable(Sessions.Session session, List<String> roles) throws Refusal {
        Set<PanelRight> own = users.rights(currentUser(session));
        for (String role : roles) {
            Optional<PanelUsers.Role> found = users.roles().stream().filter(r -> r.name().equals(role)).findFirst();
            if (found.isEmpty()) {
                throw new Refusal(400, "no-such-role", "No such role: " + role + ".");
            }
            if (!own.containsAll(found.get().rights())) {
                throw new Refusal(403, "role-above-own-rights", "You can only give roles whose rights you hold yourself.");
            }
        }
    }

    private FullHttpResponse createUser(Call call, Sessions.Session session, Map<String, Object> body)
            throws Refusal, IOException {
        String name = text(body, "name", 32);
        if (!PanelUsers.USER_NAME.matcher(name).matches()) {
            throw invalidName();
        }
        List<String> roles = list(body, "roles");
        checkGrantable(session, roles);
        String hash = passwordHash(text(body, "password", 256), name);
        change(() -> users.add(new PanelUsers.User(name, hash, roles, null, -1, List.of(), List.of(), false, now())));
        audit.record("user.created", session.user(), name, call.client(), Map.of("roles", roles));
        return listUsers();
    }

    private FullHttpResponse updateUser(Call call, Sessions.Session session, Map<String, Object> body)
            throws Refusal, IOException {
        String name = text(body, "name", 32);
        PanelUsers.User target = users.find(name).orElseThrow(() -> new Refusal(404, "no-such-user", "No such user."));
        // Changing someone who holds more rights than the actor would be a way up.
        if (!users.rights(currentUser(session)).containsAll(users.rights(target))) {
            throw new Refusal(403, "user-above-own-rights", "You cannot change a user with rights you do not hold.");
        }
        List<String> roles = body.containsKey("roles") ? list(body, "roles") : null;
        if (roles != null) {
            checkGrantable(session, roles);
        }
        Boolean disabled = body.get("disabled") instanceof Boolean b ? b : null;
        String password = optionalText(body, "password", 256);
        String hash = password == null ? null : passwordHash(password, target.name());
        boolean resetFactors = Boolean.TRUE.equals(body.get("reset-factors"));
        change(() -> users.update(target.name(), u -> {
            PanelUsers.User next = u;
            if (roles != null) {
                next = new PanelUsers.User(next.name(), next.passwordHash(), roles, next.totpSecret(), next.totpLastStep(),
                        next.passkeys(), next.oidc(), next.disabled(), next.createdAt());
            }
            if (disabled != null) {
                next = new PanelUsers.User(next.name(), next.passwordHash(), next.roles(), next.totpSecret(),
                        next.totpLastStep(), next.passkeys(), next.oidc(), disabled, next.createdAt());
            }
            if (hash != null) {
                next = withPassword(next, hash);
            }
            if (resetFactors) {
                next = withOidc(withPasskeys(withTotp(next, null, -1), List.of()), List.of());
            }
            return next;
        }));
        if (Boolean.TRUE.equals(disabled) || hash != null || resetFactors) {
            sessions.endAll(target.name(), target.name().equalsIgnoreCase(session.user()) ? session.id() : null);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (roles != null) {
            details.put("roles", roles);
        }
        if (disabled != null) {
            details.put("disabled", disabled);
        }
        if (hash != null) {
            details.put("password", "reset");
        }
        if (resetFactors) {
            details.put("factors", "reset");
        }
        audit.record("user.updated", session.user(), target.name(), call.client(), details);
        return listUsers();
    }

    private FullHttpResponse deleteUser(Call call, Sessions.Session session, Map<String, Object> body)
            throws Refusal, IOException {
        String name = text(body, "name", 32);
        PanelUsers.User target = users.find(name).orElseThrow(() -> new Refusal(404, "no-such-user", "No such user."));
        if (!users.rights(currentUser(session)).containsAll(users.rights(target))) {
            throw new Refusal(403, "user-above-own-rights", "You cannot remove a user with rights you do not hold.");
        }
        change(() -> users.remove(target.name()));
        sessions.endAll(target.name(), null);
        audit.record("user.deleted", session.user(), target.name(), call.client(), Map.of());
        return listUsers();
    }

    private FullHttpResponse saveRole(Call call, Sessions.Session session, Map<String, Object> body)
            throws Refusal, IOException {
        String name = text(body, "name", 32);
        Set<PanelRight> rights = EnumSet.noneOf(PanelRight.class);
        for (String id : list(body, "rights")) {
            rights.add(PanelRight.of(id).orElseThrow(() -> new Refusal(400, "no-such-right", "No such right: " + id + ".")));
        }
        if (!users.rights(currentUser(session)).containsAll(rights)) {
            throw new Refusal(403, "role-above-own-rights", "A role can only have rights you hold yourself.");
        }
        change(() -> users.saveRole(new PanelUsers.Role(name, rights)));
        audit.record("role.saved", session.user(), name, call.client(),
                Map.of("rights", rights.stream().map(PanelRight::id).sorted().toList()));
        return listRoles();
    }

    private FullHttpResponse deleteRole(Call call, Sessions.Session session, Map<String, Object> body)
            throws Refusal, IOException {
        String name = text(body, "name", 32);
        change(() -> users.removeRole(name));
        audit.record("role.deleted", session.user(), name, call.client(), Map.of());
        return listRoles();
    }

    private FullHttpResponse readAudit(Call call) throws Refusal, IOException {
        int limit = 100;
        String text = call.query().get("limit");
        if (text != null) {
            if (!text.matches("[0-9]{1,3}")) {
                throw new Refusal(400, "invalid-query", "\"limit\" must be a number from 1 to " + AuditLog.MAX_READ + ".");
            }
            limit = Integer.parseInt(text);
        }
        return Responses.json(200, Map.of("entries", audit.newest(limit)), secure());
    }

    // ---- API for the signed-in user --------------------------------------------------------

    private FullHttpResponse passThrough(Call call) throws Refusal {
        if (api == null) {
            throw notFound();
        }
        Sessions.Session session = session(call).orElseThrow(Refusal401::new);
        if (!call.method().equals("GET") && !call.method().equals("HEAD")) {
            checkOrigin(call);
            if (!Sessions.csrfMatches(session, call.header(CSRF_HEADER))) {
                throw new Refusal(403, "csrf", "The CSRF token does not belong to this session.");
            }
        }
        Set<PanelRight> rights = users.rights(currentUser(session));
        // File contents of templates are files: besides the template scope they need the file right.
        if (call.path().matches("/api/v1/templates/[^/]+/files(/.*)?")) {
            PanelRight files = call.method().equals("GET") || call.method().equals("HEAD")
                    ? PanelRight.FILES_READ : PanelRight.FILES_WRITE;
            if (!rights.contains(files)) {
                throw new Refusal(403, "missing-right", "This user lacks the right " + files.id() + ".");
            }
        }
        Set<Scope> scopes = PanelRight.scopes(rights);
        if (scopes.isEmpty()) {
            throw new Refusal(403, "missing-scope", "This user has no rights in the API.");
        }
        ApiToken principal = new ApiToken(ApiServer.LOCAL_PREFIX + session.user(), "panel:" + session.user(), scopes,
                session.created());
        String target = call.path().substring("/api/v1".length());
        if (target.isEmpty()) {
            target = "/";
        }
        if (call.rawQuery() != null && !call.rawQuery().isEmpty()) {
            target += "?" + call.rawQuery();
        }
        try {
            ApiResponse answer = api.call(principal, call.method(), target, call.body(),
                    call.header(HttpHeaderNames.IF_MATCH));
            FullHttpResponse response = Responses.jsonBytes(answer.status(), answer.body(), secure());
            answer.headers().forEach((k, v) -> {
                if (!k.equalsIgnoreCase("content-type") && !k.equalsIgnoreCase("content-length")) {
                    response.headers().set(k, v);
                }
            });
            return response;
        } catch (ApiException e) {
            FullHttpResponse response = Responses.jsonBytes(e.status(), ApiJson.error(e), secure());
            if (e.retryAfterSeconds() > 0) {
                response.headers().set(HttpHeaderNames.RETRY_AFTER, Long.toString(e.retryAfterSeconds()));
            }
            return response;
        }
    }

    // ---- request checks and helpers --------------------------------------------------------

    /** The panel's origin as the browser sees it. */
    String origin(Call call) {
        String host = call.header(HttpHeaderNames.HOST);
        String hostName = PanelServer.hostOf(host == null ? "" : host.strip());
        Optional<String> publicUrl = server.publicUrl();
        if (publicUrl.isPresent()
                && PanelServer.hostOf(publicUrl.get().substring(publicUrl.get().indexOf("://") + 3)).equals(hostName)) {
            return publicUrl.get();
        }
        return (secure() ? "https://" : "http://") + (host == null ? "" : host.strip().toLowerCase(Locale.ROOT));
    }

    private void checkOrigin(Call call) throws Refusal {
        String origin = call.header(HttpHeaderNames.ORIGIN);
        if (origin == null || !origin.equalsIgnoreCase(origin(call))) {
            throw new Refusal(403, "origin", "The request does not come from the panel's own page.");
        }
        String site = call.header("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin")) {
            throw new Refusal(403, "origin", "The request does not come from the panel's own page.");
        }
    }

    private void limitSignIn(Call call) throws Refusal {
        long wait = signInLimiter.tryAcquire(call.client());
        if (wait > 0) {
            throw new Refusal(429, "rate-limited", "Too many sign-in attempts. Wait " + wait + " seconds.");
        }
    }

    private static String cookie(Call call, String name) {
        String header = call.header(HttpHeaderNames.COOKIE);
        if (header == null || header.length() > 4096) {
            return null;
        }
        for (Cookie cookie : ServerCookieDecoder.STRICT.decode(header)) {
            if (cookie.name().equals(name)) {
                return cookie.value();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Call call) throws Refusal {
        if (call.body() == null || call.body().length == 0) {
            return Map.of();
        }
        try {
            Object value = ApiJson.mapper().readValue(call.body(), Object.class);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (RuntimeException e) {
            // answered below
        }
        throw new Refusal(400, "invalid-json", "The body must be a JSON object.");
    }

    private static String text(Map<String, Object> body, String key, int max) throws Refusal {
        String value = optionalText(body, key, max);
        if (value == null) {
            throw new Refusal(400, "missing-field", "\"" + key + "\" is missing.");
        }
        return value;
    }

    private static String optionalText(Map<String, Object> body, String key, int max) throws Refusal {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.length() > Math.min(max, MAX_STRING)) {
            throw new Refusal(400, "invalid-field", "\"" + key + "\" must be text of at most " + max + " characters.");
        }
        return text;
    }

    private static List<String> list(Map<String, Object> body, String key) throws Refusal {
        if (!(body.get(key) instanceof List<?> raw) || raw.size() > 64) {
            throw new Refusal(400, "invalid-field", "\"" + key + "\" must be a list.");
        }
        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof String text) || text.length() > 64 || out.contains(text)) {
                throw new Refusal(400, "invalid-field", "\"" + key + "\" must be a list of different names.");
            }
            out.add(text);
        }
        return out;
    }

    private static byte[] bytes(Map<String, Object> body, String key) throws Refusal {
        String text = text(body, key, 16 * 1024);
        try {
            return Base64.getUrlDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new Refusal(400, "invalid-field", "\"" + key + "\" must be Base64url.");
        }
    }

    private static String label(String value, String fallback) throws Refusal {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new Refusal(400, "invalid-field", "\"label\" has control characters.");
        }
        return value.strip();
    }

    private static Refusal invalidName() {
        return new Refusal(400, "invalid-name",
                "Names are 1 to 32 characters: letters, digits, _ . and -, starting with a letter or digit.");
    }

    private static String passwordHash(String password, String account) throws Refusal {
        Optional<String> problem = PasswordHash.problem(password, account);
        if (problem.isPresent()) {
            throw new Refusal(400, "weak-password", problem.get());
        }
        return PasswordHash.hash(password.toCharArray());
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] unb64(String text) {
        return Base64.getUrlDecoder().decode(text);
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    /** A store change whose refusal is answered as 409. */
    private interface Change {
        void run() throws PanelUsers.RefusedException, IOException;
    }

    private static void change(Change change) throws Refusal, IOException {
        try {
            change.run();
        } catch (PanelUsers.RefusedException e) {
            throw new Refusal(409, e.code(), e.getMessage());
        }
    }

    private static PanelUsers.User withPassword(PanelUsers.User u, String hash) {
        return new PanelUsers.User(u.name(), hash, u.roles(), u.totpSecret(), u.totpLastStep(), u.passkeys(), u.oidc(),
                u.disabled(), u.createdAt());
    }

    private static PanelUsers.User withTotp(PanelUsers.User u, String secret, long step) {
        return new PanelUsers.User(u.name(), u.passwordHash(), u.roles(), secret, step, u.passkeys(), u.oidc(),
                u.disabled(), u.createdAt());
    }

    private static PanelUsers.User withPasskeys(PanelUsers.User u, List<PanelUsers.Passkey> keys) {
        return new PanelUsers.User(u.name(), u.passwordHash(), u.roles(), u.totpSecret(), u.totpLastStep(), keys, u.oidc(),
                u.disabled(), u.createdAt());
    }

    private static PanelUsers.User withOidc(PanelUsers.User u, List<PanelUsers.OidcLink> links) {
        return new PanelUsers.User(u.name(), u.passwordHash(), u.roles(), u.totpSecret(), u.totpLastStep(), u.passkeys(),
                links, u.disabled(), u.createdAt());
    }
}
