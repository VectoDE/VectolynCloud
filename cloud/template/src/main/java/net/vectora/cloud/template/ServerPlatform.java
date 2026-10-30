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
    VELOCITY("plugins/floodgate/key.pem", "VECTORA_FORWARDING_SECRET", false, false),
    /**
     * Legacy Velocity proxy ({@code bungeeguard} forwarding, in front of the 1.8.8 servers):
     * {@code forwarding.secret} from {@code VECTORA_LEGACY_FORWARDING_SECRET}, which must differ from
     * the modern-forwarding secret. No Floodgate key yet: whether Bedrock players reach the 1.8.8
     * groups through this proxy is decided in stage 2, PR 24.
     */
    VELOCITY_LEGACY(null, "VECTORA_LEGACY_FORWARDING_SECRET", false, false),
    /**
     * Paper: Floodgate key, EULA, Spigot's restart turned off in {@code spigot.yml}, command blocks
     * off in {@code server.properties} (also for PandaSpigot and Fabric).
     */
    PAPER("plugins/floodgate/key.pem", null, true, true),
    /**
     * PandaSpigot 1.8.8 behind the legacy proxy: EULA, Spigot's restart turned off in
     * {@code spigot.yml}; Floodgate does not run there.
     */
    PANDASPIGOT(null, null, true, true),
    /** Fabric server with Floodgate-Fabric: Floodgate key under {@code config/} and EULA. */
    FABRIC("config/floodgate/key.pem", null, true, false),
    /** No Minecraft software (the database template): nothing is generated. */
    NONE(null, null, false, false);

    private final String floodgateKey;
    private final String forwardingPlaceholder;
    private final boolean eula;
    private final boolean spigot;

    ServerPlatform(String floodgateKey, String forwardingPlaceholder, boolean eula, boolean spigot) {
        this.floodgateKey = floodgateKey;
        this.forwardingPlaceholder = forwardingPlaceholder;
        this.eula = eula;
        this.spigot = spigot;
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

    /**
     * Tells whether the server reads {@code spigot.yml} and would restart itself through its
     * {@code restart-script}. The node turns that off, because restarts are its job.
     *
     * @return whether {@code restart-script: ''} and {@code restart-on-crash: true} are set after rendering
     */
    public boolean readsSpigotYml() {
        return spigot;
    }

    /**
     * Tells whether this is a Minecraft server that reads {@code server.properties} and could run
     * command blocks. The node always sets {@code enable-command-block=false} there; there is no
     * setting to turn that off.
     *
     * @return whether {@code enable-command-block=false} is forced after rendering
     */
    public boolean readsServerProperties() {
        return switch (this) {
            case PAPER, PANDASPIGOT, FABRIC -> true;
            case VELOCITY, VELOCITY_LEGACY, NONE -> false;
        };
    }
}
