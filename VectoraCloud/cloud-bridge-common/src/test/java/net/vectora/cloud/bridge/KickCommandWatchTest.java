package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.vectora.cloud.bridge.KickCommandWatch.Source;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.NetworkKick;
import org.junit.jupiter.api.Test;

class KickCommandWatchTest {

    private static final Map<String, List<String>> NO_ALIASES = Map.of();
    private final KickCommandWatch watch = new KickCommandWatch();

    @Test
    void onlyTheConsoleRconAndPlayersWithThePermissionAreEntitled() {
        assertEquals("vectora.moderation.kick", KickCommandWatch.PERMISSION);
        assertTrue(KickCommandWatch.entitled(Source.CONSOLE, false));
        assertTrue(KickCommandWatch.entitled(Source.REMOTE_CONSOLE, false));
        assertTrue(KickCommandWatch.entitled(Source.PLAYER, true));
        // Counter-checks: a player without the permission, command blocks and the rest.
        assertFalse(KickCommandWatch.entitled(Source.PLAYER, false));
        assertFalse(KickCommandWatch.entitled(Source.OTHER, true));
        assertFalse(KickCommandWatch.entitled(Source.OTHER, false));
    }

    @Test
    void kickCommandsWithAndWithoutReasonAndNamespaceAreRecognised() {
        assertEquals(Optional.of(List.of("Steve")), KickCommandWatch.targets("/kick Steve", NO_ALIASES));
        assertEquals(Optional.of(List.of("Steve")), KickCommandWatch.targets("kick Steve Spam im Chat", NO_ALIASES));
        assertEquals(Optional.of(List.of("Steve")), KickCommandWatch.targets("/minecraft:kick Steve", NO_ALIASES));
        assertEquals(Optional.of(List.of("Steve")), KickCommandWatch.targets("/KICK  Steve", NO_ALIASES));
        assertEquals(Optional.of(List.of("Steve")), KickCommandWatch.targets("/essentials:kick Steve x", NO_ALIASES));
        assertEquals(Optional.of(List.of("@a")), KickCommandWatch.targets("/kick @a", NO_ALIASES));
        // Counter-checks: no target, other commands, a command that only starts like kick.
        assertEquals(Optional.empty(), KickCommandWatch.targets("/kick", NO_ALIASES));
        assertEquals(Optional.empty(), KickCommandWatch.targets("/kickall", NO_ALIASES));
        assertEquals(Optional.empty(), KickCommandWatch.targets("/ban Steve", NO_ALIASES));
        assertEquals(Optional.empty(), KickCommandWatch.targets("", NO_ALIASES));
        assertEquals(Optional.empty(), KickCommandWatch.targets("say kick Steve", NO_ALIASES));
    }

    @Test
    void aliasesOfCommandsYmlAreFollowed() {
        Map<String, List<String>> aliases = Map.of(
                "k", List.of("kick $1 $2-"),
                "rauswurf", List.of("say bye", "minecraft:kick $$1"),
                "loop", List.of("loop $1"),
                "outer", List.of("k $1"));
        assertEquals(Optional.of(List.of("Steve")), KickCommandWatch.targets("/k Steve Spam", aliases));
        assertEquals(Optional.of(List.of("Steve")), KickCommandWatch.targets("/rauswurf Steve", aliases));
        assertEquals(Optional.of(List.of("Steve")), KickCommandWatch.targets("/outer Steve", aliases), "alias of an alias");
        // Counter-checks: an alias loop ends, an alias without a target is no kick.
        assertEquals(Optional.empty(), KickCommandWatch.targets("/loop Steve", aliases));
        assertEquals(Optional.empty(), KickCommandWatch.targets("/k", aliases));
    }

    @Test
    void theKickOfAnEntitledKickCommandInTheSameTickIsANetworkKick() {
        assertTrue(watch.command("/kick Steve Spam", Source.CONSOLE, false, NO_ALIASES));
        assertTrue(watch.kicked("steve"), "names without case");
        assertFalse(watch.kicked("Alex"), "another player kicked by a plugin in the same tick");
        watch.clear();
        assertFalse(watch.kicked("Steve"), "the next tick");

        assertTrue(watch.command("kick @a", Source.REMOTE_CONSOLE, false, NO_ALIASES));
        assertTrue(watch.kicked("Alex"), "a selector: anyone");
        // A new line ends the watch of the previous one.
        assertFalse(watch.command("/say hello", Source.CONSOLE, false, NO_ALIASES));
        assertFalse(watch.kicked("Alex"));
    }

    @Test
    void anUnentitledPlayerOrACommandBlockTriggersNoNetworkKick() {
        assertFalse(watch.command("/kick Steve", Source.PLAYER, false, NO_ALIASES));
        assertFalse(watch.kicked("Steve"));
        assertFalse(watch.command("kick Steve", Source.OTHER, true, NO_ALIASES), "a command block");
        assertFalse(watch.kicked("Steve"));
        // Counter-check: the same player with the permission.
        assertTrue(watch.command("/kick Steve", Source.PLAYER, true, NO_ALIASES));
        assertTrue(watch.kicked("Steve"));
    }

    @Test
    void anExpectedSelectorOrNameWatchesWithoutACommandLine() {
        watch.expect("@a");
        assertTrue(watch.kicked("Steve"), "a selector: anyone in this tick");
        watch.clear();
        assertFalse(watch.kicked("Steve"));
        watch.expect("Steve");
        assertTrue(watch.kicked("steve"));
        assertFalse(watch.kicked("Alex"));
    }

    @Test
    void theReasonIsPlainShortAndEmptyForTheVanillaText() {
        assertEquals("Spam im Chat", KickCommandWatch.reason(" §cSpam §lim Chat "));
        assertEquals("", KickCommandWatch.reason("Kicked by an operator."), "1.8.8 vanilla text");
        assertEquals("", KickCommandWatch.reason("§fKicked by an operator!"));
        assertEquals(NetworkKick.MAX_REASON_LENGTH, KickCommandWatch.reason("x".repeat(1000)).length());
        // A surrogate pair at the limit is not cut in half.
        String cut = KickCommandWatch.reason("x".repeat(NetworkKick.MAX_REASON_LENGTH - 1) + "😀");
        assertEquals(NetworkKick.MAX_REASON_LENGTH - 1, cut.length());
        UUID steve = UUID.randomUUID();
        assertEquals(new NetworkKick(steve, new ServiceId("lobby-1"), "Spam"),
                KickCommandWatch.message(steve, new ServiceId("lobby-1"), "§cSpam"));
    }
}
