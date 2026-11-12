// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.vectolyn.cloud.api.http.ApiJson;
import net.vectolyn.cloud.config.secret.Secret;
import net.vectolyn.cloud.panel.config.PanelConfig;
import org.jspecify.annotations.Nullable;

/**
 * Sign-in with an OpenID Connect provider: authorization code flow with PKCE (S256), a random
 * {@code state} and {@code nonce} per attempt, the client authenticated with
 * {@code client_secret_basic}. The ID token is verified with the provider's JWKS ({@link Jwt}).
 *
 * <p>The discovery document ({@code <issuer>/.well-known/openid-configuration}) must name the same
 * issuer, and its endpoints must use HTTPS unless the issuer itself is a loopback test server. It
 * and the key set are cached for an hour; an unknown {@code kid} reloads the key set once. Answers
 * of the provider are limited to 1 MiB and 10 seconds, redirects are not followed.
 */
public final class OidcClient {

    private static final int MAX_ANSWER = 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CACHE = Duration.ofHours(1);

    private final PanelConfig.Oidc config;
    private final Secret clientSecret;
    private final String redirectUri;
    private final Clock clock;
    private final HttpClient http;
    private final SecureRandom random = new SecureRandom();
    private @Nullable Discovery discovery;
    private Instant discoveredAt = Instant.EPOCH;
    private @Nullable Map<String, Object> jwks;
    private Instant jwksAt = Instant.EPOCH;

    /**
     * Creates the client.
     *
     * @param config       {@code oidc} of {@code panel.yml}
     * @param clientSecret the client secret
     * @param publicUrl    the panel's origin; the redirect URI is {@code <origin>/api/panel/oidc/callback}
     * @param clock        the clock
     */
    public OidcClient(PanelConfig.Oidc config, Secret clientSecret, String publicUrl, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.redirectUri = publicUrl + "/api/panel/oidc/callback";
        this.clock = Objects.requireNonNull(clock, "clock");
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Endpoints of the provider. */
    record Discovery(String authorization, String token, String jwks) {
    }

    /**
     * One sign-in attempt, kept by the panel until the provider sends the browser back.
     *
     * @param state    the {@code state} value
     * @param nonce    the {@code nonce} value
     * @param verifier the PKCE code verifier
     * @param url      where to send the browser
     */
    public record Attempt(String state, String nonce, String verifier, String url) {
    }

    /**
     * The account at the provider.
     *
     * @param issuer  the issuer
     * @param subject the stable id ({@code sub})
     * @param name    a name to show ({@code preferred_username}, {@code name} or {@code email}), may be empty
     */
    public record Identity(String issuer, String subject, String name) {
    }

    /** The provider could not be used. */
    public static final class OidcException extends Exception {

        private static final long serialVersionUID = 1L;

        OidcException(String message) {
            super(message);
        }

        OidcException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The button label.
     *
     * @return the name from {@code panel.yml}
     */
    public String name() {
        return config.name();
    }

    /**
     * Starts an attempt.
     *
     * @return state, nonce, verifier and the provider's URL
     * @throws OidcException if the discovery document cannot be read
     */
    public Attempt start() throws OidcException {
        Discovery endpoints = discovery();
        String state = randomText();
        String nonce = randomText();
        String verifier = randomText() + randomText();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add("openid");
        scopes.addAll(config.scopes());
        String url = endpoints.authorization() + (endpoints.authorization().contains("?") ? "&" : "?")
                + "response_type=code"
                + "&client_id=" + enc(config.clientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(String.join(" ", scopes))
                + "&state=" + state
                + "&nonce=" + nonce
                + "&code_challenge=" + challenge
                + "&code_challenge_method=S256";
        return new Attempt(state, nonce, verifier, url);
    }

    /**
     * Exchanges the code from the callback and verifies the ID token.
     *
     * @param attempt the attempt the callback's {@code state} belongs to
     * @param code    the {@code code} parameter
     * @return the account at the provider
     * @throws OidcException if the exchange or any check of the ID token fails
     */
    public Identity finish(Attempt attempt, @Nullable String code) throws OidcException {
        if (code == null || code.isEmpty() || code.length() > 2048) {
            throw new OidcException("missing code");
        }
        Discovery endpoints = discovery();
        String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirectUri)
                + "&code_verifier=" + attempt.verifier();
        String basic = Base64.getEncoder().encodeToString((enc(config.clientId()) + ":" + enc(clientSecret.reveal()))
                .getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.token())).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        Map<String, Object> answer = json(request);
        if (!(answer.get("id_token") instanceof String idToken)) {
            throw new OidcException("the provider sent no ID token");
        }
        try {
            Jwt jwt = Jwt.parse(idToken);
            try {
                jwt.verify(jwks(false));
            } catch (Jwt.JwtException e) {
                jwt.verify(jwks(true)); // the provider may have rotated its keys
            }
            jwt.checkIdToken(config.issuer(), config.clientId(), attempt.nonce(), clock.instant().getEpochSecond());
            Map<String, Object> claims = jwt.claims();
            String name = firstText(claims, "preferred_username", "name", "email");
            if (!(claims.get("sub") instanceof String subject)) {
                throw new OidcException("ID token refused: missing subject");
            }
            return new Identity(config.issuer(), subject, name);
        } catch (Jwt.JwtException e) {
            throw new OidcException("ID token refused: " + e.getMessage(), e);
        }
    }

    private static String firstText(Map<String, Object> claims, String... names) {
        for (String name : names) {
            if (claims.get(name) instanceof String text && !text.isBlank()) {
                String clean = text.replaceAll("\\p{Cntrl}", "");
                return clean.length() > 64 ? clean.substring(0, 64) : clean;
            }
        }
        return "";
    }

    private synchronized Discovery discovery() throws OidcException {
        Instant now = clock.instant();
        Discovery cached = discovery;
        if (cached != null && now.isBefore(discoveredAt.plus(CACHE))) {
            return cached;
        }
        String issuer = config.issuer();
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        Map<String, Object> doc = json(HttpRequest.newBuilder(URI.create(base + "/.well-known/openid-configuration"))
                .timeout(TIMEOUT).header("Accept", "application/json").GET().build());
        if (!issuer.equals(doc.get("issuer"))) {
            throw new OidcException("the discovery document names another issuer");
        }
        Discovery found = new Discovery(endpoint(doc, "authorization_endpoint"), endpoint(doc, "token_endpoint"),
                endpoint(doc, "jwks_uri"));
        discovery = found;
        discoveredAt = now;
        return found;
    }

    private String endpoint(Map<String, Object> doc, String name) throws OidcException {
        if (!(doc.get(name) instanceof String url) || url.length() > 1024) {
            throw new OidcException("the discovery document has no " + name);
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new OidcException("the discovery document has an invalid " + name, e);
        }
        boolean testIssuer = config.issuer().startsWith("http://");
        if (uri.getHost() == null || !("https".equals(uri.getScheme()) || (testIssuer && "http".equals(uri.getScheme())))) {
            throw new OidcException(name + " must use HTTPS");
        }
        return url;
    }

    private synchronized Map<String, Object> jwks(boolean reload) throws OidcException {
        Instant now = clock.instant();
        Map<String, Object> cached = jwks;
        if (!reload && cached != null && now.isBefore(jwksAt.plus(CACHE))) {
            return cached;
        }
        Map<String, Object> loaded = json(HttpRequest.newBuilder(URI.create(discovery().jwks())).timeout(TIMEOUT)
                .header("Accept", "application/json").GET().build());
        jwks = loaded;
        jwksAt = now;
        return loaded;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(HttpRequest request) throws OidcException {
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream in = response.body()) {
                body = in.readNBytes(MAX_ANSWER + 1);
            }
            if (body.length > MAX_ANSWER) {
                throw new OidcException("the provider's answer is too large");
            }
            if (response.statusCode() != 200) {
                throw new OidcException("the provider answered " + response.statusCode());
            }
            Map<String, Object> value = ApiJson.mapper().readValue(body, Map.class);
            if (value == null) {
                throw new OidcException("the provider's answer is not a JSON object");
            }
            return value;
        } catch (IOException e) {
            throw new OidcException("the provider cannot be reached (" + e.getClass().getSimpleName() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OidcException("interrupted", e);
        } catch (RuntimeException e) {
            throw new OidcException("the provider's answer is not JSON", e);
        }
    }

    private String randomText() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The scopes asked for, for the documentation and tests.
     *
     * @return {@code openid} plus the configured scopes
     */
    List<String> scopes() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of("openid"), config.scopes().stream())
                .distinct().collect(Collectors.toList());
    }
}
