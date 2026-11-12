// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.auth;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.io.AtomicFileWriter;
import net.vectolyn.cloud.config.io.ConfigMappers;
import net.vectolyn.cloud.config.io.RegularPaths;
import net.vectolyn.cloud.config.secret.OwnerOnlyPermissions;
import net.vectolyn.cloud.config.secret.Secret;
import net.vectolyn.cloud.config.validation.ConfigNames;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API tokens of the node, in {@code <home>/secrets/api-tokens.json}.
 *
 * <p>A token is {@code vct_} followed by 43 characters (32 random bytes, Base64url). The cloud shows
 * it once, when it is created, and keeps only its SHA-256 with id, name, scopes, creation time and,
 * if it was given a lifetime, its expiry. From the expiry on it fails like a revoked token; it stays
 * in the list (marked expired) until it is revoked, so its name is not taken over silently.
 * The file is owner-only like {@code vectolyn.env}, never a symbolic link, and replaced atomically.
 *
 * <p>{@link #authenticate} hashes the presented token and compares it with every stored hash in
 * constant time ({@link MessageDigest#isEqual}), without stopping at the first match, so the time
 * does not tell how much of a token was right or which entry matched.
 *
 * <p>Thread-safe. The console ({@code api token create|list|revoke}) and the API server share one
 * instance; a revoked token stops working at once and its WebSocket connections are closed
 * ({@link #onRevoke}).
 *
 * <p>The store also holds at most one <em>setup token</em> ({@link #issueSetupToken}): {@code vcs_}
 * and 43 characters, made at the end of the first setup for the first administrator. It is shown
 * once, only its SHA-256 and its expiry are kept in {@code secrets/setup-token.json}, and it does
 * exactly one thing: {@code POST /api/v1/setup/claim} exchanges it, once, for an API token with
 * {@link Scope#ADMIN} named after that administrator ({@link #claimSetupToken}). A new setup token
 * replaces the old one. There is no default password anywhere.
 */
public final class ApiTokens {

    /** Prefix of every token. */
    public static final String PREFIX = "vct_";
    /** File name in {@code secrets/}. */
    public static final String FILE_NAME = "api-tokens.json";
    /** Largest number of tokens. */
    public static final int MAX_TOKENS = 100;
    /** Current version of the file format. */
    public static final int SCHEMA_VERSION = 1;
    /** Shortest lifetime of a token. */
    public static final Duration MIN_LIFETIME = Duration.ofHours(1);
    /** Longest lifetime of a token, about ten years. */
    public static final Duration MAX_LIFETIME = Duration.ofDays(3650);
    /** Text form of a lifetime. */
    public static final String LIFETIME_FORMAT = "<number>h or <number>d, for example 90d";

    private static final Pattern LIFETIME = Pattern.compile("([1-9][0-9]{0,5})([hd])");

    /** Prefix of the one-time setup token. */
    public static final String SETUP_PREFIX = "vcs_";
    /** File name of the setup token in {@code secrets/}. */
    public static final String SETUP_FILE_NAME = "setup-token.json";
    /** How long a setup token is valid unless the caller says otherwise. */
    public static final Duration SETUP_TOKEN_TTL = Duration.ofHours(24);
    /** Longest validity of a setup token. */
    public static final Duration MAX_SETUP_TOKEN_TTL = Duration.ofDays(7);

    private static final int RANDOM_BYTES = 32;
    private static final Pattern SETUP_TOKEN = Pattern.compile("vcs_[A-Za-z0-9_-]{43}");
    private static final Pattern TOKEN = Pattern.compile("vct_[A-Za-z0-9_-]{43}");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_FILE_BYTES = 256 * 1024;

    private static final JsonMapper JSON = ConfigMappers.configure(JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(8).maxStringLength(1024).maxDocumentLength(MAX_FILE_BYTES).build())
                    .build()))
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private final Path file;
    private final Clock clock;
    private final SecureRandom random;
    private final AtomicFileWriter writer = new AtomicFileWriter();
    private final List<Consumer<ApiToken>> revokeListeners = new CopyOnWriteArrayList<>();
    private volatile List<Entry> entries;
    private volatile SetupEntry setup;

    private ApiTokens(Path file, Clock clock, SecureRandom random, List<Entry> entries, SetupEntry setup) {
        this.file = file;
        this.clock = clock;
        this.random = random;
        this.entries = List.copyOf(entries);
        this.setup = setup;
    }

    /**
     * Opens {@code <home>/secrets/api-tokens.json}; a missing file means no tokens.
     *
     * @param home the cloud home
     * @return the store
     * @throws IOException if the file is a link, cannot be read or is malformed (the message names the rule, never a value)
     */
    public static ApiTokens open(CloudHome home) throws IOException {
        return open(home.secretsDir().resolve(FILE_NAME), Clock.systemUTC(), new SecureRandom());
    }

    /**
     * Opens a token file (tests pass their own clock and random source).
     *
     * @param file   the file
     * @param clock  source of creation times
     * @param random source of tokens and ids
     * @return the store
     * @throws IOException if the file is a link, cannot be read or is malformed
     */
    public static ApiTokens open(Path file, Clock clock, SecureRandom random) throws IOException {
        Objects.requireNonNull(file, "file");
        RegularPaths.requireDirectoryOrMissing(file.getParent());
        RegularPaths.requireFileOrMissing(file);
        List<Entry> entries = new ArrayList<>();
        if (Files.exists(file)) {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new IOException(FILE_NAME + " is larger than " + MAX_FILE_BYTES + " bytes");
            }
            entries.addAll(parse(Files.readAllBytes(file)));
        }
        Path setupFile = file.resolveSibling(SETUP_FILE_NAME);
        RegularPaths.requireFileOrMissing(setupFile);
        SetupEntry setup = null;
        if (Files.exists(setupFile)) {
            if (Files.size(setupFile) > MAX_FILE_BYTES) {
                throw new IOException(SETUP_FILE_NAME + " is larger than " + MAX_FILE_BYTES + " bytes");
            }
            setup = parseSetup(Files.readAllBytes(setupFile));
        }
        return new ApiTokens(file, clock, random, entries, setup);
    }

    /**
     * Reads the content of a setup token file.
     *
     * @param content the bytes of the file
     * @return the entry
     * @throws IOException if the content is malformed; the message never contains a value of the file
     */
    static SetupEntry parseSetup(byte[] content) throws IOException {
        SetupFile parsed;
        try {
            parsed = JSON.readValue(content, SetupFile.class);
        } catch (JacksonException e) {
            throw new IOException(SETUP_FILE_NAME + " is not a valid setup token file (unknown field, wrong type or broken JSON)");
        }
        if (parsed == null || parsed.schemaVersion() != SCHEMA_VERSION || parsed.token() == null) {
            throw new IOException(SETUP_FILE_NAME + ": schema-version must be " + SCHEMA_VERSION + " and token must be set");
        }
        SetupEntry entry = parsed.token();
        if (entry.admin() == null || !ConfigNames.isValidName(entry.admin())) {
            throw new IOException(SETUP_FILE_NAME + ": token.admin must match " + ConfigNames.NAME);
        }
        if (entry.sha256() == null || !HASH.matcher(entry.sha256()).matches()) {
            throw new IOException(SETUP_FILE_NAME + ": token.sha256 must be 64 lower-case hex characters");
        }
        if (!isInstant(entry.expiresAt())) {
            throw new IOException(SETUP_FILE_NAME + ": token.expires-at must be an ISO-8601 instant");
        }
        return entry;
    }

    /**
     * Reads the content of a token file.
     *
     * @param content the bytes of the file
     * @return the entries
     * @throws IOException if the content is malformed; the message never contains a value of the file
     */
    static List<Entry> parse(byte[] content) throws IOException {
        TokenFile parsed;
        try {
            parsed = JSON.readValue(content, TokenFile.class);
        } catch (JacksonException e) {
            throw new IOException(FILE_NAME + " is not a valid token file (unknown field, wrong type or broken JSON)");
        }
        if (parsed == null || parsed.schemaVersion() != SCHEMA_VERSION) {
            throw new IOException(FILE_NAME + ": schema-version must be " + SCHEMA_VERSION);
        }
        List<Entry> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<Entry> listed = parsed.tokens() == null ? List.of() : parsed.tokens();
        if (listed.size() > MAX_TOKENS) {
            throw new IOException(FILE_NAME + " holds more than " + MAX_TOKENS + " tokens");
        }
        for (int i = 0; i < listed.size(); i++) {
            Entry entry = listed.get(i);
            String where = FILE_NAME + ": tokens[" + i + "]";
            if (entry == null || entry.id() == null || !ID.matcher(entry.id()).matches()) {
                throw new IOException(where + ".id must match " + ID.pattern());
            }
            if (entry.name() == null || !ConfigNames.isValidName(entry.name())) {
                throw new IOException(where + ".name must match " + ConfigNames.NAME);
            }
            if (entry.sha256() == null || !HASH.matcher(entry.sha256()).matches()) {
                throw new IOException(where + ".sha256 must be 64 lower-case hex characters");
            }
            if (entry.scopes() == null || entry.scopes().isEmpty()
                    || entry.scopes().stream().anyMatch(s -> Scope.of(s).isEmpty())) {
                throw new IOException(where + ".scopes must list known scopes");
            }
            if (!isInstant(entry.createdAt())) {
                throw new IOException(where + ".created-at must be an ISO-8601 instant");
            }
            if (entry.expiresAt() != null && (!isInstant(entry.expiresAt())
                    || !Instant.parse(entry.expiresAt()).isAfter(Instant.parse(entry.createdAt())))) {
                throw new IOException(where + ".expires-at must be an ISO-8601 instant after created-at");
            }
            if (!ids.add(entry.id()) || !names.add(entry.name())) {
                throw new IOException(where + " repeats an id or a name");
            }
            result.add(entry);
        }
        return result;
    }

    /**
     * Creates a token. The returned {@link Created#token()} is the only copy; it is not stored.
     *
     * @param name   label, {@code [a-z0-9][a-z0-9_-]{0,31}}, unique
     * @param scopes at least one scope
     * @return the token and its description
     * @throws IllegalArgumentException if the name is invalid or taken, there are no scopes, or the limit is reached
     * @throws IOException              if the file cannot be written; then no token exists
     */
    public Created create(String name, Set<Scope> scopes) throws IOException {
        return create(name, scopes, null);
    }

    /**
     * Creates a token that stops working after {@code lifetime}. The returned
     * {@link Created#token()} is the only copy; it is not stored.
     *
     * @param name     label, {@code [a-z0-9][a-z0-9_-]{0,31}}, unique
     * @param scopes   at least one scope
     * @param lifetime from {@link #MIN_LIFETIME} to {@link #MAX_LIFETIME}, or null for a token that
     *                 works until it is revoked
     * @return the token and its description
     * @throws IllegalArgumentException if the name is invalid or taken, there are no scopes, the
     *                                  lifetime is out of range, or the limit is reached
     * @throws IOException              if the file cannot be written; then no token exists
     */
    public synchronized Created create(String name, Set<Scope> scopes, Duration lifetime) throws IOException {
        if (lifetime != null && (lifetime.compareTo(MIN_LIFETIME) < 0 || lifetime.compareTo(MAX_LIFETIME) > 0)) {
            throw new IllegalArgumentException("the lifetime must be from 1h to 3650d");
        }
        if (name == null || !ConfigNames.isValidName(name)) {
            throw new IllegalArgumentException("name must match " + ConfigNames.NAME);
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("at least one scope is required");
        }
        List<Entry> current = entries;
        if (current.stream().anyMatch(e -> e.name().equals(name))) {
            throw new IllegalArgumentException("a token with this name exists");
        }
        if (current.size() >= MAX_TOKENS) {
            throw new IllegalArgumentException("at most " + MAX_TOKENS + " tokens");
        }
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id;
        do {
            byte[] idBytes = new byte[4];
            random.nextBytes(idBytes);
            id = HexFormat.of().formatHex(idBytes);
        } while (find(current, id).isPresent());
        Instant now = clock.instant();
        List<String> scopeIds = EnumSet.copyOf(scopes).stream().map(Scope::id).toList();
        String expiresAt = lifetime == null ? null : now.plus(lifetime).toString();
        Entry entry = new Entry(id, name, scopeIds, now.toString(), expiresAt, HexFormat.of().formatHex(sha256(token)));
        List<Entry> next = new ArrayList<>(current);
        next.add(entry);
        save(next);
        entries = List.copyOf(next);
        return new Created(Secret.of(token), entry.toToken());
    }

    /**
     * Makes the one-time setup token for the first administrator and replaces an earlier one. The
     * returned {@link SetupToken#token()} is the only copy; the file keeps its SHA-256 and expiry.
     *
     * @param admin name of the administrator, {@code [a-z0-9][a-z0-9_-]{0,31}}; the claimed API token
     *              gets this name (with {@code -2}, {@code -3}, ... if it is taken)
     * @param ttl   how long it is valid, more than zero and at most {@link #MAX_SETUP_TOKEN_TTL}
     * @return the token, the administrator and when it expires
     * @throws IllegalArgumentException if the name or the validity is out of range
     * @throws IOException              if the file cannot be written; then no setup token is valid
     */
    public synchronized SetupToken issueSetupToken(String admin, Duration ttl) throws IOException {
        if (admin == null || !ConfigNames.isValidName(admin)) {
            throw new IllegalArgumentException("admin must match " + ConfigNames.NAME);
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_SETUP_TOKEN_TTL) > 0) {
            throw new IllegalArgumentException("ttl must be more than zero and at most " + MAX_SETUP_TOKEN_TTL);
        }
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        String token = SETUP_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = clock.instant().plus(ttl);
        SetupEntry entry = new SetupEntry(admin, expires.toString(), HexFormat.of().formatHex(sha256(token)));
        // The old token stops working before the new file is written, so a failed write leaves none.
        setup = null;
        Path target = setupFile();
        prepareDirectory();
        RegularPaths.requireFileOrMissing(target);
        writer.write(target, JSON.writeValueAsBytes(new SetupFile(SCHEMA_VERSION, entry)), OwnerOnlyPermissions::restrictFile);
        OwnerOnlyPermissions.restrictFile(target);
        setup = entry;
        return new SetupToken(Secret.of(token), admin, expires);
    }

    /**
     * Checks a setup token presented by a client, without using it up.
     *
     * @param presented the text after {@code Bearer }, may be anything
     * @return the administrator it is for, if it is the current setup token and not expired
     */
    public Optional<String> setupAdmin(String presented) {
        SetupEntry current = setup;
        if (current == null || presented == null || !SETUP_TOKEN.matcher(presented).matches()) {
            return Optional.empty();
        }
        boolean equal = MessageDigest.isEqual(sha256(presented), HexFormat.of().parseHex(current.sha256()));
        if (!equal || !clock.instant().isBefore(Instant.parse(current.expiresAt()))) {
            return Optional.empty();
        }
        return Optional.of(current.admin());
    }

    /**
     * Exchanges the setup token, once, for an API token with {@link Scope#ADMIN}. The setup token
     * is removed first, so it can never be used twice; if the API token cannot be written afterwards,
     * a new setup token is needed ({@code api token setup}).
     *
     * @param presented the setup token
     * @return the new API token, or empty if {@code presented} is not a valid, unexpired setup token
     * @throws IOException if a file cannot be written
     */
    public synchronized Optional<Created> claimSetupToken(String presented) throws IOException {
        Optional<String> admin = setupAdmin(presented);
        if (admin.isEmpty()) {
            return Optional.empty();
        }
        setup = null;
        Files.deleteIfExists(setupFile());
        List<Entry> current = entries;
        String name = admin.get();
        for (int i = 2; taken(current, name); i++) {
            name = candidate(admin.get(), i);
        }
        return Optional.of(create(name, EnumSet.of(Scope.ADMIN)));
    }

    /**
     * When the current setup token expires.
     *
     * @return the expiry, or empty if there is no setup token
     */
    public Optional<Instant> setupTokenExpiry() {
        SetupEntry current = setup;
        return current == null ? Optional.empty() : Optional.of(Instant.parse(current.expiresAt()));
    }

    private static boolean taken(List<Entry> list, String name) {
        return list.stream().anyMatch(e -> e.name().equals(name));
    }

    /** {@code admin} for the first try, then {@code admin-2}, ... cut so the name stays valid. */
    private static String candidate(String admin, int number) {
        if (number == 1) {
            return admin;
        }
        String suffix = "-" + number;
        String head = admin.length() + suffix.length() > 32 ? admin.substring(0, 32 - suffix.length()) : admin;
        return head + suffix;
    }

    private Path setupFile() {
        return file.resolveSibling(SETUP_FILE_NAME);
    }

    private void prepareDirectory() throws IOException {
        Path dir = file.getParent();
        RegularPaths.requireDirectoryOrMissing(dir);
        Files.createDirectories(dir);
        OwnerOnlyPermissions.restrictDirectory(dir);
    }

    /**
     * Every token, in creation order.
     *
     * @return the descriptions; never a token or a hash
     */
    public List<ApiToken> list() {
        return entries.stream().map(Entry::toToken).toList();
    }

    /**
     * Revokes a token by id or name. It stops working at once.
     *
     * @param idOrName the id ({@code a1b2c3d4}) or the name
     * @return the revoked token, or empty if there is none
     * @throws IOException if the file cannot be written; then the token stays valid
     */
    public synchronized Optional<ApiToken> revoke(String idOrName) throws IOException {
        List<Entry> current = entries;
        Optional<Entry> found = current.stream()
                .filter(e -> e.id().equals(idOrName) || e.name().equals(idOrName)).findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<Entry> next = new ArrayList<>(current);
        next.remove(found.get());
        save(next);
        entries = List.copyOf(next);
        ApiToken token = found.get().toToken();
        for (Consumer<ApiToken> listener : revokeListeners) {
            listener.accept(token);
        }
        return Optional.of(token);
    }

    /**
     * Checks a token presented by a client.
     *
     * @param presented the text after {@code Bearer }, may be anything
     * @return the token's description if it is valid, not revoked and not expired
     */
    public Optional<ApiToken> authenticate(String presented) {
        if (presented == null || !TOKEN.matcher(presented).matches()) {
            return Optional.empty();
        }
        byte[] hash = sha256(presented);
        Entry match = null;
        for (Entry entry : entries) {
            // Every entry is compared, whatever matched before.
            boolean equal = MessageDigest.isEqual(hash, entry.hashBytes());
            if (equal) {
                match = entry;
            }
        }
        if (match == null) {
            return Optional.empty();
        }
        ApiToken token = match.toToken();
        return token.isExpired(clock.instant()) ? Optional.empty() : Optional.of(token);
    }

    /**
     * Whether a token id is still valid, for connections that were opened before a revoke.
     *
     * @param id the token id
     * @return whether a token with this id exists and has not expired
     */
    public boolean isValid(String id) {
        Instant now = clock.instant();
        return find(entries, id).map(e -> !e.toToken().isExpired(now)).orElse(false);
    }

    /**
     * Parses a lifetime such as {@code 12h} or {@code 90d}.
     *
     * @param text the text
     * @return the lifetime
     * @throws IllegalArgumentException if it does not have the form {@value #LIFETIME_FORMAT} or is
     *                                  outside 1h to 3650d
     */
    public static Duration parseLifetime(String text) {
        Matcher m = LIFETIME.matcher(text == null ? "" : text);
        if (!m.matches()) {
            throw new IllegalArgumentException("the lifetime must look like " + LIFETIME_FORMAT);
        }
        long value = Long.parseLong(m.group(1));
        Duration lifetime = m.group(2).equals("h") ? Duration.ofHours(value) : Duration.ofDays(value);
        if (lifetime.compareTo(MIN_LIFETIME) < 0 || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("the lifetime must be from 1h to 3650d");
        }
        return lifetime;
    }

    /**
     * The clock of this store.
     *
     * @return the clock
     */
    public Clock clock() {
        return clock;
    }

    /**
     * Registers a listener that is told about every revoked token, on the thread that revoked it.
     *
     * @param listener the listener
     */
    public void onRevoke(Consumer<ApiToken> listener) {
        revokeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * The file of this store.
     *
     * @return the path
     */
    public Path file() {
        return file;
    }

    private void save(List<Entry> next) throws IOException {
        prepareDirectory();
        RegularPaths.requireFileOrMissing(file);
        byte[] content = JSON.writeValueAsBytes(new TokenFile(SCHEMA_VERSION, next));
        writer.write(file, content, OwnerOnlyPermissions::restrictFile);
        OwnerOnlyPermissions.restrictFile(file);
    }

    private static boolean isInstant(String text) {
        if (text == null) {
            return false;
        }
        try {
            Instant.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static Optional<Entry> find(List<Entry> list, String id) {
        return list.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    /**
     * A new token.
     *
     * @param token the token; shown once, never stored
     * @param info  its description
     */
    public record Created(Secret token, ApiToken info) {
    }

    /**
     * A new setup token.
     *
     * @param token     the token; shown once, never stored
     * @param admin     the administrator it is for
     * @param expiresAt when it stops working
     */
    public record SetupToken(Secret token, String admin, Instant expiresAt) {
    }

    /** Content of the setup token file. */
    @JsonPropertyOrder({"schema-version", "token"})
    record SetupFile(int schemaVersion, SetupEntry token) {
    }

    /** The stored setup token. */
    @JsonPropertyOrder({"admin", "expires-at", "sha256"})
    record SetupEntry(String admin, String expiresAt, String sha256) {
    }

    /** Content of the token file. */
    @JsonPropertyOrder({"schema-version", "tokens"})
    record TokenFile(int schemaVersion, List<Entry> tokens) {
    }

    /** One stored token. */
    @JsonPropertyOrder({"id", "name", "scopes", "created-at", "expires-at", "sha256"})
    record Entry(String id, String name, List<String> scopes, String createdAt, String expiresAt, String sha256) {

        byte[] hashBytes() {
            return HexFormat.of().parseHex(sha256);
        }

        ApiToken toToken() {
            Set<Scope> parsed = EnumSet.noneOf(Scope.class);
            scopes.forEach(s -> Scope.of(s).ifPresent(parsed::add));
            return new ApiToken(id, name, parsed, Instant.parse(createdAt),
                    expiresAt == null ? null : Instant.parse(expiresAt));
        }
    }
}
