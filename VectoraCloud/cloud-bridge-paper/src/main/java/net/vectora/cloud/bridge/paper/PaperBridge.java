package net.vectora.cloud.bridge.paper;

import org.bukkit.Server;

/**
 * Connects a Paper server to VectoraCloud.
 *
 * <p>Placeholder from the stage-2 scaffold. It only touches the platform API so the build
 * proves that this module compiles against it with release 21. The module structure and its
 * planned content are described in docs/cloud/architecture.md.
 */
public final class PaperBridge {

    private PaperBridge() {
    }

    /** Name and version of the platform, as reported to the cloud. */
    public static String describe(Server server) {
        return server.getName() + " " + server.getVersion();
    }
}
