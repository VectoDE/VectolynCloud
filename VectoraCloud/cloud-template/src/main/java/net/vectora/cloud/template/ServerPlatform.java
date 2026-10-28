package net.vectora.cloud.template;

import java.util.Optional;

/**
 * Server software a template is for. It decides which files the cloud generates next to the
 * rendered template.
 */
public enum ServerPlatform {

    /**
     * Main Velocity proxy ({@code modern} forwarding): {@code forwarding.secret} from
     * {@code VECTORA_FORWARDING_SECRET} and the Floodgate key, no EULA.
     */
    VELOCITY("plugins/floodgate/key.pem", "VECTORA_FORWARDING_SECRET", false),
    /**
     * Legacy Velocity proxy ({@code bungeeguard} forwarding, in front of the 1.8.8 servers):
     * {@code forwarding.secret} from {@code VECTORA_LEGACY_FORWARDING_SECRET}, which must differ from
     * the modern-forwarding secret. No Floodgate key yet: whether Bedrock players reach the 1.8.8
     * groups through this proxy is decided in stage 2, PR 24.
     */
    VELOCITY_LEGACY(null, "VECTORA_LEGACY_FORWARDING_SECRET", false),
    /** Paper: Floodgate key and EULA. */
    PAPER("plugins/floodgate/key.pem", null, true),
    /** PandaSpigot 1.8.8 behind the legacy proxy: EULA only; Floodgate does not run there. */
    PANDASPIGOT(null, null, true),
    /** Fabric server with Floodgate-Fabric: Floodgate key under {@code config/} and EULA. */
    FABRIC("config/floodgate/key.pem", null, true),
    /** No Minecraft software (the database template): nothing is generated. */
    NONE(null, null, false);

    private final String floodgateKey;
    private final String forwardingPlaceholder;
    private final boolean eula;

    ServerPlatform(String floodgateKey, String forwardingPlaceholder, boolean eula) {
        this.floodgateKey = floodgateKey;
        this.forwardingPlaceholder = forwardingPlaceholder;
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
        return forwardingPlaceholder != null;
    }

    /**
     * Returns the placeholder whose value goes into {@code forwarding.secret}.
     *
     * @return the placeholder name, or empty if this platform has no {@code forwarding.secret}
     */
    public Optional<String> forwardingPlaceholder() {
        return Optional.ofNullable(forwardingPlaceholder);
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
