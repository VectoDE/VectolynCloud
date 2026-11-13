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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import net.vectolyn.cloud.panel.files.ServiceFiles;
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
import org.jspecify.annotations.Nullable;

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
    /** Largest JSON body; only file uploads may be larger (up to the server's body limit). */
    static final int MAX_JSON_BODY = 256 * 1024;

    private final PanelUsers users;
    private final Sessions sessions;
    private final AuditLog audit;
    private final @Nullable NodeAccess api;
    private final @Nullable OidcClient oidc;
    private final Clock clock;
    private final RateLimiter<String> signInLimiter;
    private final @Nullable ServiceFiles files;
    private volatile @Nullable PanelNode node;
    private final Map<Channel, String> streams = new ConcurrentHashMap<>();
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private @Nullable PanelServer server;

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
    public PanelApi(PanelUsers users, AuditLog audit, @Nullable NodeAccess api, @Nullable OidcClient oidc, Clock clock, LongSupplier nanoTime) {
        this(users, audit, api, oidc, clock, nanoTime, null);
    }

    /**
     * Creates the endpoints with the file manager.
     *
     * @param users    the account store
     * @param audit    the audit log
     * @param api      the node's API
     * @param oidc     the single sign-on client, or null
     * @param clock    the clock
     * @param nanoTime the monotonic clock of the rate limits
     * @param files    the service folders, or null without a file manager
     */
    public PanelApi(PanelUsers users, AuditLog audit, @Nullable NodeAccess api, @Nullable OidcClient oidc, Clock clock, LongSupplier nanoTime,
                    @Nullable ServiceFiles files) {
        this.users = Objects.requireNonNull(users, "users");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = api;
        this.oidc = oidc;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessions = new Sessions(clock);
        this.signInLimiter = new RateLimiter<>(10, 20, nanoTime);
        this.files = files;
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
    // The body is the received bytes; copying it into a List would cost every request, so the array stays.
    @SuppressWarnings("ArrayRecordComponent")
    record Call(String method, String path, Map<String, String> query, String rawQuery, byte[] body,
                io.netty.handler.codec.http.HttpHeaders headers, String client) {

        @Nullable String header(CharSequence name) {
            return headers.get(name);
        }
    }

    /** A failure answered as JSON. */
    static class Refusal extends Exception {

        private static final long serialVersionUID = 1L;

        private final int status;
        private final String code;
        private final String text;

        Refusal(int status, String code, String message) {
            this(status, code, message, null);
        }

        Refusal(int status, String code, String message, @Nullable Throwable cause) {
            super(message, cause, false, false);
            this.status = status;
            this.code = code;
            this.text = message;
        }
    }

    /** Path of the live-event stream (WebSocket). */
    static final String EVENTS = "/api/panel/events";

    /**
     * The answer to a refusal outside {@link #handle}.
     *
     * @param r the refusal
     * @return the error response
     */
    FullHttpResponse refusal(Refusal r) {
        return Responses.error(r.status, r.code, String.valueOf(r.getMessage()), secure());
    }

    FullHttpResponse handle(Call call) {
        try {
            return route(call);
        } catch (Refusal r) {
            return Responses.error(r.status, r.code, r.text, secure());
        } catch (IOException e) {
            return Responses.error(500, "storage-failed", "The panel could not save the change.", secure());
        }
    }

    private PanelServer server() {
        return Objects.requireNonNull(server, "the panel server is not attached");
    }

    private boolean secure() {
        return server().secure();
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
                case "/api/panel/files" -> {
                    require(session, PanelRight.FILES_READ);
                    yield listFiles(call);
                }
                case "/api/panel/files/content" -> {
                    require(session, PanelRight.FILES_READ);
                    yield readFile(call);
                }
                case "/api/panel/players" -> {
                    require(session, PanelRight.SERVICES_READ);
                    yield listPlayers();
                }
                case "/api/panel/statistics" -> {
                    require(session, PanelRight.NODE_READ);
                    yield statistics();
                }
                case "/api/panel/logs" -> {
                    require(session, PanelRight.ADMIN);
                    yield searchLog(call, session);
                }
                case "/api/panel/config/export" -> {
                    require(session, PanelRight.CONFIG_READ);
                    yield exportConfig(call, session);
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
        if (path.equals("/api/panel/files/write")) {
            Sessions.Session session = session(call).orElseThrow(Refusal401::new);
            if (!Sessions.csrfMatches(session, csrf)) {
                throw new Refusal(403, "csrf", "The CSRF token does not belong to this session.");
            }
            require(session, PanelRight.FILES_WRITE);
            return writeFile(call, session);
        }
        if (path.equals("/api/panel/config/import/preview") || path.equals("/api/panel/config/import")) {
            Sessions.Session session = session(call).orElseThrow(Refusal401::new);
            if (!Sessions.csrfMatches(session, csrf)) {
                throw new Refusal(403, "csrf", "The CSRF token does not belong to this session.");
            }
            require(session, PanelRight.CONFIG_WRITE);
            return importConfig(call, session, path.endsWith("/preview"));
        }
        if (call.body() != null && call.body().length > MAX_JSON_BODY) {
            throw new Refusal(413, "body-too-large", "The body is larger than " + MAX_JSON_BODY / 1024 + " KiB.");
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
            case "/api/panel/files/folder" -> {
                require(session, PanelRight.FILES_WRITE);
                yield createFolder(call, session, body);
            }
            case "/api/panel/files/delete" -> {
                require(session, PanelRight.FILES_WRITE);
                yield deleteFile(call, session, body);
            }
            case "/api/panel/players/kick", "/api/panel/players/ban", "/api/panel/players/message" -> {
                require(session, PanelRight.PLAYERS_MANAGE);
                yield playerAction(call, session, body, path.substring(path.lastIndexOf('/') + 1));
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
        info.put("version", server().version());
        info.put("frontend", !server().assets().placeholder());
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
                    throw new IOException(e.getMessage(), e);
                }
                return name;
            });
        } catch (IOException e) {
            throw new Refusal(409, "setup-failed", "The first account could not be created.", e);
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
        OptionalLong step = Totp.verify(Objects.requireNonNull(user.totpSecret(), "totpSecret"), code, clock.instant().getEpochSecond(), user.totpLastStep());
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
            throw invalidCredentials(e);
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
        return invalidCredentials(null);
    }

    private static Refusal invalidCredentials(@Nullable Throwable cause) {
        return new Refusal(401, "invalid-credentials", "Name or password is wrong.", cause);
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
    // The challenge is compared as raw bytes; a List<Byte> would only cost memory.
    @SuppressWarnings("ArrayRecordComponent")
    record Challenge(byte[] bytes, @Nullable String user) {
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
        random.nextBytes(challenge);
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
            throw passkeyRefused(call, user.name(), e);
        }
        try {
            users.update(user.name(), u -> withPasskeys(u, u.passkeys().stream()
                    .map(p -> p.id().equals(credentialId)
                            ? new PanelUsers.Passkey(p.id(), p.publicKey(), p.algorithm(), count, p.label(), p.createdAt())
                            : p).toList()));
        } catch (PanelUsers.RefusedException e) {
            throw passkeyRefused(call, user.name(), e);
        }
        audit.record("login.ok", user.name(), null, call.client(), Map.of("method", "passkey"));
        return signedIn(call, user.name(), "passkey");
    }

    private Refusal passkeyRefused(Call call, @Nullable String user) {
        return passkeyRefused(call, user, null);
    }

    private Refusal passkeyRefused(Call call, @Nullable String user, @Nullable Throwable cause) {
        audit.record("login.failed", null, user, call.client(), Map.of("factor", "passkey"));
        return new Refusal(401, "passkey-refused", "This passkey was not accepted.", cause);
    }

    private FullHttpResponse passkeyCreateOptions(Call call, Sessions.Session session) throws Refusal {
        PanelUsers.User user = currentUser(session);
        if (user.passkeys().size() >= 16) {
            throw new Refusal(409, "too-many-passkeys", "An account can have at most 16 passkeys.");
        }
        byte[] challenge = new byte[32];
        random.nextBytes(challenge);
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
                .filter(c -> session.user().equalsIgnoreCase(c.user()))
                .orElseThrow(() -> new Refusal(400, "ticket-expired", "That took too long. Start again."));
        String label = label(optionalText(body, "label", 64), "Passkey");
        WebAuthn.Credential credential;
        try {
            credential = WebAuthn.register(bytes(body, "client-data-json"), bytes(body, "attestation-object"),
                    expected(call, challenge.bytes()));
        } catch (WebAuthn.WebAuthnException e) {
            throw new Refusal(400, "passkey-refused", "The passkey could not be verified.", e);
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
    record OidcPending(OidcClient.Attempt attempt, @Nullable String linkUser) {
    }

    private FullHttpResponse oidcStart(Call call, Sessions.@Nullable Session session) throws Refusal {
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
            throw new Refusal(502, "oidc-unavailable", "The single sign-on provider cannot be reached.", e);
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
            response = oidcFinish(call, oidc, pending.get());
        }
        response.headers().add(HttpHeaderNames.SET_COOKIE, OIDC_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax");
        return response;
    }

    private FullHttpResponse oidcFinish(Call call, OidcClient client, OidcPending pending) throws IOException {
        String code = decode(call.query().get("code"));
        OidcClient.Identity identity;
        try {
            identity = client.finish(pending.attempt(), code);
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

    private static @Nullable String decode(@Nullable String value) {
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
        List<String> reset = new ArrayList<>();
        if (hash != null) {
            reset.add("password");
        }
        if (resetFactors) {
            reset.add("factors");
        }
        if (!reset.isEmpty()) {
            details.put("reset", reset);
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
        ApiToken principal = principal(session, rights);
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
            byte[] answerBody = answer.body();
            FullHttpResponse response = Responses.jsonBytes(answer.status(),
                    answerBody == null ? new byte[0] : answerBody, secure());
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

    private static ApiToken principal(Sessions.Session session, Set<PanelRight> rights) throws Refusal {
        Set<Scope> scopes = PanelRight.scopes(rights);
        if (scopes.isEmpty()) {
            throw new Refusal(403, "missing-scope", "This user has no rights in the API.");
        }
        return new ApiToken(ApiServer.LOCAL_PREFIX + session.user(), "panel:" + session.user(), scopes, session.created());
    }

    // ---- live events ------------------------------------------------------------------------

    /**
     * The checked start of an event stream.
     *
     * @param principal the user as a local token
     * @param sessionId the session the stream belongs to
     * @param types     event types asked for
     * @param services  services to follow
     */
    record StreamGrant(ApiToken principal, String sessionId, Set<String> types, Set<String> services) {
    }

    /**
     * Checks a WebSocket upgrade to {@code /api/panel/events}: a session, the panel's own origin
     * (browsers send it with every WebSocket, which stops other sites from opening one with the
     * user's cookie) and the query {@code types} and {@code services}, comma-separated.
     *
     * @param call the upgrade request
     * @return the grant
     * @throws Refusal 401, 403 or 400
     */
    StreamGrant openStream(Call call) throws Refusal {
        if (api == null) {
            throw notFound();
        }
        Sessions.Session session = session(call).orElseThrow(Refusal401::new);
        String origin = call.header(HttpHeaderNames.ORIGIN);
        if (origin == null || !origin.equalsIgnoreCase(origin(call))) {
            throw new Refusal(403, "origin", "The request does not come from the panel's own page.");
        }
        return new StreamGrant(principal(session, users.rights(currentUser(session))), session.id(),
                listQuery(call, "types"), listQuery(call, "services"));
    }

    private static Set<String> listQuery(Call call, String key) throws Refusal {
        String value = call.query().get(key);
        if (value == null || value.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String item : value.split(",", -1)) {
            if (!item.matches("[a-z0-9][a-z0-9_.-]{0,63}") || out.size() >= 64) {
                throw new Refusal(400, "invalid-query", "\"" + key + "\" must be a comma-separated list of names.");
            }
            out.add(item);
        }
        return out;
    }

    /**
     * Subscribes an upgraded WebSocket and remembers its session, so the stream ends with it.
     *
     * @param channel the WebSocket
     * @param grant   the checked request
     * @return the event types the stream gets
     * @throws ApiException as {@link NodeAccess#subscribe}
     */
    Set<String> subscribe(Channel channel, StreamGrant grant) throws ApiException {
        NodeAccess node = Objects.requireNonNull(api, "api");
        Set<String> types = node.subscribe(channel, grant.principal(), grant.types(), grant.services());
        streams.put(channel, grant.sessionId());
        channel.closeFuture().addListener(f -> streams.remove(channel));
        return types;
    }

    /** Closes every event stream whose session has ended or whose user was disabled or removed. */
    @SuppressWarnings("FutureReturnValueIgnored")
    void sweepStreams() {
        streams.forEach((channel, sessionId) -> {
            if (!sessions.alive(sessionId) || sessions.userOf(sessionId)
                    .flatMap(users::find).map(PanelUsers.User::disabled).orElse(true)) {
                streams.remove(channel);
                channel.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.POLICY_VIOLATION, "session ended"))
                        .addListener(f -> channel.close());
            }
        });
    }

    /**
     * Connects the node: players, service consoles and the configuration archive. Without it those
     * endpoints answer {@code 404}.
     *
     * @param node the node side
     */
    public void node(PanelNode node) {
        this.node = node;
    }

    private PanelNode requireNode() throws Refusal {
        PanelNode current = node;
        if (current == null) {
            throw notFound();
        }
        return current;
    }

    // ---- players --------------------------------------------------------------------------

    /** A Java name (3 to 16 of letters, digits, underscore) or a Bedrock name with the Floodgate dot. */
    private static final java.util.regex.Pattern PLAYER_NAME = java.util.regex.Pattern.compile("\\.?[A-Za-z0-9_]{1,16}");
    /** Longest kick, ban or message text, as the protocol's kick reason. */
    static final int MAX_PLAYER_TEXT = 256;

    private FullHttpResponse listPlayers() throws Refusal {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PanelNode.Player p : requireNode().players()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uuid", p.uuid());
            item.put("name", p.name());
            item.put("proxy", p.proxy());
            item.put("service", p.service());
            item.put("bedrock", p.bedrock());
            out.add(item);
        }
        return Responses.json(200, Map.of("players", out), secure());
    }

    /**
     * Checks a text that goes into a console line: no control characters (a line break would start
     * a second command), at most {@value #MAX_PLAYER_TEXT} characters.
     */
    static String consoleText(@Nullable String text) throws Refusal {
        String value = text == null ? "" : text.strip();
        if (value.length() > MAX_PLAYER_TEXT || value.chars().anyMatch(c -> Character.isISOControl(c)
                || c == 0x2028 || c == 0x2029 || c == 0x85)) {
            throw new Refusal(400, "invalid-text", "The text may have at most " + MAX_PLAYER_TEXT
                    + " characters and no line breaks or control characters.");
        }
        return value;
    }

    private FullHttpResponse playerAction(Call call, Sessions.Session session, Map<String, Object> body, String action)
            throws Refusal {
        PanelNode current = requireNode();
        String uuid = text(body, "uuid", 36);
        String text = consoleText(optionalText(body, action.equals("message") ? "text" : "reason", MAX_PLAYER_TEXT));
        if (action.equals("message") && text.isEmpty()) {
            throw new Refusal(400, "missing-field", "\"text\" is missing.");
        }
        PanelNode.Player player = current.players().stream().filter(p -> p.uuid().equalsIgnoreCase(uuid)).findFirst()
                .orElseThrow(() -> new Refusal(404, "no-such-player", "This player is not online."));
        if (player.service() == null) {
            throw new Refusal(409, "player-between-servers", "The player is switching servers; try again in a moment.");
        }
        if (!PLAYER_NAME.matcher(player.name()).matches()) {
            throw new Refusal(409, "unusual-name", "The player's name cannot be used in a console command.");
        }
        String line = switch (action) {
            case "kick" -> "kick " + player.name() + (text.isEmpty() ? "" : " " + text);
            case "ban" -> "ban " + player.name() + (text.isEmpty() ? "" : " " + text);
            default -> "tell " + player.name() + " " + text;
        };
        if (!current.command(player.service(), line)) {
            throw new Refusal(409, "service-not-running", "The player's server does not take commands right now.");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("service", player.service());
        details.put("name", player.name());
        audit.record("player." + action, session.user(), player.uuid(), call.client(), details);
        return Responses.json(200, Map.of("sent", true, "service", player.service()), secure());
    }

    // ---- statistics and log search --------------------------------------------------------

    /** Most hits of one log search. */
    static final int MAX_LOG_HITS = 500;

    private FullHttpResponse statistics() throws Refusal {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PanelNode.ModeStatistics m : requireNode().statistics()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mode", m.mode());
            item.put("logins", m.logins());
            item.put("playtime-seconds", m.playtimeSeconds());
            item.put("peak-players", m.peakPlayers());
            out.add(item);
        }
        return Responses.json(200, Map.of("modes", out), secure());
    }

    private FullHttpResponse searchLog(Call call, Sessions.Session session) throws Refusal, IOException {
        String text = queryText(call, "q").strip();
        if (text.length() < 2 || text.length() > 200 || text.chars().anyMatch(Character::isISOControl)) {
            throw new Refusal(400, "invalid-query", "Search for 2 to 200 characters without control characters.");
        }
        int limit = MAX_LOG_HITS;
        String raw = call.query().get("limit");
        if (raw != null) {
            try {
                limit = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > MAX_LOG_HITS) {
                throw new Refusal(400, "invalid-query", "\"limit\" must be 1 to " + MAX_LOG_HITS + ".");
            }
        }
        PanelNode.LogSearch result = requireNode().searchLog(text, limit);
        List<Map<String, Object>> hits = new ArrayList<>();
        result.hits().forEach(h -> hits.add(Map.of("file", h.file(), "line", h.line(), "text", h.text())));
        audit.record("log.searched", session.user(), "logs", call.client(), Map.of("hits", hits.size()));
        return Responses.json(200, Map.of("hits", hits, "truncated", result.truncated()), secure());
    }

    // ---- configuration archive ------------------------------------------------------------

    private FullHttpResponse exportConfig(Call call, Sessions.Session session) throws Refusal, IOException {
        byte[] zip = requireNode().exportConfig();
        audit.record("config.exported", session.user(), "config", call.client(), Map.of("bytes", zip.length));
        FullHttpResponse response = Responses.jsonBytes(200, zip, secure());
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/zip");
        response.headers().set(HttpHeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"vectolyn-config.zip\"");
        return response;
    }

    private FullHttpResponse importConfig(Call call, Sessions.Session session, boolean preview)
            throws Refusal, IOException {
        PanelNode current = requireNode();
        byte[] archive = call.body() == null ? new byte[0] : call.body();
        String digest = java.util.HexFormat.of().formatHex(sha256(archive));
        try {
            if (preview) {
                PanelNode.ImportPlan plan = current.planImport(archive);
                List<Map<String, Object>> files = new ArrayList<>();
                plan.files().forEach(f -> files.add(Map.of("file", f.file(), "kind", f.kind())));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("files", files);
                out.put("diff", plan.diff());
                out.put("confirm", digest);
                return Responses.json(200, out, secure());
            }
            // The import that runs is the one that was previewed: the client sends its digest back.
            String confirm = call.query().get("confirm");
            if (confirm == null || !java.security.MessageDigest.isEqual(
                    confirm.getBytes(StandardCharsets.US_ASCII), digest.getBytes(StandardCharsets.US_ASCII))) {
                throw new Refusal(409, "preview-required", "Preview this archive first and confirm that preview.");
            }
            Set<String> written = current.applyImport(archive);
            audit.record("config.imported", session.user(), "config", call.client(),
                    Map.of("files", written.size(), "sha256", digest));
            return Responses.json(200, Map.of("files", new java.util.TreeSet<>(written)), secure());
        } catch (PanelNode.InvalidImport e) {
            throw new Refusal(422, "invalid-import",
                    Objects.requireNonNullElse(e.getMessage(), "The archive cannot be imported."), e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    // ---- file manager ---------------------------------------------------------------------

    private ServiceFiles requireFiles() throws Refusal {
        if (files == null) {
            throw notFound();
        }
        return files;
    }

    private static String queryText(Call call, String key) throws Refusal {
        String raw = call.query().get(key);
        if (raw == null) {
            return "";
        }
        String decoded = decode(raw);
        if (decoded == null) {
            throw new Refusal(400, "invalid-query", "\"" + key + "\" is not valid percent-encoding.");
        }
        return decoded;
    }

    private static Refusal refusal(ServiceFiles.FileException e) {
        return new Refusal(e.status(), e.code(), String.valueOf(e.getMessage()));
    }

    private FullHttpResponse listFiles(Call call) throws Refusal, IOException {
        try {
            List<ServiceFiles.Entry> entries = requireFiles().list(queryText(call, "service"), queryText(call, "path"));
            List<Map<String, Object>> out = new ArrayList<>();
            for (ServiceFiles.Entry e : entries) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", e.name());
                item.put("folder", e.folder());
                item.put("size", e.size());
                item.put("modified", e.modified());
                out.add(item);
            }
            return Responses.json(200, Map.of("entries", out), secure());
        } catch (ServiceFiles.FileException e) {
            throw refusal(e);
        }
    }

    private FullHttpResponse readFile(Call call) throws Refusal, IOException {
        String path = queryText(call, "path");
        byte[] content;
        try {
            content = requireFiles().read(queryText(call, "service"), path);
        } catch (ServiceFiles.FileException e) {
            throw refusal(e);
        }
        FullHttpResponse response = Responses.jsonBytes(200, content, secure());
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
        // Always a download, never rendered: a file of a service is not a page of the panel.
        String name = ServiceFiles.fileName(path).orElse("file").replaceAll("[^A-Za-z0-9._-]", "_");
        response.headers().set(HttpHeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"");
        return response;
    }

    private FullHttpResponse writeFile(Call call, Sessions.Session session) throws Refusal, IOException {
        String service = queryText(call, "service");
        String path = queryText(call, "path");
        try {
            requireFiles().write(service, path, call.body() == null ? new byte[0] : call.body());
        } catch (ServiceFiles.FileException e) {
            throw refusal(e);
        }
        audit.record("file.written", session.user(), service + "/" + path, call.client(),
                Map.of("bytes", call.body() == null ? 0 : call.body().length));
        return Responses.json(200, Map.of("written", true), secure());
    }

    private FullHttpResponse createFolder(Call call, Sessions.Session session, Map<String, Object> body)
            throws Refusal, IOException {
        String service = text(body, "service", 64);
        String path = text(body, "path", 512);
        try {
            requireFiles().createFolder(service, path);
        } catch (ServiceFiles.FileException e) {
            throw refusal(e);
        }
        audit.record("folder.created", session.user(), service + "/" + path, call.client(), Map.of());
        return Responses.json(200, Map.of("created", true), secure());
    }

    private FullHttpResponse deleteFile(Call call, Sessions.Session session, Map<String, Object> body)
            throws Refusal, IOException {
        String service = text(body, "service", 64);
        String path = text(body, "path", 512);
        try {
            requireFiles().delete(service, path);
        } catch (ServiceFiles.FileException e) {
            throw refusal(e);
        }
        audit.record("file.deleted", session.user(), service + "/" + path, call.client(), Map.of());
        return Responses.json(200, Map.of("deleted", true), secure());
    }

    // ---- request checks and helpers --------------------------------------------------------

    /** The panel's origin as the browser sees it. */
    String origin(Call call) {
        String host = call.header(HttpHeaderNames.HOST);
        String hostName = PanelServer.hostOf(host == null ? "" : host.strip());
        Optional<String> publicUrl = server().publicUrl();
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

    private static @Nullable String cookie(Call call, String name) {
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

    private static @Nullable String optionalText(Map<String, Object> body, String key, int max) throws Refusal {
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
            throw new Refusal(400, "invalid-field", "\"" + key + "\" must be Base64url.", e);
        }
    }

    private static String label(@Nullable String value, String fallback) throws Refusal {
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
            throw new Refusal(409, e.code(), Objects.requireNonNullElse(e.getMessage(), "The change was refused."), e);
        }
    }

    private static PanelUsers.User withPassword(PanelUsers.User u, String hash) {
        return new PanelUsers.User(u.name(), hash, u.roles(), u.totpSecret(), u.totpLastStep(), u.passkeys(), u.oidc(),
                u.disabled(), u.createdAt());
    }

    private static PanelUsers.User withTotp(PanelUsers.User u, @Nullable String secret, long step) {
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
