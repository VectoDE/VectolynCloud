package net.vectora.cloud.template;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Files the cloud creates in a runtime folder instead of copying them from the template.
 *
 * <p>Secret files are written so that only the owner can read them where the file system has
 * POSIX permissions. Error messages never contain a secret.
 */
public final class GeneratedFiles {

    /** Placeholder holding the Velocity modern-forwarding secret. */
    public static final String FORWARDING_PLACEHOLDER = "VECTORA_FORWARDING_SECRET";
    /** Placeholder holding the Floodgate AES key, base64 encoded. */
    public static final String FLOODGATE_PLACEHOLDER = "VECTORA_FLOODGATE_KEY_B64";
    /** File Velocity reads the forwarding secret from ({@code forwarding-secret-file} in {@code velocity.toml}). */
    public static final String FORWARDING_SECRET_FILE = "forwarding.secret";
    /** File the server checks for the accepted Minecraft EULA. */
    public static final String EULA_FILE = "eula.txt";
    /** Length of the raw Floodgate key in bytes (AES-128). */
    public static final int FLOODGATE_KEY_BYTES = 16;

    private static final String EULA = """
            # By changing the setting below to TRUE you are indicating your agreement to the Minecraft EULA (https://aka.ms/MinecraftEULA).
            # Written by VectoraCloud after the operator accepted the EULA.
            eula=true
            """;

    private GeneratedFiles() {
    }

    /**
     * Writes {@value #FORWARDING_SECRET_FILE} with {@code secret} as its only content (UTF-8, no line
     * break).
     *
     * @param runtime runtime folder of the proxy
     * @param secret the forwarding secret: at least 16 characters from {@code A-Z a-z 0-9 . _ ~ + / = -}
     * @return the written path relative to {@code runtime}
     * @throws TemplateException if the secret breaks that rule, or the path is unsafe
     * @throws IOException if writing fails
     */
    public static String writeForwardingSecret(Path runtime, String secret) throws TemplateException, IOException {
        checkForwardingSecret(secret);
        RuntimePaths.write(runtime, FORWARDING_SECRET_FILE, secret.getBytes(UTF_8), true);
        return FORWARDING_SECRET_FILE;
    }

    /**
     * Writes Floodgate's {@code key.pem} at the path {@code platform} expects: the raw 16 key bytes
     * decoded from {@code base64}, not PEM text despite the name.
     *
     * @param runtime runtime folder
     * @param platform server software; decides the path
     * @param base64 the key, base64 encoded (surrounding whitespace is ignored)
     * @return the written path relative to {@code runtime}
     * @throws IllegalArgumentException if {@code platform} runs no Floodgate
     * @throws TemplateException if the key is not base64 of exactly 16 bytes, or the path is unsafe
     * @throws IOException if writing fails
     */
    public static String writeFloodgateKey(Path runtime, ServerPlatform platform, String base64)
            throws TemplateException, IOException {
        String path = platform.floodgateKeyPath()
                .orElseThrow(() -> new IllegalArgumentException(platform + " runs no Floodgate"));
        byte[] key = decodeFloodgateKey(path, base64);
        RuntimePaths.write(runtime, path, key, true);
        return path;
    }

    /**
     * Writes {@value #EULA_FILE} with {@code eula=true}, but only if the operator accepted the
     * Minecraft EULA. Without consent nothing is written and an existing file stays as it is.
     *
     * @param runtime runtime folder
     * @param accepted whether the operator explicitly accepted the EULA
     * @return the written path relative to {@code runtime}, or empty without consent
     * @throws TemplateException if the path is unsafe
     * @throws IOException if writing fails
     */
    public static Optional<String> writeEula(Path runtime, boolean accepted) throws TemplateException, IOException {
        if (!accepted) {
            return Optional.empty();
        }
        RuntimePaths.write(runtime, EULA_FILE, EULA.getBytes(UTF_8), false);
        return Optional.of(EULA_FILE);
    }

    static void checkForwardingSecret(String secret) throws TemplateException {
        Objects.requireNonNull(secret, "secret");
        PlaceholderValueRules.checkSecret(FORWARDING_PLACEHOLDER, secret, FORWARDING_SECRET_FILE, TemplateException.NO_LINE);
    }

    static byte[] decodeFloodgateKey(String path, String base64) throws TemplateException {
        Objects.requireNonNull(base64, "base64");
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new TemplateException(path, FLOODGATE_PLACEHOLDER + " is not valid base64");
        }
        if (key.length != FLOODGATE_KEY_BYTES) {
            throw new TemplateException(path, FLOODGATE_PLACEHOLDER + " must decode to exactly " + FLOODGATE_KEY_BYTES
                    + " bytes, not " + key.length);
        }
        return key;
    }
}
