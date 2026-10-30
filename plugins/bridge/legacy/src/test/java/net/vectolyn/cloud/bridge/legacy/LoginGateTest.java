package net.vectolyn.cloud.bridge.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.destroystokyo.paper.event.player.PlayerHandshakeEvent;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.UUID;
import java.util.logging.Logger;
import net.vectolyn.cloud.bridge.forwarding.AdmissionTickets;
import net.vectolyn.cloud.bridge.forwarding.BungeeGuardCheck;
import net.vectolyn.cloud.bridge.forwarding.ForwardingSecret;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerLoginEvent;
import org.junit.jupiter.api.Test;

/** The second gate: a login needs a ticket from an accepted handshake. */
class LoginGateTest {

    private static final String SECRET = "legacy-forwarding-secret.0123456789";
    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private final Logger log = Logger.getAnonymousLogger();
    private final HandshakeGuard guard =
            new HandshakeGuard(BungeeGuardCheck.with(ForwardingSecret.of(SECRET)), new AdmissionTickets(), log);

    @Test
    void aLoginWithoutAnAcceptedHandshakeIsRefused() throws Exception {
        PlayerLoginEvent login = login(PLAYER);
        guard.onLogin(login);
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, login.getResult());
        assertEquals(HandshakeGuard.FAIL_MESSAGE, login.getKickMessage());
    }

    @Test
    void aLoginAfterAnAcceptedHandshakeIsAllowedOnce() throws Exception {
        log.setUseParentHandlers(false);
        guard.onHandshake(new PlayerHandshakeEvent("127.0.0.1\u0000203.0.113.7\u0000069a79f444e94726a5befca90e38aaf5"
                + "\u0000[{\"name\":\"bungeeguard-token\",\"value\":\"" + SECRET + "\"}]", "198.51.100.20", false));
        PlayerLoginEvent first = login(PLAYER);
        guard.onLogin(first);
        assertEquals(PlayerLoginEvent.Result.ALLOWED, first.getResult());

        PlayerLoginEvent again = login(PLAYER);
        guard.onLogin(again);
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, again.getResult(), "the ticket is used up");
    }

    @Test
    void anotherPluginCannotAllowARefusedLogin() throws Exception {
        PlayerLoginEvent login = login(PLAYER);
        guard.onLogin(login);
        login.allow(); // a plugin between LOWEST and MONITOR
        guard.enforceLogin(login);
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, login.getResult());
        assertEquals(HandshakeGuard.FAIL_MESSAGE, login.getKickMessage());

        // Counter-check: an admitted login is left as the other plugins decided.
        log.setUseParentHandlers(false);
        guard.onHandshake(new PlayerHandshakeEvent("127.0.0.1\u0000203.0.113.7\u0000069a79f444e94726a5befca90e38aaf5"
                + "\u0000[{\"name\":\"bungeeguard-token\",\"value\":\"" + SECRET + "\"}]", "198.51.100.20", false));
        PlayerLoginEvent admitted = login(PLAYER);
        guard.onLogin(admitted);
        admitted.disallow(PlayerLoginEvent.Result.KICK_WHITELIST, "whitelist");
        guard.enforceLogin(admitted);
        assertEquals(PlayerLoginEvent.Result.KICK_WHITELIST, admitted.getResult());
        // The next event of a refused player starts clean as well.
        PlayerLoginEvent unrelated = login(PLAYER);
        guard.enforceLogin(unrelated);
        assertEquals(PlayerLoginEvent.Result.ALLOWED, unrelated.getResult());
    }

    @Test
    void aRefusedHandshakeLeavesNoTicket() throws Exception {
        log.setUseParentHandlers(false);
        guard.onHandshake(new PlayerHandshakeEvent("127.0.0.1\u0000203.0.113.7\u0000069a79f444e94726a5befca90e38aaf5"
                + "\u0000[{\"name\":\"bungeeguard-token\",\"value\":\"wrong-wrong-wrong-wrong\"}]", "198.51.100.20", false));
        PlayerLoginEvent login = login(PLAYER);
        guard.onLogin(login);
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, login.getResult());
    }

    private static PlayerLoginEvent login(UUID uuid) throws Exception {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "Notch";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Player[Notch]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new PlayerLoginEvent(player, "127.0.0.1", InetAddress.getLoopbackAddress());
    }
}
