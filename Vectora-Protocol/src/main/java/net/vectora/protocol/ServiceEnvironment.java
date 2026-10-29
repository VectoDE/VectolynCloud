package net.vectora.protocol;

/**
 * Names of the environment variables the cloud sets for every service process. The node writes
 * them ({@code cloud-node}), the client and the bridges read them ({@code Vectora-CloudClient}).
 * Both sides use these constants, so the names cannot drift apart.
 */
public final class ServiceEnvironment {

    /** Host of the cloud channel the service connects to. */
    public static final String CLOUD_HOST = "VECTORA_CLOUD_HOST";

    /** Port of the cloud channel. */
    public static final String CLOUD_PORT = "VECTORA_CLOUD_PORT";

    /** Id of the service, for example {@code lobby-1}. */
    public static final String SERVICE_ID = "VECTORA_SERVICE_ID";

    /** Token of this start of the service; a secret. */
    public static final String SERVICE_TOKEN = "VECTORA_SERVICE_TOKEN";

    /** Group of the service. */
    public static final String SERVICE_GROUP = "VECTORA_SERVICE_GROUP";

    /** Task that started the service. */
    public static final String SERVICE_TASK = "VECTORA_SERVICE_TASK";

    /** Port the server listens on. */
    public static final String SERVICE_PORT = "VECTORA_SERVICE_PORT";

    /**
     * Platform of the service, the name of cloud-template's {@code ServerPlatform}
     * ({@code VELOCITY}, {@code VELOCITY_LEGACY}, {@code PAPER}, {@code PANDASPIGOT}, ...). The
     * Velocity bridge tells the main proxy from the legacy proxy by it.
     */
    public static final String SERVICE_PLATFORM = "VECTORA_SERVICE_PLATFORM";

    /**
     * Forwarding secret of the legacy proxy, for the 1.8.8 backends behind it; a secret. The legacy
     * bridge checks the {@code bungeeguard-token} of every login against it.
     */
    public static final String LEGACY_FORWARDING_SECRET = "VECTORA_LEGACY_FORWARDING_SECRET";

    /**
     * Prefix of every variable the cloud owns. The node removes all variables with this prefix
     * from its own environment before it starts a service, so nothing of the cloud's environment
     * leaks into a server.
     */
    public static final String PREFIX = "VECTORA_";

    private ServiceEnvironment() {
    }
}
