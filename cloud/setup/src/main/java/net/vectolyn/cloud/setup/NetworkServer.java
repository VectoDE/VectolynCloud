package net.vectolyn.cloud.setup;

import java.util.Optional;
import net.vectolyn.cloud.config.model.CombatSystem;
import net.vectolyn.cloud.config.model.MemorySize;
import net.vectolyn.cloud.config.model.ServerKind;
import net.vectolyn.cloud.config.model.ServerSoftware;
import net.vectolyn.cloud.console.Text;

/**
 * The servers the assistant offers, in the order it asks for them. Each has a "wanted" switch and a
 * default group that the assistant creates when the group is missing.
 *
 * <p>Every server except the legacy proxy belongs to a {@link ServerKind} and uses its switch under
 * {@code servers:} in {@code cloud.yml}. The legacy proxy's switch is {@code legacy-proxy.enabled},
 * which also carries its port.
 */
public enum NetworkServer {

    /** Velocity, the entry point for Java and Bedrock players. */
    PROXY(ServerKind.PROXY, "proxy", "Proxy", ServerSoftware.VELOCITY, "512M",
            Text.of("Proxy (Velocity, entry point for Java and Bedrock)", "Proxy (Velocity, Eingang für Java und Bedrock)"),
            Text.of("Proxy", "Proxy")),
    /** Hub lobby. */
    LOBBY(ServerKind.LOBBY, "lobby", "Lobby-Server", ServerSoftware.PAPER, "1G",
            Text.of("Lobby", "Lobby"),
            Text.of("Lobby", "Lobby")),
    /** Survival. */
    SURVIVAL(ServerKind.SURVIVAL, "survival", "Survival-Server", ServerSoftware.PAPER, "3G",
            Text.of("Survival", "Survival"),
            Text.of("Survival", "Survival")),
    /** Creative. */
    CREATIVE(ServerKind.CREATIVE, "creative", "Creative-Server", ServerSoftware.PAPER, "2G",
            Text.of("Creative", "Creative"),
            Text.of("Creative", "Creative")),
    /** Fabric mod server. */
    MOD(ServerKind.MOD, "mod", "Mod-Server", ServerSoftware.FABRIC, "3G",
            Text.of("Mod server (Fabric)", "Mod-Server (Fabric)"),
            Text.of("Mod server", "Mod-Server")),
    /** The End world. */
    END(ServerKind.END, "end", "End-Server", ServerSoftware.PAPER, "1G",
            Text.of("End", "End"),
            Text.of("End", "End")),
    /** Minigame groups. */
    MINIGAME(ServerKind.MINIGAME, "minigame", "Minigame-Server", ServerSoftware.PAPER, "1536M",
            Text.of("Minigames", "Minigames"),
            Text.of("Minigames", "Minigames")),
    /** Second Velocity in front of the 1.8.8 servers. */
    LEGACY_PROXY(null, "legacy-proxy", "Legacy-Proxy", ServerSoftware.VELOCITY, "512M",
            Text.of("Legacy proxy (Velocity for 1.8.8, own port)", "Legacy-Proxy (Velocity für 1.8.8, eigener Port)"),
            Text.of("Legacy proxy", "Legacy-Proxy")),
    /** PandaSpigot 1.8.8 behind the legacy proxy. */
    LEGACY_SERVER(ServerKind.LEGACY_SERVER, "legacy-server", "Legacy-Server", ServerSoftware.PANDASPIGOT, "1G",
            Text.of("1.8.8 server (PandaSpigot, behind the legacy proxy)", "1.8.8-Server (PandaSpigot, hinter dem Legacy-Proxy)"),
            Text.of("1.8.8 server", "1.8.8-Server"));

    /** Minecraft version of every default group except the 1.8.8 server. */
    public static final String MINECRAFT_VERSION = "26.3";
    /** Minecraft version of the 1.8.8 server. */
    public static final String LEGACY_MINECRAFT_VERSION = "1.8.8";

    private final ServerKind kind;
    private final String key;
    private final String template;
    private final ServerSoftware software;
    private final MemorySize defaultHeap;
    private final Text label;
    private final Text shortName;

    NetworkServer(ServerKind kind, String key, String template, ServerSoftware software, String defaultHeap, Text label,
                  Text shortName) {
        this.kind = kind;
        this.key = key;
        this.template = template;
        this.software = software;
        this.defaultHeap = MemorySize.parse(defaultHeap);
        this.label = label;
        this.shortName = shortName;
    }

    /** The server kind whose switch it uses; empty for the legacy proxy. */
    public Optional<ServerKind> kind() {
        return Optional.ofNullable(kind);
    }

    /** Key in the setup file under {@code servers:}, also the name of the default group and task. */
    public String key() {
        return key;
    }

    /** Template folder in the cloud home. */
    public String template() {
        return template;
    }

    /** Software of the default group. */
    public ServerSoftware software() {
        return software;
    }

    /** Minecraft version of the default group. */
    public String minecraftVersion() {
        return software == ServerSoftware.PANDASPIGOT ? LEGACY_MINECRAFT_VERSION : MINECRAFT_VERSION;
    }

    /** Combat mechanics of the default group: 1.8 style on the 1.8.8 server, modern elsewhere. */
    public CombatSystem combat() {
        return software == ServerSoftware.PANDASPIGOT ? CombatSystem.LEGACY : CombatSystem.MODERN;
    }

    /**
     * Services the assistant suggests for the default task, as {@code static/min/max}: the proxies
     * and Survival, Creative, Mod and End one static service that is never scaled; the lobby one
     * static and dynamic overflow up to 4; the 1.8.8 server one static and overflow up to 3;
     * minigames only dynamic, one ready and up to 8.
     */
    public ServiceCounts defaultCounts() {
        return switch (this) {
            case LOBBY -> new ServiceCounts(1, 1, 4);
            case LEGACY_SERVER -> new ServiceCounts(1, 1, 3);
            case MINIGAME -> new ServiceCounts(0, 1, 8);
            case PROXY, LEGACY_PROXY, SURVIVAL, CREATIVE, MOD, END -> new ServiceCounts(1, 1, 1);
        };
    }

    /** Whether the server is a Velocity proxy: one per node, so its counts are fixed at 1/1/1. */
    public boolean isProxy() {
        return software == ServerSoftware.VELOCITY;
    }

    /** Suggested maximum heap of one service. */
    public MemorySize defaultHeap() {
        return defaultHeap;
    }

    /** Name in the assistant. */
    public Text label() {
        return label;
    }

    /** Short name for the summary. */
    public Text shortName() {
        return shortName;
    }

    /** The server whose template is {@code template}, if any. */
    public static Optional<NetworkServer> byTemplate(String template) {
        for (NetworkServer server : values()) {
            if (server.template.equals(template)) {
                return Optional.of(server);
            }
        }
        return Optional.empty();
    }
}
