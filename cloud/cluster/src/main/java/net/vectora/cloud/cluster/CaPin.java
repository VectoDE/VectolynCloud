package net.vectora.cloud.cluster;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The pin of the cluster CA a joining node checks before it sends its join token: the SHA-256 of
 * the CA certificate's public key (its DER {@code SubjectPublicKeyInfo}), written
 * {@code sha256:<64 hex digits>}, the format of {@code kubeadm join --discovery-token-ca-cert-hash}.
 * The pin covers the key, not the certificate, so a CA certificate renewed with the same key keeps
 * its pin.
 */
public final class CaPin {

    /** Prefix of the written form. */
    public static final String PREFIX = "sha256:";

    private final byte[] hash;

    private CaPin(byte[] hash) {
        this.hash = hash.clone();
    }

    /**
     * The pin of a CA certificate.
     *
     * @param ca the certificate
     * @return its pin
     */
    public static CaPin of(X509Certificate ca) {
        Objects.requireNonNull(ca, "ca");
        return new CaPin(sha256(ca.getPublicKey().getEncoded()));
    }

    /**
     * Parses {@code sha256:<64 hex digits>} (upper or lower case, colons between bytes allowed).
     *
     * @param text the written pin
     * @return the pin, or empty if the text is not one
     */
    public static Optional<CaPin> parse(String text) {
        if (text == null || !text.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return Optional.empty();
        }
        String hex = text.substring(PREFIX.length()).replace(":", "");
        if (!hex.matches("[0-9A-Fa-f]{64}")) {
            return Optional.empty();
        }
        return Optional.of(new CaPin(HexFormat.of().parseHex(hex.toLowerCase(Locale.ROOT))));
    }

    /**
     * Whether {@code certificate} has the pinned key. Compares in constant time.
     *
     * @param certificate the certificate to check
     * @return true if its public key has this hash
     */
    public boolean matches(X509Certificate certificate) {
        return MessageDigest.isEqual(hash, sha256(certificate.getPublicKey().getEncoded()));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CaPin pin && MessageDigest.isEqual(hash, pin.hash);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }

    /** The written form, {@code sha256:<hex>}. A pin is public, so it may be printed. */
    @Override
    public String toString() {
        return PREFIX + HexFormat.of().formatHex(hash);
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java", e);
        }
    }
}
