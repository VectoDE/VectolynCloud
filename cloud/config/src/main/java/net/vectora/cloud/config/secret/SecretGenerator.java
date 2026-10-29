package net.vectora.cloud.config.secret;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Creates the values of {@link GeneratedSecret} from a {@link SecureRandom}. Thread-safe. */
public final class SecretGenerator {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final SecureRandom random;

    /** Generator with the platform's default {@link SecureRandom}. */
    public SecretGenerator() {
        this(new SecureRandom());
    }

    /** Generator with a given source (tests). */
    public SecretGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /** A new value for {@code kind}. */
    public Secret generate(GeneratedSecret kind) {
        return switch (kind.form()) {
            case ALPHANUMERIC -> alphanumeric(kind.size());
            case BASE64_BYTES -> base64Bytes(kind.size());
        };
    }

    /** A new value for every {@link GeneratedSecret}. */
    public Map<GeneratedSecret, Secret> generateAll() {
        Map<GeneratedSecret, Secret> all = new EnumMap<>(GeneratedSecret.class);
        for (GeneratedSecret kind : GeneratedSecret.values()) {
            all.put(kind, generate(kind));
        }
        return all;
    }

    /** {@code length} characters from {@code A-Z a-z 0-9}, uniformly distributed. */
    public Secret alphanumeric(int length) {
        if (length < 1) {
            throw new IllegalArgumentException("length must be positive");
        }
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return Secret.of(out.toString());
    }

    /** {@code bytes} random bytes, Base64 encoded. */
    public Secret base64Bytes(int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("bytes must be positive");
        }
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Secret.of(Base64.getEncoder().encodeToString(raw));
    }
}
