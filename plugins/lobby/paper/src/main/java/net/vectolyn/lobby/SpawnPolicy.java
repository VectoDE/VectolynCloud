package net.vectolyn.lobby;

/** Decides which creature spawns the lobby allows. Free of Paper types so it runs on any JDK. */
final class SpawnPolicy {
    private static final String PLUGIN_CREATED = "CUSTOM";

    private SpawnPolicy() {}

    /** Only plugins (NPCs and the like) may create creatures; everything else is blocked. */
    static boolean blocks(String spawnReasonName) {
        return !PLUGIN_CREATED.equals(spawnReasonName);
    }
}
