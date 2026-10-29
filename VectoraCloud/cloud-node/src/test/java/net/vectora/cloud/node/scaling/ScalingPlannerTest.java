package net.vectora.cloud.node.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.CombatSystem;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.HeapConfig;
import net.vectora.cloud.config.model.JavaSelection;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.NodesConfig;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.config.model.WantedServers;
import net.vectora.cloud.node.scaling.ScalingPlanner.Decision;
import net.vectora.cloud.node.scaling.ScalingPlanner.Hold;
import net.vectora.cloud.node.scaling.ScalingPlanner.Held;
import net.vectora.cloud.node.scaling.ScalingPlanner.Memory;
import net.vectora.cloud.node.scaling.ScalingPlanner.Reason;
import net.vectora.cloud.node.scaling.ScalingPlanner.Start;
import net.vectora.cloud.node.scaling.ScalingPlanner.Stop;
import net.vectora.cloud.node.service.ServiceInfo;
import net.vectora.cloud.node.service.ServiceState;
import net.vectora.protocol.ServiceId;
import org.junit.jupiter.api.Test;

/** The pure scaling decisions: every rule with its boundary, and the counter-check that shows the rule is what decides. */
class ScalingPlannerTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final long MB = 1024L * 1024L;
    /** Plenty: 64 GB, 1 GB used. */
    private static final Optional<Memory> ROOMY = Optional.of(new Memory(1024 * MB, 64L * 1024 * MB));

    // ---------------------------------------------------------------- min-services

    @Test
    void aDynamicTaskIsFilledUpToMinServicesAndNotBeyond() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 2, 4).running("minigame", 1, TaskMode.DYNAMIC, 10);
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.MIN_SERVICES)), s.plan());

        // Counter-check: with two running, the minimum is met.
        s.running("minigame", 2, TaskMode.DYNAMIC, 10);
        assertEquals(List.of(), s.plan());
    }

    @Test
    void aCrashedDynamicServiceWaitingForItsRestartCountsAndAStoppingOneDoesNot() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 4)
                .service("minigame", 1, TaskMode.DYNAMIC, ServiceState.CRASHED);
        assertEquals(List.of(), s.plan(), "the service core restarts it");

        Scenario stopping = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 4)
                .service("minigame", 1, TaskMode.DYNAMIC, ServiceState.STOPPING);
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.MIN_SERVICES)), stopping.plan());
    }

    @Test
    void aStaticServiceStoppedByHandOrGivenUpAfterCrashesStaysDownButAReleasedOneComesBack() {
        Scenario s = new Scenario().task("lobby", TaskMode.STATIC, 1, 1)
                .service("lobby", 1, TaskMode.STATIC, ServiceState.STOPPED);
        assertEquals(List.of(), s.plan(), "stopped by hand");

        Scenario crashed = new Scenario().task("lobby", TaskMode.STATIC, 1, 1)
                .service("lobby", 1, TaskMode.STATIC, ServiceState.CRASHED);
        assertEquals(List.of(), crashed.plan(), "given up after its crashes");

        // Switched off and switched on again while it still stops: wait for it, no lobby-2 beside it.
        Scenario stopping = new Scenario().task("lobby", TaskMode.STATIC, 1, 1)
                .service("lobby", 1, TaskMode.STATIC, ServiceState.STOPPING).released("lobby-1");
        assertEquals(List.of(), stopping.plan());

        // Counter-check: stopped because its group was switched off, it comes back at its own index.
        s.released("lobby-1");
        assertEquals(List.of(new Start("lobby", OptionalInt.of(1), Reason.MIN_SERVICES)), s.plan());
    }

    @Test
    void aNewStaticSlotTakesTheLowestIndexOfTheGroupThatHasNoService() {
        Scenario s = new Scenario().task("survival", TaskMode.STATIC, 3, 3)
                .running("survival", 1, TaskMode.STATIC, 0)
                .service("survival", 2, TaskMode.STATIC, ServiceState.STOPPED);
        assertEquals(List.of(new Start("survival", OptionalInt.of(3), Reason.MIN_SERVICES)), s.plan(),
                "survival-2 was stopped by hand and keeps its folder untouched");
    }

    @Test
    void aMixedTaskFillsItsStaticServicesFirstAndTheRestOfMinServicesDynamically() {
        Scenario s = new Scenario().task("lobby", "lobby", 1, 2, 4);
        assertEquals(List.of(new Start("lobby", OptionalInt.of(1), Reason.MIN_SERVICES)), s.plan(), "static first");
        s.running("lobby", 1, TaskMode.STATIC, 0);
        assertEquals(List.of(new Start("lobby", OptionalInt.empty(), Reason.MIN_SERVICES)), s.plan(),
                "the static quota is full, the second service is dynamic");
        // Counter-check: with two static services both are static slots.
        Scenario both = new Scenario().task("lobby", "lobby", 2, 2, 4).running("lobby", 1, TaskMode.STATIC, 0);
        assertEquals(List.of(new Start("lobby", OptionalInt.of(2), Reason.MIN_SERVICES)), both.plan());
    }

    @Test
    void anUnwantedGroupATaskOfAnotherNodeAndAMissingGroupAreNeverScaled() {
        Scenario wanted = new Scenario().task("lobby", TaskMode.STATIC, 1, 1);
        assertEquals(List.of(new Start("lobby", OptionalInt.of(1), Reason.MIN_SERVICES)), wanted.plan());

        Scenario off = new Scenario().task("lobby", TaskMode.STATIC, 1, 1).switchOff(ServerKind.LOBBY);
        assertEquals(List.of(), off.plan(), "servers.lobby: false");

        Scenario ownSwitch = new Scenario().task("lobby", TaskMode.STATIC, 1, 1);
        ownSwitch.groups.put("lobby", ownSwitch.groups.get("lobby").withWanted(false));
        assertEquals(List.of(), ownSwitch.plan(), "wanted: false in the group file");

        Scenario elsewhere = new Scenario().task("lobby", TaskMode.STATIC, 1, 1);
        TaskConfig lobbyTask = elsewhere.tasks.get("lobby");
        elsewhere.tasks.put("lobby", lobbyTask.withNodes(List.of("node-2")));
        assertEquals(List.of(), elsewhere.plan(), "runs on node-2");

        Scenario noGroup = new Scenario().task("lobby", TaskMode.STATIC, 1, 1);
        noGroup.groups.remove("lobby");
        assertEquals(List.of(), noGroup.plan());
    }

    @Test
    void minServicesIgnoresTheMemoryBudgetLikeTheStartOfTheNode() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 4).memory(Optional.empty());
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.MIN_SERVICES)), s.plan());
    }

    @Test
    void minServicesNeverGoesBeyondWhatMaxServicesLeavesFree() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 2, 2)
                .running("minigame", 1, TaskMode.DYNAMIC, 0)
                .service("minigame", 2, TaskMode.DYNAMIC, ServiceState.STOPPING);
        assertEquals(List.of(), s.plan(), "minigame-2 still counts for max-services until it has ended");
    }

    // ---------------------------------------------------------------- scale up

    @Test
    void anotherServiceStartsOnceEveryRunningOneReachesTheThreshold() {
        // 50 players per service, scale at 80 %: 40 players is the threshold.
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3).running("minigame", 1, TaskMode.DYNAMIC, 39);
        assertEquals(List.of(), s.plan(), "39 is below 80 % of 50");
        s.running("minigame", 1, TaskMode.DYNAMIC, 40);
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.PLAYERS)), s.plan());

        // One of two with room left: no new service.
        s.running("minigame", 2, TaskMode.DYNAMIC, 10);
        assertEquals(List.of(), s.plan());
        s.running("minigame", 2, TaskMode.DYNAMIC, 45);
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.PLAYERS)), s.plan());
    }

    @Test
    void unknownPlayerCountsAStartingServiceAndMaxServicesHoldTheScaleUpBack() {
        Scenario unknown = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3)
                .running("minigame", 1, TaskMode.DYNAMIC, 50).runningUnknown("minigame", 2, TaskMode.DYNAMIC);
        assertEquals(List.of(), unknown.plan(), "no report from minigame-2 yet");

        Scenario starting = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3)
                .running("minigame", 1, TaskMode.DYNAMIC, 50).service("minigame", 2, TaskMode.DYNAMIC, ServiceState.STARTING);
        assertEquals(List.of(), starting.plan(), "wait until minigame-2 can take players");

        Scenario full = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 2)
                .running("minigame", 1, TaskMode.DYNAMIC, 50).running("minigame", 2, TaskMode.DYNAMIC, 50);
        assertEquals(List.of(), full.plan(), "max-services reached");

        // Counter-check: one more allowed.
        full.task("minigame", TaskMode.DYNAMIC, 1, 3);
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.PLAYERS)), full.plan());
    }

    @Test
    void staticTasksAndProxiesNeverScaleUp() {
        // static = max: a full static task has no room for a dynamic service.
        Scenario lobby = new Scenario().task("lobby", "lobby", 1, 1, 1).running("lobby", 1, TaskMode.STATIC, 50);
        assertEquals(List.of(), lobby.plan());

        // Counter-check, mixed task (static 1, max 4): under load the overflow starts dynamically.
        Scenario mixed = new Scenario().task("lobby", "lobby", 1, 1, 4).running("lobby", 1, TaskMode.STATIC, 50);
        assertEquals(List.of(new Start("lobby", OptionalInt.empty(), Reason.PLAYERS)), mixed.plan());

        Scenario proxy = new Scenario().group("proxy", ServerKind.PROXY.templateFolder(), ServerSoftware.VELOCITY)
                .task("proxy", "proxy", TaskMode.DYNAMIC, 1, 2).running("proxy", 1, TaskMode.DYNAMIC, 500);
        assertEquals(List.of(), proxy.plan(), "one proxy per node");
    }

    @Test
    void theCooldownSpacesTheDecisionsOfATask() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3).running("minigame", 1, TaskMode.DYNAMIC, 40);
        s.lastAction.put("minigame", NOW.minusSeconds(59));
        assertEquals(List.of(), s.plan(), "59 s after the last decision, cooldown 60 s");
        s.lastAction.put("minigame", NOW.minusSeconds(60));
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.PLAYERS)), s.plan());
    }

    @Test
    void theMemoryBudgetOfTheNodeHoldsAScaleUpBack() {
        // minigame heap max 1G; lobby-1 (1G) and minigame-1 (1G) already run.
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3).task("lobby", TaskMode.STATIC, 1, 1)
                .running("lobby", 1, TaskMode.STATIC, 0).running("minigame", 1, TaskMode.DYNAMIC, 40);

        s.memory(Optional.empty());
        assertEquals(List.of(new Held("minigame", Hold.MEMORY_UNKNOWN)), s.plan());

        // 3 GB of heaps fit into 4 GB, but only 1 GB minus 1 MB is free right now.
        s.memory(Optional.of(new Memory(3072 * MB + MB, 4096 * MB)));
        assertEquals(List.of(new Held("minigame", Hold.MEMORY_FREE)), s.plan());

        // Plenty free, but the heaps of all services would exceed the machine: 3 GB > 3 GB - 1 MB.
        s.memory(Optional.of(new Memory(0, 3072 * MB - MB)));
        assertEquals(List.of(new Held("minigame", Hold.MEMORY_BUDGET)), s.plan());

        // Counter-check: exactly enough on both counts.
        s.memory(Optional.of(new Memory(3072 * MB, 4096 * MB)));
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.PLAYERS)), s.plan());
        s.memory(Optional.of(new Memory(0, 3072 * MB)));
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.PLAYERS)), s.plan());
    }

    @Test
    void aPausedTaskStartsNothingButMayStillScaleDown() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3).running("minigame", 1, TaskMode.DYNAMIC, 40);
        s.pausedUntil.put("minigame", NOW.plusSeconds(1));
        assertEquals(List.of(new Held("minigame", Hold.PAUSED)), s.plan());
        s.pausedUntil.put("minigame", NOW);
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.PLAYERS)), s.plan(), "pause is over");

        Scenario fill = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3);
        fill.pausedUntil.put("minigame", NOW.plusSeconds(1));
        assertEquals(List.of(new Held("minigame", Hold.PAUSED)), fill.plan());

        Scenario down = surplus();
        down.pausedUntil.put("minigame", NOW.plusSeconds(600));
        assertEquals(List.of(new Stop("minigame", new ServiceId("minigame-2"), Reason.EMPTY)), down.plan());
    }

    // ---------------------------------------------------------------- scale down

    /** minigame-1 with 10 players, minigame-2 empty for exactly scale-down-after (5 minutes). */
    private static Scenario surplus() {
        return new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3)
                .running("minigame", 1, TaskMode.DYNAMIC, 10).running("minigame", 2, TaskMode.DYNAMIC, 0)
                .emptySince("minigame-2", NOW.minus(Duration.ofMinutes(5)));
    }

    @Test
    void aSurplusServiceStopsOnceItHasBeenEmptyForScaleDownAfter() {
        assertEquals(List.of(new Stop("minigame", new ServiceId("minigame-2"), Reason.EMPTY)), surplus().plan());

        Scenario early = surplus().emptySince("minigame-2", NOW.minus(Duration.ofMinutes(5)).plusSeconds(1));
        assertEquals(List.of(), early.plan(), "empty for 4:59");
    }

    @Test
    void aServiceIsNeverStoppedWhileItHasPlayersOrItsCountIsUnknown() {
        Scenario players = surplus().running("minigame", 2, TaskMode.DYNAMIC, 1);
        assertEquals(List.of(), players.plan());

        Scenario unknown = surplus().runningUnknown("minigame", 2, TaskMode.DYNAMIC);
        assertEquals(List.of(), unknown.plan(), "no report: it may have players");
    }

    @Test
    void hysteresisKeepsTheLastServiceWithRoom() {
        // minigame-1 at the threshold: stopping minigame-2 would make the next tick start one again.
        Scenario s = surplus().running("minigame", 1, TaskMode.DYNAMIC, 40);
        assertEquals(List.of(), s.plan());

        // Counter-check: one player less on minigame-1 and minigame-2 may go.
        s.running("minigame", 1, TaskMode.DYNAMIC, 39);
        assertEquals(List.of(new Stop("minigame", new ServiceId("minigame-2"), Reason.EMPTY)), s.plan());

        // A service that is still starting is room as well.
        Scenario starting = surplus().running("minigame", 1, TaskMode.DYNAMIC, 50)
                .service("minigame", 3, TaskMode.DYNAMIC, ServiceState.STARTING);
        assertEquals(List.of(new Stop("minigame", new ServiceId("minigame-2"), Reason.EMPTY)), starting.plan());
    }

    @Test
    void scaleDownStopsAtMinServicesAndPicksTheHighestIndex() {
        Scenario atMin = surplus().task("minigame", TaskMode.DYNAMIC, 2, 3);
        assertEquals(List.of(), atMin.plan());

        Scenario two = surplus().running("minigame", 3, TaskMode.DYNAMIC, 0)
                .emptySince("minigame-3", NOW.minus(Duration.ofHours(1)));
        assertEquals(List.of(new Stop("minigame", new ServiceId("minigame-3"), Reason.EMPTY)), two.plan(),
                "one per decision");
    }

    @Test
    void staticServicesAreNeverScaledDown() {
        // static-services lowered to 1 later: both static services stay although they are empty for a day.
        Scenario s = new Scenario().task("lobby", "lobby", 1, 1, 3)
                .running("lobby", 1, TaskMode.STATIC, 0).running("lobby", 2, TaskMode.STATIC, 0)
                .emptySince("lobby-1", NOW.minus(Duration.ofDays(1))).emptySince("lobby-2", NOW.minus(Duration.ofDays(1)));
        assertEquals(List.of(), s.plan());

        // Counter-check: the same empty service, dynamic, is stopped.
        Scenario overflow = new Scenario().task("lobby", "lobby", 1, 1, 3)
                .running("lobby", 1, TaskMode.STATIC, 0).running("lobby", 2, TaskMode.DYNAMIC, 0)
                .emptySince("lobby-1", NOW.minus(Duration.ofDays(1))).emptySince("lobby-2", NOW.minus(Duration.ofDays(1)));
        assertEquals(List.of(new Stop("lobby", new ServiceId("lobby-2"), Reason.EMPTY)), overflow.plan());

        // A static service in a task that was switched to dynamic later keeps its folder and stays.
        Scenario switched = new Scenario().task("lobby", TaskMode.DYNAMIC, 0, 3)
                .running("lobby", 1, TaskMode.STATIC, 0).running("lobby", 2, TaskMode.DYNAMIC, 0)
                .emptySince("lobby-1", NOW.minus(Duration.ofDays(1))).emptySince("lobby-2", NOW.minus(Duration.ofDays(1)));
        assertEquals(List.of(new Stop("lobby", new ServiceId("lobby-2"), Reason.EMPTY)), switched.plan());
        switched.running("lobby", 2, TaskMode.DYNAMIC, 3);
        assertEquals(List.of(), switched.plan(), "lobby-1 is static, lobby-2 has players");
    }

    @Test
    void aboveMaxServicesAnEmptyServiceStopsWithoutWaiting() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 0, 1)
                .running("minigame", 1, TaskMode.DYNAMIC, 50).running("minigame", 2, TaskMode.DYNAMIC, 0);
        assertEquals(List.of(new Stop("minigame", new ServiceId("minigame-2"), Reason.ABOVE_MAX)), s.plan());

        // Counter-check: nobody is stopped with players on it, also above the limit.
        s.running("minigame", 2, TaskMode.DYNAMIC, 1);
        assertEquals(List.of(), s.plan());
    }

    @Test
    void tasksAreDecidedIndependently() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3).task("lobby", TaskMode.STATIC, 1, 1)
                .running("minigame", 1, TaskMode.DYNAMIC, 40);
        List<Decision> decisions = s.plan();
        assertEquals(List.of(new Start("lobby", OptionalInt.of(1), Reason.MIN_SERVICES),
                new Start("minigame", OptionalInt.empty(), Reason.PLAYERS)), decisions);
        assertTrue(decisions.stream().allMatch(d -> d.task() != null));
    }

    // ---------------------------------------------------------------- fixture

    /** One configuration and one set of services; every method changes it in place. */
    private static final class Scenario {
        final Map<String, GroupConfig> groups = new TreeMap<>();
        final Map<String, TaskConfig> tasks = new TreeMap<>();
        final Map<ServiceId, ServiceInfo> services = new java.util.LinkedHashMap<>();
        final Map<ServiceId, Integer> players = new HashMap<>();
        final Map<String, Instant> lastAction = new HashMap<>();
        final Map<String, Instant> pausedUntil = new HashMap<>();
        final Map<ServiceId, Instant> emptySince = new HashMap<>();
        final Set<ServiceId> released = new HashSet<>();
        CloudConfig cloud = CloudConfig.defaults().withServers(WantedServers.all());
        Optional<Memory> memory = ROOMY;

        Scenario() {
            group("lobby", ServerKind.LOBBY.templateFolder(), ServerSoftware.PAPER);
            group("survival", ServerKind.SURVIVAL.templateFolder(), ServerSoftware.PAPER);
            group("minigame", ServerKind.MINIGAME.templateFolder(), ServerSoftware.PAPER);
        }

        Scenario group(String name, String template, ServerSoftware software) {
            groups.put(name, new GroupConfig(GroupConfig.SCHEMA_VERSION, template, software, "26.3", JavaSelection.AUTO,
                    new HeapConfig(MemorySize.parse("256M"), MemorySize.parse("1G")), List.of(), CombatSystem.MODERN));
            return this;
        }

        Scenario task(String name, TaskMode mode, int min, int max) {
            return task(name, name, mode, min, max);
        }

        /** STATIC: every service static and never scaled, as a task file of schema-version 1 migrates. */
        Scenario task(String name, String group, TaskMode mode, int min, int max) {
            return task(name, group, mode == TaskMode.STATIC ? min : 0, min, mode == TaskMode.STATIC ? min : max);
        }

        Scenario task(String name, String group, int staticServices, int min, int max) {
            tasks.put(name, new TaskConfig(TaskConfig.SCHEMA_VERSION, group,
                    new ScalingConfig(staticServices, min, max, 50, 80, 50, TimeSpan.parse("5m"), TimeSpan.parse("60s")),
                    List.of()));
            return this;
        }

        Scenario switchOff(ServerKind kind) {
            cloud = cloud.withServers(cloud.servers().with(kind, false));
            return this;
        }

        Scenario service(String task, int index, TaskMode mode, ServiceState state) {
            ServiceId id = new ServiceId(task + "-" + index);
            services.put(id, new ServiceInfo(id, task, task, index, mode, state, 30000 + index, Path.of("x"),
                    state.hasProcess() ? OptionalLong.of(100 + index) : OptionalLong.empty(),
                    Optional.of(NOW.minus(Duration.ofHours(1))), OptionalInt.empty(), 0));
            players.remove(id);
            return this;
        }

        Scenario running(String task, int index, TaskMode mode, int online) {
            service(task, index, mode, ServiceState.RUNNING);
            players.put(new ServiceId(task + "-" + index), online);
            return this;
        }

        Scenario runningUnknown(String task, int index, TaskMode mode) {
            return service(task, index, mode, ServiceState.RUNNING);
        }

        Scenario emptySince(String id, Instant since) {
            emptySince.put(new ServiceId(id), since);
            return this;
        }

        Scenario released(String id) {
            released.add(new ServiceId(id));
            return this;
        }

        Scenario memory(Optional<Memory> value) {
            memory = value;
            return this;
        }

        List<Decision> plan() {
            ConfigSnapshot snapshot = ConfigSnapshot.of(cloud, NodesConfig.defaults(), groups, tasks);
            return ScalingPlanner.plan(new ScalingPlanner.Input(snapshot, "node-1", new ArrayList<>(services.values()),
                    new ScalingPlanner.Signals(Map.copyOf(players), memory), Map.copyOf(lastAction),
                    Map.copyOf(pausedUntil), Map.copyOf(emptySince), Set.copyOf(released), NOW));
        }
    }
}
