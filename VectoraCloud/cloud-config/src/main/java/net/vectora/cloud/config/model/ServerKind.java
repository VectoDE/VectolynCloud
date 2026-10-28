package net.vectora.cloud.config.model;

/**
 * The eight server kinds of the network. Each has a template folder in the cloud home
 * ({@code Server-Network/<folder>/template/}) and a "wanted" switch in {@code cloud.yml}.
 */
public enum ServerKind {
    /** Velocity proxy with Geyser, Floodgate and Via*. */
    PROXY("proxy", "Proxy"),
    /** Hub lobby (Paper). */
    LOBBY("lobby", "Lobby-Server"),
    /** Survival (Paper). */
    SURVIVAL("survival", "Survival-Server"),
    /** Creative with plots and freebuild (Paper). */
    CREATIVE("creative", "Creative-Server"),
    /** Mod-Server (Fabric). */
    MOD("mod", "Mod-Server"),
    /** The only End world of the network (Paper). */
    END("end", "End-Server"),
    /** MariaDB, managed by the cloud. */
    DATABASE("database", "Database"),
    /** Generic minigame template (Paper), until stage 6 brings the individual games. */
    MINIGAME("minigame", "Minigame-Server");

    private final String key;
    private final String templateFolder;

    ServerKind(String key, String templateFolder) {
        this.key = key;
        this.templateFolder = templateFolder;
    }

    /** Key in {@code cloud.yml} under {@code servers}. */
    public String key() {
        return key;
    }

    /** Folder name in the cloud home that holds {@code template/}. */
    public String templateFolder() {
        return templateFolder;
    }
}
