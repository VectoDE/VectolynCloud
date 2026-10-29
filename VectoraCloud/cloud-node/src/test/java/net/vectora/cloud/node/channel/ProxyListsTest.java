package net.vectora.cloud.node.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import net.vectora.protocol.ServiceId;
import org.junit.jupiter.api.Test;

class ProxyListsTest {

    private static final ServiceId PROXY = new ServiceId("proxy-1");
    private static final ServiceId OTHER = new ServiceId("proxy-2");
    private static final ServiceId LOBBY = new ServiceId("lobby-1");
    private static final ServiceId SURVIVAL = new ServiceId("survival-1");
    private static final ServiceId MINIGAME = new ServiceId("minigame-2");
    private static final ServiceId CREATIVE = new ServiceId("creative-1");
    private static final ServiceId LEGACY = new ServiceId("legacy-server-1");

    @Test
    void aBackendThatEndedWhileTheProxyWasAwayIsStaleOnReconnect() {
        ProxyLists lists = new ProxyLists();
        lists.sent(PROXY, LOBBY);
        lists.sent(PROXY, SURVIVAL);
        lists.sent(PROXY, MINIGAME);
        // survival-1 and minigame-2 ended during the gap; their UnregisterServer never arrived.
        assertEquals(List.of(MINIGAME, SURVIVAL), lists.stale(PROXY, Set.of(LOBBY, CREATIVE)));
        lists.withdrawn(PROXY, MINIGAME);
        lists.withdrawn(PROXY, SURVIVAL);
        assertEquals(List.of(), lists.stale(PROXY, Set.of(LOBBY)));
    }

    @Test
    void eachProxyHasItsOwnListAndAnEndedProxyStartsEmpty() {
        ProxyLists lists = new ProxyLists();
        lists.sent(PROXY, LOBBY);
        lists.sent(OTHER, LEGACY);
        assertEquals(List.of(), lists.stale(OTHER, Set.of(LEGACY)));
        assertEquals(List.of(LOBBY), lists.stale(PROXY, Set.of()));
        lists.proxyEnded(PROXY);
        assertEquals(List.of(), lists.stale(PROXY, Set.of()), "a new proxy process lists nothing yet");
        // Counter-check: the other proxy is untouched.
        assertEquals(List.of(LEGACY), lists.stale(OTHER, Set.of()));
    }
}
