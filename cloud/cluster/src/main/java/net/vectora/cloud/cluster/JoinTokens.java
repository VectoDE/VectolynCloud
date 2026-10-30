package net.vectora.cloud.cluster;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.io.AtomicFileWriter;
import net.vectora.cloud.config.io.ConfigFormat;
import net.vectora.cloud.config.io.ConfigMappers;
import net.vectora.cloud.config.io.ConfigYaml;
import net.vectora.cloud.config.io.RegularPaths;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.secret.OwnerOnlyPermissions;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.protocol.NodeId;

/**
 * The open join tokens of the head, in {@code secrets/cluster/join-tokens.yml} (owner only).
 *
 * <p>A token is {@code <id>.<secret>}: 6 and 32 characters of {@code [a-z0-9]} (165 bits of
 * secret). It is made for one node id and expires (1 minute to 24 hours, default 1 hour). The file
 * keeps the id, the node id, the expiry and the SHA-256 of the secret, never the secret itself.
 * A token opens one join: the first request with the right secret uses it up, whether the join
 * then succeeds or not. Three requests with a wrong secret for the same id use it up as well.
 * Every refusal looks the same to the peer ({@code INVALID_TOKEN}).
 */
public final class JoinTokens {

    /** Default lifetime of a token. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(1);
    /** Shortest lifetime. */
    public static final Duration MIN_TTL = Duration.ofMinutes(1);
    /** Longest lifetime. */
    public static final Duration MAX_TTL = Duration.ofHours(24);
    /** Most open tokens. */
    public static final int MAX_TOKENS = 32;
    /** Wrong secrets for one token before it is used up. */
    static final int MAX_FAILURES = 3;

    private static final String FILE = "secrets/cluster/join-tokens.yml";
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final ConfigFormat<Store> FORMAT = new ConfigFormat<>(Store.class, 1, List.of());

    private final Path file;
    private final SecureRandom random = new SecureRandom();

    /**
     * The file content.
     *
     * @param schemaVersion 1
     * @param tokens        open tokens
     */
    @JsonPropertyOrder({"schema-version", "tokens"})
    record Store(@JsonProperty(required = true) int schemaVersion, List<Entry> tokens) {
        Store {
            tokens = tokens == null ? List.of() : List.copyOf(tokens);
        }
    }

    /**
     * One open token.
     *
     * @param id        the public part
     * @param node      the node id it is for
     * @param hash      SHA-256 of the secret, hex
     * @param expiresAt expiry, ISO-8601 instant
     * @param failures  requests with a wrong secret so far
     */
    @JsonPropertyOrder({"id", "node", "hash", "expires-at", "failures"})
    record Entry(String id, String node, String hash, String expiresAt, int failures) {
    }

    /**
     * A token as listed, without any secret.
     *
     * @param id        the public part
     * @param node      the node it is for
     * @param expiresAt its expiry
     */
    public record Info(String id, NodeId node, Instant expiresAt) {
    }

    /**
     * A new token. {@link #token()} is shown once and not stored.
     *
     * @param info  the listed part
     * @param token {@code <id>.<secret>}
     */
    public record Created(Info info, Secret token) {
    }

    /** Result of {@link #redeem}. */
    public enum Redemption {
        /** Valid: the token is used up now. */
        VALID,
        /** Unknown, expired, used, wrong secret or for another node. */
        INVALID
    }

    /**
     * Opens the store; nothing is read until it is used.
     *
     * @param file {@code secrets/cluster/join-tokens.yml}
     */
    public JoinTokens(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * Creates a token.
     *
     * @param node the node it is for
     * @param ttl  its lifetime, {@link #MIN_TTL} to {@link #MAX_TTL}
     * @param now  the current time
     * @return the token
     * @throws IOException              if the file cannot be read or written
     * @throws IllegalArgumentException if the lifetime is out of range
     * @throws IllegalStateException    if {@link #MAX_TOKENS} tokens are open
     */
    public synchronized Created create(NodeId node, Duration ttl, Instant now) throws IOException {
        if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("lifetime must be 1m to 24h");
        }
        List<Entry> open = live(load(), now);
        if (open.size() >= MAX_TOKENS) {
            throw new IllegalStateException("too many open join tokens");
        }
        String id;
        do {
            id = randomText(6);
        } while (find(open, id).isPresent());
        String secret = randomText(32);
        Instant expires = now.plus(ttl);
        List<Entry> next = new ArrayList<>(open);
        next.add(new Entry(id, node.value(), hash(secret), expires.toString(), 0));
        save(next);
        return new Created(new Info(id, node, expires), Secret.of(id + "." + secret));
    }

    /**
     * The open tokens.
     *
     * @param now the current time; expired tokens are left out
     * @return the tokens, oldest first
     * @throws IOException if the file cannot be read
     */
    public synchronized List<Info> list(Instant now) throws IOException {
        return live(load(), now).stream()
                .map(e -> new Info(e.id(), new NodeId(e.node()), Instant.parse(e.expiresAt())))
                .toList();
    }

    /**
     * Deletes a token.
     *
     * @param id its public part
     * @return whether it existed
     * @throws IOException if the file cannot be read or written
     */
    public synchronized boolean revoke(String id) throws IOException {
        List<Entry> all = load();
        List<Entry> next = all.stream().filter(e -> !e.id().equals(id)).toList();
        if (next.size() == all.size()) {
            return false;
        }
        save(next);
        return true;
    }

    /**
     * Checks a token of a join request and uses it up if it is valid.
     *
     * @param id     public part from the request
     * @param secret secret part from the request
     * @param node   node id of the joining node
     * @param now    the current time
     * @return {@link Redemption#VALID} if the token exists, has not expired, has this secret and
     *         is for this node; the token is gone then
     * @throws IOException if the file cannot be read or written
     */
    public synchronized Redemption redeem(String id, Secret secret, NodeId node, Instant now) throws IOException {
        List<Entry> all = load();
        List<Entry> open = live(all, now);
        Optional<Entry> found = find(open, id);
        // The hash is compared even for an unknown id, so the time does not tell ids apart.
        String expected = found.map(Entry::hash).orElse(hash("decoy-" + id));
        boolean secretMatches = MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                hash(secret.reveal()).getBytes(StandardCharsets.US_ASCII));
        if (found.isEmpty()) {
            if (open.size() != all.size()) {
                save(open);
            }
            return Redemption.INVALID;
        }
        Entry entry = found.get();
        List<Entry> next = new ArrayList<>(open);
        next.remove(entry);
        if (secretMatches) {
            // Used up by the first right secret, even for the wrong node.
            save(next);
            return entry.node().equals(node.value()) ? Redemption.VALID : Redemption.INVALID;
        }
        if (entry.failures() + 1 < MAX_FAILURES) {
            next.add(new Entry(entry.id(), entry.node(), entry.hash(), entry.expiresAt(), entry.failures() + 1));
        }
        save(next);
        return Redemption.INVALID;
    }

    /**
     * Splits a written token.
     *
     * @param token {@code <id>.<secret>}
     * @return id and secret, or empty if the text is no token
     */
    public static Optional<String[]> split(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Matcher matcher = ClusterMessages.TOKEN.matcher(token);
        return matcher.matches() ? Optional.of(new String[] {matcher.group(1), matcher.group(2)}) : Optional.empty();
    }

    private static Optional<Entry> find(List<Entry> entries, String id) {
        return entries.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    private static List<Entry> live(List<Entry> entries, Instant now) {
        return entries.stream().filter(e -> Instant.parse(e.expiresAt()).isAfter(now)).toList();
    }

    private List<Entry> load() throws IOException {
        RegularPaths.requireFileOrMissing(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        byte[] content;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            content = in.readNBytes(ConfigMappers.MAX_FILE_BYTES + 1);
        }
        Store store;
        try {
            store = ConfigYaml.read(FILE, content, FORMAT).value();
        } catch (ConfigException e) {
            throw new IOException(e.describe(Language.EN), e);
        }
        for (Entry entry : store.tokens()) {
            if (entry == null || entry.id() == null || !entry.id().matches("[a-z0-9]{6}") || entry.node() == null
                    || entry.hash() == null || !entry.hash().matches("[0-9a-f]{64}") || entry.failures() < 0) {
                throw new IOException(FILE + " has an invalid entry");
            }
            try {
                new NodeId(entry.node());
                Instant.parse(entry.expiresAt());
            } catch (IllegalArgumentException | DateTimeParseException | NullPointerException e) {
                throw new IOException(FILE + " has an invalid entry", e);
            }
        }
        return store.tokens();
    }

    private void save(List<Entry> entries) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        RegularPaths.requireFileOrMissing(file);
        byte[] content = ("# Open join tokens of the cluster, written by VectoraCloud. Secrets are kept as SHA-256 only.\n"
                + ConfigMappers.yaml().writeValueAsString(new Store(1, entries))).getBytes(StandardCharsets.UTF_8);
        new AtomicFileWriter().write(file, content, OwnerOnlyPermissions::restrictFile);
        OwnerOnlyPermissions.restrictFile(file);
    }

    private String randomText(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    private static String hash(String secret) {
        return HexFormat.of().formatHex(CaPin.sha256(secret.getBytes(StandardCharsets.US_ASCII)));
    }
}
