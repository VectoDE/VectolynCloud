package net.vectolyn.cloud.launcher.libs;

import java.util.Objects;

/**
 * One embedded library: a jar under {@code META-INF/vectolyn/libs/} and its expected SHA-256.
 *
 * @param fileName plain file name, already checked by {@link LibraryIndex}
 * @param sha256   expected SHA-256 as 64 lower-case hex digits
 */
public record LibraryEntry(String fileName, String sha256) {

    /** Validates that no component is {@code null}. */
    public LibraryEntry {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(sha256, "sha256");
    }
}
