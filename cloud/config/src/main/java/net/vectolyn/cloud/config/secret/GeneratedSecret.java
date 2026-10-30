package net.vectolyn.cloud.config.secret;

/**
 * The secrets the cloud generates on first start, with the keys of
 * {@code cloud/dist/secrets/vectolyn.env.example}.
 *
 * <p>The other keys of the example ({@code VECTOLYN_DB_HOST}, {@code VECTOLYN_DB_PORT},
 * {@code VECTOLYN_DB_NAME}, {@code VECTOLYN_DB_USER}) are not secrets; their values come from the
 * {@code database} section of {@code cloud.yml}.
 */
public enum GeneratedSecret {

    /** Velocity modern-forwarding secret, same on the proxy and every backend. */
    FORWARDING_SECRET("VECTOLYN_FORWARDING_SECRET", Form.ALPHANUMERIC, 48,
            "Velocity modern-forwarding secret, same on proxy and all backends"),
    /**
     * Forwarding secret of the legacy proxy ({@code bungeeguard} mode). Velocity sends it to the 1.8.8
     * backends as {@code bungeeguard-token} in plain text inside the handshake, so it is a secret of
     * its own and never the modern-forwarding secret.
     */
    LEGACY_FORWARDING_SECRET("VECTOLYN_LEGACY_FORWARDING_SECRET", Form.ALPHANUMERIC, 48,
            "Forwarding secret of the legacy proxy (bungeeguard token for the 1.8.8 servers). Never the same as"
                    + " VECTOLYN_FORWARDING_SECRET"),
    /** Password of the LuckPerms database user. */
    DB_PASSWORD("VECTOLYN_DB_PASSWORD", Form.ALPHANUMERIC, 32, "Password of the LuckPerms database user"),
    /** Password of the MariaDB {@code root} user (administration and cloud setup only). */
    DB_ROOT_PASSWORD("VECTOLYN_DB_ROOT_PASSWORD", Form.ALPHANUMERIC, 32,
            "Password of the MariaDB root user. Administration and cloud setup only"),
    /** {@code management-server-secret} in {@code server.properties}, exactly 40 alphanumeric characters. */
    MANAGEMENT_SECRET("VECTOLYN_MANAGEMENT_SECRET", Form.ALPHANUMERIC, 40,
            "management-server-secret for server.properties, 40 alphanumeric characters"),
    /** Floodgate AES key: 16 raw random bytes, Base64 encoded; the cloud decodes it into {@code key.pem}. */
    FLOODGATE_KEY("VECTOLYN_FLOODGATE_KEY_B64", Form.BASE64_BYTES, 16,
            "Floodgate AES key (16 bytes), base64 encoded; written to every key.pem");

    /** How a value is built. */
    public enum Form {
        /** {@code size} characters from {@code A-Z a-z 0-9}. */
        ALPHANUMERIC,
        /** {@code size} random bytes, Base64 encoded (with padding). */
        BASE64_BYTES
    }

    private final String key;
    private final Form form;
    private final int size;
    private final String description;

    GeneratedSecret(String key, Form form, int size, String description) {
        this.key = key;
        this.form = form;
        this.size = size;
        this.description = description;
    }

    /** Key in {@code vectolyn.env}. */
    public String key() {
        return key;
    }

    /** How the value is built. */
    public Form form() {
        return form;
    }

    /** Characters ({@link Form#ALPHANUMERIC}) or raw bytes ({@link Form#BASE64_BYTES}). */
    public int size() {
        return size;
    }

    /** Comment written above a newly appended entry. */
    public String description() {
        return description;
    }
}
