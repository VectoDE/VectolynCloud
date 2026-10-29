package net.vectora.cloud.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.config.secret.SecretStore;
import net.vectora.cloud.console.Text;

/**
 * Reads the password of an external database from a file named in the setup file, for example a
 * Docker secret under {@code /run/secrets/}. The file holds the password on one line; a single line
 * break at the end (which most editors and {@code echo} add) is dropped, nothing else is changed.
 */
public final class PasswordFile {

    /** Largest file accepted, 4 KiB; a password is far shorter. */
    public static final int MAX_BYTES = 4096;

    /**
     * Either a secret or a problem.
     *
     * @param secret  the password, or null
     * @param problem what is wrong, or null
     */
    public record Result(Secret secret, Text problem) {

        /** Exactly one of both is set. */
        public Result {
            if ((secret == null) == (problem == null)) {
                throw new IllegalArgumentException("either a secret or a problem");
            }
        }

        /** Never shows the secret. */
        @Override
        public String toString() {
            return secret != null ? "Result[secret=" + Secret.MASK + "]" : "Result[problem]";
        }
    }

    private PasswordFile() {
    }

    /** Parses the content of a password file. */
    public static Result parse(byte[] content) {
        Objects.requireNonNull(content, "content");
        if (content.length > MAX_BYTES) {
            return new Result(null, SetupMessages.FILE_PASSWORD_FORM);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return new Result(null, SetupMessages.FILE_PASSWORD_FORM);
        }
        if (text.endsWith("\r\n")) {
            text = text.substring(0, text.length() - 2);
        } else if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        Secret secret = Secret.of(text);
        if (secret.isEmpty() || !SecretStore.isStorable(secret)) {
            return new Result(null, SetupMessages.FILE_PASSWORD_FORM);
        }
        return new Result(secret, null);
    }

    /** Reads {@code file}; a relative path is resolved against {@code home}. */
    public static Result read(Path home, String file) {
        Path path;
        try {
            path = home.resolve(file);
        } catch (RuntimeException e) {
            return new Result(null, SetupMessages.FILE_PASSWORD_UNREADABLE);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] content = in.readNBytes(MAX_BYTES + 1);
            return parse(content);
        } catch (IOException | SecurityException e) {
            return new Result(null, SetupMessages.FILE_PASSWORD_UNREADABLE);
        }
    }
}
