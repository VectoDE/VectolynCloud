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

    private static final KickCommandWatch.Selectors NONE = KickCommandWatch.Selectors.NONE;

    @Test
    void theKickOfAnEntitledKickCommandInTheSameTickIsANetworkKick() {
        assertTrue(watch.command("/kick Steve Spam", Source.CONSOLE, false, NO_ALIASES, NONE));
        assertTrue(watch.kicked("steve", "Spam"), "names without case");
        assertFalse(watch.kicked("Alex", "Spam"), "another player kicked by a plugin in the same tick");
        watch.clear();
        assertFalse(watch.kicked("Steve", "Spam"), "the next tick");

        // A new line ends the watch of the previous one.
        assertTrue(watch.command("/kick Steve", Source.CONSOLE, false, NO_ALIASES, NONE));
        assertFalse(watch.command("/say hello", Source.CONSOLE, false, NO_ALIASES, NONE));
        assertFalse(watch.kicked("Steve", ""));
    }

    @Test
    void aResolvedSelectorMarksOnlyThePlayersItMatches() {
        KickCommandWatch.Selectors paper = selector -> selector.equals("@a[distance=..5]")
                ? Optional.of(List.of("Steve", "Alex")) : Optional.empty();
        assertTrue(watch.command("kick @a[distance=..5] weg", Source.REMOTE_CONSOLE, false, NO_ALIASES, paper));
        assertTrue(watch.kicked("alex", "anything"), "a matched player, whatever the reason");
        assertTrue(watch.kicked("Steve", "weg"));
        assertFalse(watch.kicked("Notch", "weg"), "a player the selector did not match, same reason, same tick");
    }

    @Test
    void anUnresolvedSelectorMarksOnlyKicksWithTheCommandsReason() {
        // 1.8.8 cannot resolve selectors: only kicks in this tick with the line's reason count.
        assertTrue(watch.command("kick @a Wartung heute", Source.CONSOLE, false, NO_ALIASES, NONE));
        assertTrue(watch.kicked("Alex", "§cWartung heute"), "the command's reason, colour codes aside");
        assertFalse(watch.kicked("Alex", "Flying is not enabled on this server"),
                "another kick in the same tick, for flying");
        watch.clear();
        // Without a reason vanilla kicks with its own text, which the watch reads as empty.
        watch.expect("@p", "Kicked by an operator.", NONE);
        assertTrue(watch.kicked("Steve", "Kicked by an operator."));
        assertFalse(watch.kicked("Steve", "You logged in from another location"));
    }

    @Test
    void anUnentitledPlayerOrACommandBlockTriggersNoNetworkKick() {
        assertFalse(watch.command("/kick Steve", Source.PLAYER, false, NO_ALIASES, NONE));
        assertFalse(watch.kicked("Steve", ""));
        assertFalse(watch.command("kick Steve", Source.OTHER, true, NO_ALIASES, NONE), "a command block");
        assertFalse(watch.kicked("Steve", ""));
        assertFalse(watch.command("kick @a", Source.PLAYER, false, NO_ALIASES, NONE));
        assertFalse(watch.kicked("Steve", ""), "an unentitled selector");
        // Counter-check: the same player with the permission.
        assertTrue(watch.command("/kick Steve", Source.PLAYER, true, NO_ALIASES, NONE));
        assertTrue(watch.kicked("Steve", ""));
    }

    @Test
    void anExpectedNameWatchesWithoutACommandLine() {
        watch.expect("Steve", "", NONE);
        assertTrue(watch.kicked("steve", "anything"));
        assertFalse(watch.kicked("Alex", ""));
        watch.clear();
        assertFalse(watch.kicked("Steve", ""));
    }

    @Test
    void anAliasArgumentTooLongForANumberIsNoArgument() {
        Map<String, List<String>> aliases = Map.of("k", List.of("kick $99999999999999999999 $1"));
        assertEquals(Optional.of(List.of("Steve")), KickCommandWatch.targets("/k Steve", aliases));
    }

    @Test
    void theReasonOfAKickCommandIsTheRestOfTheLine() {
        assertEquals("", KickCommandWatch.reasonOf(new String[] {"Bob"}));
        assertEquals("zu laut", KickCommandWatch.reasonOf(new String[] {"Bob", "zu", "laut"}));
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
