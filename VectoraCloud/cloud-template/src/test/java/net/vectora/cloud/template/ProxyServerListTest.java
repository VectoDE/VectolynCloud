package net.vectora.cloud.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProxyServerListTest {

    private static final List<ProxyBackend> ALL = List.of(
            new ProxyBackend("lobby", "127.0.0.1", 25566, true),
            new ProxyBackend("survival", "127.0.0.1", 25567, false),
            new ProxyBackend("mod", "127.0.0.1", 25568, true),
            new ProxyBackend("creative", "10.0.0.5", 25569, true));

    private static final Map<String, List<String>> FORCED = Map.of(
            "creative.example.net", List.of("creative"),
            "survival.example.net", List.of("survival"),
            "play.example.net", List.of("survival", "lobby"));

    @Test
    void formatsTheThreeVelocityValues() {
        ProxyServerList list = ProxyServerList.of(ALL, List.of("lobby", "survival", "mod"), FORCED);

        assertEquals("""
                creative = "10.0.0.5:25569"
                lobby = "127.0.0.1:25566"
                mod = "127.0.0.1:25568\"""", list.servers());
        assertEquals("\"lobby\", \"mod\"", list.tryOrder());
        assertEquals("""
                "creative.example.net" = [ "creative" ]
                "play.example.net" = [ "lobby" ]""", list.forcedHosts());
    }

    @Test
    void unwantedServersAppearNowhere() {
        ProxyServerList list = ProxyServerList.of(ALL, List.of("survival", "lobby"), FORCED);
        for (String value : List.of(list.servers(), list.tryOrder(), list.forcedHosts())) {
            assertFalse(value.contains("survival"), value);
        }
    }

    @Test
    void orderDoesNotDependOnTheInputOrder() {
        List<ProxyBackend> shuffled = new ArrayList<>(ALL);
        Collections.reverse(shuffled);
        Map<String, List<String>> forced = new LinkedHashMap<>();
        new ArrayList<>(FORCED.keySet()).reversed().forEach(host -> forced.put(host, FORCED.get(host)));

        ProxyServerList a = ProxyServerList.of(ALL, List.of("lobby", "mod"), FORCED);
        ProxyServerList b = ProxyServerList.of(shuffled, List.of("lobby", "mod"), forced);

        assertEquals(a.servers(), b.servers());
        assertEquals(a.forcedHosts(), b.forcedHosts());
    }

    @Test
    void tryOrderKeepsTheGivenOrderWithoutDuplicates() {
        assertEquals("\"mod\", \"lobby\"",
                ProxyServerList.of(ALL, List.of("mod", "lobby", "mod"), Map.of()).tryOrder());
    }

    @Test
    void emptyValuesWithoutForcedHostsOrTry() {
        ProxyServerList list = ProxyServerList.of(ALL, List.of(), Map.of());
        assertEquals("", list.tryOrder());
        assertEquals("", list.forcedHosts());
    }

    @Test
    void placeholderValuesUseTheTemplateNames() {
        PlaceholderValues values = ProxyServerList.of(ALL, List.of("lobby"), Map.of()).placeholderValues();
        assertEquals(Optional.of("\"lobby\""), values.find("VECTORA_PROXY_TRY"));
        assertEquals(Optional.of(""), values.find("VECTORA_PROXY_FORCED_HOSTS"));
        assertEquals(Optional.of("creative = \"10.0.0.5:25569\"\nlobby = \"127.0.0.1:25566\"\nmod = \"127.0.0.1:25568\""),
                values.find("VECTORA_PROXY_SERVERS"));
        assertEquals(Optional.empty(), values.find("VECTORA_SERVER_PORT"));
    }

    @Test
    void ipv6HostsAreBracketed() {
        ProxyServerList list = ProxyServerList.of(List.of(new ProxyBackend("lobby", "::1", 25566, true)), List.of(), Map.of());
        assertEquals("lobby = \"[::1]:25566\"", list.servers());
    }

    @Test
    void invalidInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProxyBackend("lob by", "127.0.0.1", 1, true));
        assertThrows(IllegalArgumentException.class, () -> new ProxyBackend("a\"=1", "127.0.0.1", 1, true));
        assertThrows(IllegalArgumentException.class, () -> new ProxyBackend("", "127.0.0.1", 1, true));
        assertThrows(IllegalArgumentException.class, () -> new ProxyBackend("lobby", "127.0.0.1\"", 1, true));
        assertThrows(IllegalArgumentException.class, () -> new ProxyBackend("lobby", "", 1, true));
        assertThrows(IllegalArgumentException.class, () -> new ProxyBackend("lobby", "127.0.0.1", 0, true));
        assertThrows(IllegalArgumentException.class, () -> new ProxyBackend("lobby", "127.0.0.1", 65536, true));
        assertThrows(IllegalArgumentException.class, () -> ProxyServerList.of(
                List.of(new ProxyBackend("lobby", "a", 1, true), new ProxyBackend("Lobby", "b", 2, false)), List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ProxyServerList.of(ALL, List.of("nether"), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ProxyServerList.of(ALL, List.of(), Map.of("x.example.net", List.of("nether"))));
        assertThrows(IllegalArgumentException.class,
                () -> ProxyServerList.of(ALL, List.of(), Map.of("bad host\"", List.of("lobby"))));
    }
}
