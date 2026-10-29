package net.vectora.cloud.config.model;

/**
 * TLS of the API listener. The keystore password is a secret and lives in {@code secrets/}.
 *
 * @param enabled  whether the API uses HTTPS
 * @param keystore PKCS#12 keystore, relative to the cloud home; required when enabled, null otherwise
 */
public record TlsConfig(boolean enabled, String keystore) {

    /** TLS off. */
    public static TlsConfig disabled() {
        return new TlsConfig(false, null);
    }
}
