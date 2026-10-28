package net.vectora.cloud.bridge.velocity;

import com.velocitypowered.api.proxy.ProxyServer;

/**
 * Connects a Velocity proxy to VectoraCloud.
 *
 * <p>Placeholder from the stage-2 scaffold. It only touches the platform API so the build
 * proves that this module compiles against it with release 21. The module structure and its
 * planned content are described in docs/cloud/architecture.md.
 */
public final class VelocityBridge {

    private VelocityBridge() {
    }

    /** Name and version of the platform, as reported to the cloud. */
    public static String describe(ProxyServer server) {
        return server.getVersion().getName() + " " + server.getVersion().getVersion();
    }
}
