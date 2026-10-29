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
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.HeapConfig;
import net.vectora.cloud.config.model.JavaSelection;
import net.vectora.cloud.config.model.MemoryReserve;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.NodesConfig;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.config.model.WantedServers;
import net.vectora.cloud.node.scaling.ScalingPlanner.Decision;
import net.vectora.cloud.node.scaling.ScalingPlanner.Drain;
import net.vectora.cloud.node.scaling.ScalingPlanner.Hold;
import net.vectora.cloud.node.scaling.ScalingPlanner.Held;
import net.vectora.cloud.node.scaling.ScalingPlanner.Load;
import net.vectora.cloud.node.scaling.ScalingPlanner.Memory;
import net.vectora.cloud.node.scaling.ScalingPlanner.Reason;
import net.vectora.cloud.node.scaling.ScalingPlanner.Start;
import net.vectora.cloud.node.service.ServiceInfo;
import net.vectora.cloud.node.service.ServiceState;
import net.vectora.protocol.ServiceId;
import org.junit.jupiter.api.Test;

/** The pure scaling decisions: every rule with its boundary, and the counter-check that shows the rule is what decides. */
class ScalingPlannerTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;
    /** Plenty: 64 GB, 1 GB used. */
    private static final Optional<Memory> ROOMY = Optional.of(new Memory(1024 * MB, 64L * GB));

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
        assertEquals(List.of(new Start("lobby", OptionalInt.of(1), Reason.MIN_SERVICES),
                new Start("lobby", OptionalInt.empty(), Reason.MIN_SERVICES)), s.plan(), "static first, then dynamic");
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
    void anotherServiceStartsOnceTheLoadOfTheGroupReachesScaleUpAt() {
        // 50 players per service, scale-up-at 80 %: 40 of 50.
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3).running("minigame", 1, TaskMode.DYNAMIC, 39);
        assertEquals(List.of(), s.plan(), "39 of 50 is 78 %");
        s.running("minigame", 1, TaskMode.DYNAMIC, 40);
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), s.plan());
    }

    @Test
    void theLoadIsTheSumOfTheGroupNotEachServiceOnItsOwn() {
        // 10 + 70 of 2 x 50 is 80 %, although minigame-1 is nearly empty.
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3)
                .running("minigame", 1, TaskMode.DYNAMIC, 10).running("minigame", 2, TaskMode.DYNAMIC, 70);
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), s.plan());

        // Counter-check: one player less is 79 %.
        s.running("minigame", 2, TaskMode.DYNAMIC, 69);
        assertEquals(List.of(), s.plan());
    }

    @Test
    void staticServicesCountTowardTheLoadOfTheGroup() {
        // Mixed task: static lobby-1 with 40 of 50 makes the group 80 %, the overflow starts dynamically.
        Scenario mixed = new Scenario().task("lobby", "lobby", 1, 1, 4).running("lobby", 1, TaskMode.STATIC, 40);
        assertEquals(List.of(new Start("lobby", OptionalInt.empty(), Reason.LOAD)), mixed.plan());

        // Counter-check: an empty static service beside it halves the load.
        mixed.running("lobby", 2, TaskMode.STATIC, 0);
        assertEquals(List.of(), mixed.plan());
    }

    @Test
    void scaleUpAtIsReadFromTheTask() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3).running("minigame", 1, TaskMode.DYNAMIC, 30);
        assertEquals(List.of(), s.plan(), "30 of 50 is below 80 %");
        s.tasks.put("minigame", s.tasks.get("minigame").withScaling(s.tasks.get("minigame").scaling().withScaleUpAt(60)));
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), s.plan(), "60 % reached");
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
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), full.plan());
    }

    @Test
    void staticTasksAndProxiesNeverScaleUp() {
        // static = max: a full static task has no room for a dynamic service.
        Scenario lobby = new Scenario().task("lobby", "lobby", 1, 1, 1).running("lobby", 1, TaskMode.STATIC, 50);
        assertEquals(List.of(), lobby.plan());

        Scenario proxy = new Scenario().group("proxy", ServerKind.PROXY.templateFolder(), ServerSoftware.VELOCITY)
                .task("proxy", "proxy", TaskMode.DYNAMIC, 1, 2).running("proxy", 1, TaskMode.DYNAMIC, 500);
        assertEquals(List.of(), proxy.plan(), "one proxy per node");
    }

    @Test
    void aServiceBeingEmptiedIsNotPartOfTheLoad() {
        // minigame-2 is drained: the 40 players on minigame-1 alone are 80 %.
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3)
                .running("minigame", 1, TaskMode.DYNAMIC, 40).running("minigame", 2, TaskMode.DYNAMIC, 0);
        assertEquals(List.of(), s.plan(), "40 of 100");
        s.draining.add(new ServiceId("minigame-2"));
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), s.plan());
    }

    @Test
    void aServiceBeingEmptiedDoesNotCountForMinServices() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 2, 3)
                .running("minigame", 1, TaskMode.DYNAMIC, 10).running("minigame", 2, TaskMode.DYNAMIC, 0);
        assertEquals(List.of(), s.plan(), "two of two");
        s.draining.add(new ServiceId("minigame-2"));
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.MIN_SERVICES)), s.plan());
    }

    @Test
    void theCooldownSpacesTheDecisionsOfATask() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3).running("minigame", 1, TaskMode.DYNAMIC, 40);
        s.lastAction.put("minigame", NOW.minusSeconds(59));
        assertEquals(List.of(), s.plan(), "59 s after the last decision, cooldown 60 s");
        s.lastAction.put("minigame", NOW.minusSeconds(60));
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), s.plan());
    }

    @Test
    void theMemoryBudgetOfTheNodeHoldsAScaleUpBack() {
        // max-memory 64G, so the budget is the measured memory. minigame heap max 1G; lobby-1 (1G) and
        // minigame-1 (1G) already run.
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
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), s.plan());
        s.memory(Optional.of(new Memory(0, 3072 * MB)));
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), s.plan());
    }

    @Test
    void maxMemoryCapsTheBudgetBelowTheMeasuredMemory() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3).task("lobby", TaskMode.STATIC, 1, 1)
                .running("lobby", 1, TaskMode.STATIC, 0).running("minigame", 1, TaskMode.DYNAMIC, 40)
                .memory(Optional.of(new Memory(0, 64L * GB)));
        s.maxMemory("2G");
        assertEquals(List.of(new Held("minigame", Hold.MEMORY_BUDGET)), s.plan(), "3 GB of heaps, 2 GB allowed");
        s.maxMemory("3G");
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), s.plan());
    }

    @Test
    void withoutMaxMemoryTheReserveForSystemNodeAndDatabaseIsKeptFree() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3).task("lobby", TaskMode.STATIC, 1, 1)
                .running("lobby", 1, TaskMode.STATIC, 0).running("minigame", 1, TaskMode.DYNAMIC, 40);
        s.maxMemory(null);
        long nodeHeap = 512 * MB;
        // Managed database: 1 GiB + 512 MiB + 256 MiB + 512 MiB = 2304 MiB; 3 GB of heaps need 5376 MiB.
        assertEquals(2304 * MB, ScalingPlanner.reserve(s.snapshot(), "node-1", nodeHeap));
        s.memory(Optional.of(new Memory(0, 5376 * MB - 1, nodeHeap)));
        assertEquals(List.of(new Held("minigame", Hold.MEMORY_BUDGET)), s.plan());
        s.memory(Optional.of(new Memory(0, 5376 * MB, nodeHeap)));
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), s.plan());

        // Counter-check: an external database or none at all needs no reserve here.
        s.cloud = s.cloud.withDatabase(DatabaseConfig.defaults().withMode(DatabaseMode.EXTERNAL));
        assertEquals(1792 * MB, ScalingPlanner.reserve(s.snapshot(), "node-1", nodeHeap));
        s.cloud = s.cloud.withDatabase(DatabaseConfig.defaults().withMode(DatabaseMode.DOCKER))
                .withServers(s.cloud.servers().with(ServerKind.DATABASE, false));
        assertEquals(1792 * MB, ScalingPlanner.reserve(s.snapshot(), "node-1", nodeHeap));
        s.cloud = s.cloud.withServers(s.cloud.servers().with(ServerKind.DATABASE, true));
        assertEquals(2304 * MB, ScalingPlanner.reserve(s.snapshot(), "node-1", nodeHeap), "docker counts like managed");
    }

    @Test
    void theReserveComesFromMemoryReserveOfTheNodeWithDefaultsForMissingFields() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3).task("lobby", TaskMode.STATIC, 1, 1)
                .running("lobby", 1, TaskMode.STATIC, 0).running("minigame", 1, TaskMode.DYNAMIC, 40);
        long nodeHeap = 512 * MB;
        // system 2G, node-overhead missing (256M), database 1G with a managed database: 3328 MiB + heap.
        s.nodes = new NodesConfig(NodesConfig.SCHEMA_VERSION, List.of(NodeConfig.defaults().withMemoryReserve(
                new MemoryReserve(MemorySize.parse("2G"), null, MemorySize.parse("1G")))));
        assertEquals(3840 * MB, ScalingPlanner.reserve(s.snapshot(), "node-1", nodeHeap));
        // 3 GB of heaps need 3072 + 3840 MiB.
        s.memory(Optional.of(new Memory(0, 6912 * MB - 1, nodeHeap)));
        assertEquals(List.of(new Held("minigame", Hold.MEMORY_BUDGET)), s.plan());
        s.memory(Optional.of(new Memory(0, 6912 * MB, nodeHeap)));
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), s.plan());

        // Counter-check: another node's reserve does not apply; this node then has the defaults.
        s.nodes = new NodesConfig(NodesConfig.SCHEMA_VERSION, List.of(new NodeConfig("node-2", "127.0.0.1", 7000,
                NodeConfig.defaults().servicePorts()).withMemoryReserve(
                new MemoryReserve(MemorySize.parse("8G"), null, null))));
        assertEquals(2304 * MB, ScalingPlanner.reserve(s.snapshot(), "node-1", nodeHeap));
    }

    @Test
    void theBudgetIsNeverNegative() {
        Scenario s = new Scenario();
        s.maxMemory(null);
        assertEquals(0, ScalingPlanner.budget(s.snapshot(), "node-1", new Memory(0, GB)));
        assertEquals(8L * GB - 2304 * MB, ScalingPlanner.budget(s.snapshot(), "node-1", new Memory(0, 8L * GB, 512 * MB)));
        s.maxMemory("16G");
        assertEquals(8L * GB, ScalingPlanner.budget(s.snapshot(), "node-1", new Memory(0, 8L * GB, 512 * MB)),
                "max-memory above the machine: the machine");
    }

    @Test
    void aPausedTaskStartsNothingButMayStillScaleDown() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3).running("minigame", 1, TaskMode.DYNAMIC, 40);
        s.pausedUntil.put("minigame", NOW.plusSeconds(1));
        assertEquals(List.of(new Held("minigame", Hold.PAUSED)), s.plan());
        s.pausedUntil.put("minigame", NOW);
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), s.plan(), "pause is over");

        Scenario fill = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3);
        fill.pausedUntil.put("minigame", NOW.plusSeconds(1));
        assertEquals(List.of(new Held("minigame", Hold.PAUSED)), fill.plan());

        Scenario down = surplus();
        down.pausedUntil.put("minigame", NOW.plusSeconds(600));
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.LOW_LOAD)), down.plan());
    }

    // ---------------------------------------------------------------- scale down

    /** minigame-1 with 10 players, minigame-2 empty, low load for exactly scale-down-after (5 minutes). */
    private static Scenario surplus() {
        return new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3)
                .running("minigame", 1, TaskMode.DYNAMIC, 10).running("minigame", 2, TaskMode.DYNAMIC, 0)
                .lowSince("minigame", NOW.minus(Duration.ofMinutes(5)));
    }

    @Test
    void anEmptySurplusServiceStopsOnceTheLoadHasBeenLowForScaleDownAfter() {
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.LOW_LOAD)), surplus().plan());

        Scenario early = surplus().lowSince("minigame", NOW.minus(Duration.ofMinutes(5)).plusSeconds(1));
        assertEquals(List.of(), early.plan(), "low for 4:59");

        Scenario never = surplus();
        never.lowSince.clear();
        assertEquals(List.of(), never.plan(), "the autoscaler has not seen the condition hold");
    }

    @Test
    void aServiceWithPlayersIsEmptiedWhenTheRestStaysBelowScaleDownBelow() {
        // 20 + 4 players; without minigame-2 they are 24 of 50 = 48 % < 50 %.
        Scenario s = surplus().running("minigame", 1, TaskMode.DYNAMIC, 20).running("minigame", 2, TaskMode.DYNAMIC, 4);
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.LOW_LOAD)), s.plan());

        // Counter-check: 25 of 50 is exactly 50 %, not below.
        s.running("minigame", 2, TaskMode.DYNAMIC, 5);
        assertEquals(List.of(), s.plan());
    }

    @Test
    void theEmptiestDynamicServiceIsChosenAndOfEqualOnesTheHighestIndex() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 4)
                .running("minigame", 1, TaskMode.DYNAMIC, 3).running("minigame", 2, TaskMode.DYNAMIC, 1)
                .running("minigame", 3, TaskMode.DYNAMIC, 2).lowSince("minigame", NOW.minus(Duration.ofHours(1)));
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.LOW_LOAD)), s.plan());

        s.running("minigame", 3, TaskMode.DYNAMIC, 1);
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-3"), Reason.LOW_LOAD)), s.plan());
    }

    @Test
    void anUnknownCountOrAServiceBeingEmptiedHoldsTheScaleDownBack() {
        Scenario unknown = surplus().runningUnknown("minigame", 1, TaskMode.DYNAMIC);
        assertEquals(List.of(), unknown.plan(), "no report: the load is not known");

        Scenario unknownCandidate = surplus().runningUnknown("minigame", 2, TaskMode.DYNAMIC);
        assertEquals(List.of(), unknownCandidate.plan());

        Scenario draining = surplus();
        draining.draining.add(new ServiceId("minigame-2"));
        assertEquals(List.of(), draining.plan(), "already being emptied, and minigame-1 is the last one left");
    }

    @Test
    void scaleDownStopsAtMinServicesAndDecidesOneServicePerRound() {
        Scenario atMin = surplus().task("minigame", TaskMode.DYNAMIC, 2, 3);
        assertEquals(List.of(), atMin.plan());

        Scenario two = surplus().running("minigame", 3, TaskMode.DYNAMIC, 0);
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-3"), Reason.LOW_LOAD)), two.plan(),
                "one per decision");
    }

    @Test
    void scaleDownBelowIsReadFromTheTask() {
        // 20 of 50 without minigame-2: 40 %.
        Scenario s = surplus().running("minigame", 1, TaskMode.DYNAMIC, 20).running("minigame", 2, TaskMode.DYNAMIC, 0);
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.LOW_LOAD)), s.plan());
        s.tasks.put("minigame", s.tasks.get("minigame").withScaling(s.tasks.get("minigame").scaling().withScaleDownBelow(40)));
        assertEquals(List.of(), s.plan(), "40 % is not below 40 %");
    }

    @Test
    void staticServicesAreNeverEmptiedButTakePlayersFromADynamicOne() {
        // Both static (static-services 2), empty for a day: they stay.
        Scenario s = new Scenario().task("lobby", "lobby", 2, 2, 3)
                .running("lobby", 1, TaskMode.STATIC, 0).running("lobby", 2, TaskMode.STATIC, 0)
                .lowSince("lobby", NOW.minus(Duration.ofDays(1)));
        assertEquals(List.of(), s.plan());
        s.task("lobby", "lobby", 2, 1, 3);
        assertEquals(List.of(), s.plan(), "min-services 1, but both are within static-services");

        // Counter-check: the same service, dynamic, is stopped.
        Scenario overflow = new Scenario().task("lobby", "lobby", 1, 1, 3)
                .running("lobby", 1, TaskMode.STATIC, 0).running("lobby", 2, TaskMode.DYNAMIC, 0)
                .lowSince("lobby", NOW.minus(Duration.ofDays(1)));
        assertEquals(List.of(new Drain("lobby", new ServiceId("lobby-2"), Reason.LOW_LOAD)), overflow.plan());

        // With players on it, they move to the static lobby-1 although it has more of them.
        overflow.running("lobby", 1, TaskMode.STATIC, 10).running("lobby", 2, TaskMode.DYNAMIC, 3);
        assertEquals(List.of(new Drain("lobby", new ServiceId("lobby-2"), Reason.LOW_LOAD)), overflow.plan());
    }

    @Test
    void aboveMaxServicesAnEmptyServiceStopsWithoutWaiting() {
        Scenario s = new Scenario().task("minigame", TaskMode.DYNAMIC, 0, 1)
                .running("minigame", 1, TaskMode.DYNAMIC, 50).running("minigame", 2, TaskMode.DYNAMIC, 0);
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.ABOVE_MAX)), s.plan());

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
                new Start("minigame", OptionalInt.empty(), Reason.LOAD)), decisions);
        assertTrue(decisions.stream().allMatch(d -> d.task() != null));
    }

    @Test
    void staticServicesAboveALoweredStaticServicesCountAsDynamicFromTheHighestIndex() {
        // static-services lowered from 3 to 1: lobby-2 and lobby-3 are surplus, lobby-1 stays static.
        Scenario s = new Scenario().task("lobby", "lobby", 1, 1, 3)
                .running("lobby", 1, TaskMode.STATIC, 0).running("lobby", 2, TaskMode.STATIC, 0)
                .running("lobby", 3, TaskMode.STATIC, 0).lowSince("lobby", NOW.minus(Duration.ofDays(1)));
        assertEquals(List.of(new Drain("lobby", new ServiceId("lobby-3"), Reason.LOW_LOAD)), s.plan());

        // lobby-3 has a player, lobby-2 none: the emptiest surplus one goes, never lobby-1.
        s.running("lobby", 3, TaskMode.STATIC, 1);
        assertEquals(List.of(new Drain("lobby", new ServiceId("lobby-2"), Reason.LOW_LOAD)), s.plan());

        // Counter-check: with static-services back at 3 none of them is surplus.
        s.task("lobby", "lobby", 3, 3, 3);
        assertEquals(List.of(), s.plan());
    }

    @Test
    void aSurplusStaticServiceThatStoppedNoLongerCountsTowardMinServices() {
        // static-services 1, min 2: lobby-2 (static, surplus) was stopped by the scaler, so one more starts,
        // a dynamic one, since the static quota is full.
        Scenario s = new Scenario().task("lobby", "lobby", 1, 2, 3)
                .running("lobby", 1, TaskMode.STATIC, 0).service("lobby", 2, TaskMode.STATIC, ServiceState.STOPPED);
        assertEquals(List.of(new Start("lobby", OptionalInt.empty(), Reason.MIN_SERVICES)), s.plan());
        // Counter-check: within static-services it would count although stopped (stopped by hand stays down).
        s.task("lobby", "lobby", 2, 2, 3);
        assertEquals(List.of(), s.plan());
    }

    @Test
    void everyMissingServiceOfMinServicesStartsInOneRoundWithoutCooldown() {
        // static 2, min 4, max 6, nothing runs (for example after the EULA was given again).
        Scenario s = new Scenario().task("lobby", "lobby", 2, 4, 6);
        s.lastAction.put("lobby", NOW.minusSeconds(1));
        assertEquals(List.of(new Start("lobby", OptionalInt.of(1), Reason.MIN_SERVICES),
                new Start("lobby", OptionalInt.of(2), Reason.MIN_SERVICES),
                new Start("lobby", OptionalInt.empty(), Reason.MIN_SERVICES),
                new Start("lobby", OptionalInt.empty(), Reason.MIN_SERVICES)), s.plan(), "cooldown does not apply");

        // Released static services come back at their own index first; max-services caps the batch.
        Scenario back = new Scenario().task("lobby", "lobby", 2, 3, 3)
                .service("lobby", 1, TaskMode.STATIC, ServiceState.STOPPED).released("lobby-1")
                .service("lobby", 2, TaskMode.STATIC, ServiceState.STOPPED).released("lobby-2")
                .running("lobby", 3, TaskMode.DYNAMIC, 0);
        assertEquals(List.of(new Start("lobby", OptionalInt.of(1), Reason.MIN_SERVICES),
                new Start("lobby", OptionalInt.of(2), Reason.MIN_SERVICES)), back.plan());

        // Counter-check: the cooldown still spaces a scale-up by load.
        Scenario load = new Scenario().task("minigame", TaskMode.DYNAMIC, 1, 3).running("minigame", 1, TaskMode.DYNAMIC, 40);
        load.lastAction.put("minigame", NOW.minusSeconds(1));
        assertEquals(List.of(), load.plan());
    }

    @Test
    void twoTasksOfOneGroupStartOnlyOneServiceByLoadInARound() {
        Scenario s = new Scenario().task("lobby", "lobby", 0, 1, 3).task("lobby-eu", "lobby", 0, 1, 3)
                .running("lobby", 1, TaskMode.DYNAMIC, 40).running("lobby-eu", 2, TaskMode.DYNAMIC, 40);
        assertEquals(List.of(new Start("lobby", OptionalInt.empty(), Reason.LOAD)), s.plan(),
                "80 % for the group: one new service, not one per task");

        // Counter-check: two groups each get theirs.
        Scenario two = new Scenario().task("lobby", "lobby", 0, 1, 3).task("minigame", TaskMode.DYNAMIC, 1, 3)
                .running("lobby", 1, TaskMode.DYNAMIC, 40).running("minigame", 1, TaskMode.DYNAMIC, 40);
        assertEquals(List.of(new Start("lobby", OptionalInt.empty(), Reason.LOAD),
                new Start("minigame", OptionalInt.empty(), Reason.LOAD)), two.plan());
    }

    @Test
    void whetherAServiceIsEmptyIsJudgedByTheHigherOfTheBridgesAndTheProxysCount() {
        // Above max-services: the proxy has just routed a player to minigame-2, the bridge still says 0.
        Scenario above = new Scenario().task("minigame", TaskMode.DYNAMIC, 0, 1)
                .running("minigame", 1, TaskMode.DYNAMIC, 50).running("minigame", 2, TaskMode.DYNAMIC, 0)
                .present("minigame-2", 1);
        assertEquals(List.of(), above.plan());
        above.present("minigame-2", 0);
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.ABOVE_MAX)), above.plan());

        // Low load: 20 + 4 is below 50 % of 50, but 20 + 5 seen by one side is not.
        Scenario low = surplus().running("minigame", 1, TaskMode.DYNAMIC, 20).running("minigame", 2, TaskMode.DYNAMIC, 4);
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.LOW_LOAD)), low.plan());
        low.present("minigame-2", 5);
        assertEquals(List.of(), low.plan());
    }

    // ---------------------------------------------------------------- group load

    @Test
    void theGroupLoadAddsUpEveryTaskOfTheGroupAndOnlyRunningServices() {
        Scenario s = new Scenario().task("lobby", "lobby", 1, 1, 3).task("lobby-eu", "lobby", 0, 0, 3)
                .running("lobby", 1, TaskMode.STATIC, 10).running("lobby-eu", 2, TaskMode.DYNAMIC, 20)
                .service("lobby", 3, TaskMode.DYNAMIC, ServiceState.STARTING);
        assertEquals(Optional.of(new Load(30, 100)), s.load("lobby", null));
        assertEquals(Optional.of(new Load(20, 50)), s.load("lobby", new ServiceId("lobby-1")));
        s.runningUnknown("lobby-eu", 2, TaskMode.DYNAMIC);
        assertEquals(Optional.empty(), s.load("lobby", null));
    }

    // ---------------------------------------------------------------- fixture

    /** One configuration and one set of services; every method changes it in place. */
    private static final class Scenario {
        final Map<String, GroupConfig> groups = new TreeMap<>();
        final Map<String, TaskConfig> tasks = new TreeMap<>();
        final Map<ServiceId, ServiceInfo> services = new java.util.LinkedHashMap<>();
        final Map<ServiceId, Integer> players = new HashMap<>();
        /** The higher count of bridge and proxy, where it differs from {@link #players}. */
        final Map<ServiceId, Integer> present = new HashMap<>();
        final Map<String, Instant> lastAction = new HashMap<>();
        final Map<String, Instant> pausedUntil = new HashMap<>();
        final Map<String, Instant> lowSince = new HashMap<>();
        final Set<ServiceId> released = new HashSet<>();
        final Set<ServiceId> draining = new HashSet<>();
        CloudConfig cloud = CloudConfig.defaults().withServers(WantedServers.all());
        /** {@code max-memory} 64G, so the budget is the measured memory in most tests. */
        NodesConfig nodes = withMaxMemory(MemorySize.parse("64G"));
        Optional<Memory> memory = ROOMY;

        Scenario() {
            group("lobby", ServerKind.LOBBY.templateFolder(), ServerSoftware.PAPER);
            group("survival", ServerKind.SURVIVAL.templateFolder(), ServerSoftware.PAPER);
            group("minigame", ServerKind.MINIGAME.templateFolder(), ServerSoftware.PAPER);
        }

        private static NodesConfig withMaxMemory(MemorySize value) {
            return new NodesConfig(NodesConfig.SCHEMA_VERSION, List.of(NodeConfig.defaults().withMaxMemory(value)));
        }

        Scenario maxMemory(String value) {
            nodes = withMaxMemory(value == null ? null : MemorySize.parse(value));
            return this;
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
            String group = tasks.containsKey(task) ? tasks.get(task).group() : task;
            services.put(id, new ServiceInfo(id, group, task, index, mode, state, 30000 + index, Path.of("x"),
                    state.hasProcess() ? OptionalLong.of(100 + index) : OptionalLong.empty(),
                    Optional.of(NOW.minus(Duration.ofHours(1))), OptionalInt.empty(), 0));
            players.remove(id);
            present.remove(id);
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

        Scenario present(String id, int count) {
            present.put(new ServiceId(id), count);
            return this;
        }

        Scenario lowSince(String task, Instant since) {
            lowSince.put(task, since);
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

        ConfigSnapshot snapshot() {
            return ConfigSnapshot.of(cloud, nodes, groups, tasks);
        }

        Optional<Load> load(String group, ServiceId leaveOut) {
            return ScalingPlanner.groupLoad(snapshot(), group, new ArrayList<>(services.values()),
                    new ScalingPlanner.Signals(Map.copyOf(players), memory), Set.copyOf(draining), leaveOut);
        }

        List<Decision> plan() {
            return ScalingPlanner.plan(new ScalingPlanner.Input(snapshot(), "node-1", new ArrayList<>(services.values()),
                    new ScalingPlanner.Signals(Map.copyOf(players), Map.copyOf(present), memory), Map.copyOf(lastAction),
                    Map.copyOf(pausedUntil), Map.copyOf(lowSince), Set.copyOf(released), Set.copyOf(draining), NOW));
        }
    }
}
