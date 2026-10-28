package net.vectora.cloud.template;

import java.util.Optional;

/**
 * Server software a template is for. It decides which files the cloud generates next to the
 * rendered template.
 */
public enum ServerPlatform {

    /** Velocity proxy: {@code forwarding.secret} and the Floodgate key, no EULA. */
    VELOCITY("plugins/floodgate/key.pem", true, false),
    /** Paper (and forks such as PandaSpigot): Floodgate key and EULA. */
    PAPER("plugins/floodgate/key.pem", false, true),
    /** Fabric server with Floodgate-Fabric: Floodgate key under {@code config/} and EULA. */
    FABRIC("config/floodgate/key.pem", false, true),
    /** No Minecraft software (the database template): nothing is generated. */
    NONE(null, false, false);

    private final String floodgateKey;
    private final boolean forwardingSecretFile;
    private final boolean eula;

    ServerPlatform(String floodgateKey, boolean forwardingSecretFile, boolean eula) {
        this.floodgateKey = floodgateKey;
        this.forwardingSecretFile = forwardingSecretFile;
        this.eula = eula;
    }

    /**
     * Returns where Floodgate expects its key, relative to the runtime folder.
     *
     * @return the {@code key.pem} path, or empty if this platform runs no Floodgate
     */
    public Optional<String> floodgateKeyPath() {
        return Optional.ofNullable(floodgateKey);
    }

    /**
     * Tells whether the forwarding secret is read from {@code forwarding.secret} (Velocity) rather
     * than from a rendered config file (the backends).
     *
     * @return whether {@code forwarding.secret} is generated
     */
    public boolean usesForwardingSecretFile() {
        return forwardingSecretFile;
    }

    /**
     * Tells whether the server refuses to start without {@code eula.txt}.
     *
     * @return whether {@code eula.txt} is generated after consent
     */
    public boolean needsEula() {
        return eula;
    }
}
