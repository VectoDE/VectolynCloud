package net.vectora.cloud.node.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
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
import net.vectora.cloud.node.channel.ChannelBridge.DrainResult;
import net.vectora.cloud.node.scaling.ScalingPlanner.Decision;
import net.vectora.cloud.node.scaling.ScalingPlanner.Drain;
import net.vectora.cloud.node.scaling.ScalingPlanner.Hold;
import net.vectora.cloud.node.scaling.ScalingPlanner.Held;
import net.vectora.cloud.node.scaling.ScalingPlanner.Memory;
import net.vectora.cloud.node.scaling.ScalingPlanner.Reason;
import net.vectora.cloud.node.scaling.ScalingPlanner.Start;
import net.vectora.cloud.node.service.ServiceException;
import net.vectora.cloud.node.service.ServiceInfo;
import net.vectora.cloud.node.service.ServiceState;
import net.vectora.protocol.ServiceId;
import org.junit.jupiter.api.Test;

/** The rounds of the autoscaler on a fake service core: what it remembers, when it waits, and when it does nothing. */
class AutoscalerTest {

    private static final Instant T0 = Instant.parse("2026-10-29T12:00:00Z");
    private static final Optional<Memory> ROOMY = Optional.of(new Memory(0, 64L * 1024 * 1024 * 1024));

    @Test
    void nothingHappensBeforeTheSetupIsDoneWhileSuspendedAndAfterClose() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        f.cloud = f.cloud.withEulaAccepted(false);
        assertEquals(List.of(), f.scaler.tick(), "no EULA");
        f.cloud = f.cloud.withEulaAccepted(true).withSetupComplete(false);
        assertEquals(List.of(), f.scaler.tick(), "setup not complete");
        f.cloud = f.cloud.withSetupComplete(true);
        f.scaler.suspend(true);
        assertEquals(List.of(), f.scaler.tick(), "suspended");
        f.scaler.suspend(false);

        // Counter-check: once everything is in place, the minimum is started.
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.MIN_SERVICES)), f.scaler.tick());
        assertEquals(List.of("start minigame"), f.target.calls);

        f.scaler.close();
        f.target.services.clear();
        assertEquals(List.of(), f.scaler.tick(), "closed");
    }

    @Test
    void scalesUpOnLoadAndDownAfterTheLowLoadTimeWithTheCooldownInBetween() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        f.target.add("minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.players.put(new ServiceId("minigame-1"), 40);
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), f.scaler.tick());
        f.target.setState("minigame-2", ServiceState.RUNNING);
        f.players.put(new ServiceId("minigame-1"), 10);
        f.players.put(new ServiceId("minigame-2"), 0);

        // The load is low from now on; the cooldown (60 s) and scale-down-after (5 min) both count.
        f.advance(Duration.ofSeconds(30));
        assertEquals(List.of(), f.scaler.tick(), "cooldown");
        f.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertEquals(List.of(), f.scaler.tick(), "low for 4:59");
        f.advance(Duration.ofSeconds(1));
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.LOW_LOAD)), f.scaler.tick());
        assertEquals(List.of("start minigame", "drain minigame-2", "stop minigame-2"), f.target.calls,
                "an empty service is closed and checked as well before it stops");
    }

    @Test
    void aStopThatFailsAfterTheDrainOpensTheServiceAgain() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        f.target.add("minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.target.add("minigame", 2, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.players.put(new ServiceId("minigame-1"), 10);
        f.players.put(new ServiceId("minigame-2"), 0);
        f.target.failStops = true;
        f.scaler.tick();
        f.advance(Duration.ofMinutes(5));
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.LOW_LOAD)), f.scaler.tick());
        assertEquals(List.of("drain minigame-2", "stop minigame-2", "reopen minigame-2"), f.target.calls);

        // Counter-check: a stop that works leaves it closed until it is gone.
        f.target.failStops = false;
        f.target.calls.clear();
        f.scaler.tick();
        f.advance(Duration.ofMinutes(5));
        f.scaler.tick();
        assertEquals(List.of("drain minigame-2", "stop minigame-2"), f.target.calls);
    }

    @Test
    void theDrainWaitsTheDrainTimeoutOfTheGroup() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        f.groups.put("minigame", f.groups.get("minigame").withDrainTimeout(TimeSpan.parse("45s")));
        f.target.add("minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.target.add("minigame", 2, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.players.put(new ServiceId("minigame-1"), 10);
        f.players.put(new ServiceId("minigame-2"), 0);
        f.scaler.tick();
        f.advance(Duration.ofMinutes(5));
        f.scaler.tick();
        assertEquals(List.of(Duration.ofSeconds(45)), f.target.drainTimeouts);

        // Counter-check: without drain-timeout the default of 30 seconds.
        f.groups.put("minigame", f.groups.get("minigame").withDrainTimeout(null));
        f.target.add("minigame", 2, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.scaler.tick();
        f.advance(Duration.ofMinutes(5));
        f.scaler.tick();
        assertEquals(List.of(Duration.ofSeconds(45), Duration.ofSeconds(30)), f.target.drainTimeouts);
    }

    @Test
    void everyMissingServiceComesBackInOneRoundAfterTheEulaWasGivenAgain() {
        Fixture f = new Fixture().task("lobby", TaskMode.STATIC, 3, 3);
        for (int i = 1; i <= 3; i++) {
            f.target.add("lobby", i, TaskMode.STATIC, ServiceState.STOPPED);
        }
        ConfigSnapshot withoutEula = f.snapshot().withCloud(f.cloud.withEulaAccepted(false).withSetupComplete(false));
        for (ServiceInfo s : List.copyOf(f.target.services.values())) {
            f.scaler.stateChanged(s, ServiceState.STOPPING, withoutEula);
        }
        assertEquals(3, f.scaler.tick().size(), "all three at once");
        assertEquals(List.of("start lobby 1", "start lobby 2", "start lobby 3"), f.target.calls);
    }

    @Test
    void anUnknownPlayerCountIsWarnedOnceNamingTheServiceAndAgainAfterItWasKnown() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        f.target.add("minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING);
        List<String> warnings = new ArrayList<>();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(Autoscaler.class.getName());
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                if (r.getLevel() == java.util.logging.Level.WARNING) {
                    warnings.add(java.text.MessageFormat.format(r.getMessage(), r.getParameters()));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            f.scaler.tick();
            f.scaler.tick();
            assertEquals(1, warnings.size(), "once while it stays unknown: " + warnings);
            assertTrue(warnings.get(0).contains("minigame-1") && warnings.get(0).contains("group minigame"),
                    warnings.get(0));

            // Counter-check: known in between, then unknown again, warns again.
            f.players.put(new ServiceId("minigame-1"), 5);
            f.scaler.tick();
            assertEquals(1, warnings.size(), "no warning while known");
            f.players.remove(new ServiceId("minigame-1"));
            f.scaler.tick();
            assertEquals(2, warnings.size(), "again after it was known");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void aRiseOfTheLoadResetsTheLowLoadTime() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        f.target.add("minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.target.add("minigame", 2, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.players.put(new ServiceId("minigame-1"), 10);
        f.players.put(new ServiceId("minigame-2"), 0);
        assertEquals(List.of(), f.scaler.tick());
        f.advance(Duration.ofMinutes(4));
        // 25 of 50 without minigame-2: not below 50 %.
        f.players.put(new ServiceId("minigame-1"), 25);
        assertEquals(List.of(), f.scaler.tick());
        f.players.put(new ServiceId("minigame-1"), 10);
        assertEquals(List.of(), f.scaler.tick(), "low again from now on");
        f.advance(Duration.ofMinutes(4));
        assertEquals(List.of(), f.scaler.tick(), "low again for 4 minutes only, 8 minutes after the first time");
        f.advance(Duration.ofMinutes(1));
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.LOW_LOAD)), f.scaler.tick());
    }

    @Test
    void aPlayerOnTheSurplusServiceDoesNotResetTheTimeButIsMovedAway() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        f.target.add("minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.target.add("minigame", 2, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.players.put(new ServiceId("minigame-1"), 10);
        f.players.put(new ServiceId("minigame-2"), 0);
        assertEquals(List.of(), f.scaler.tick());
        f.advance(Duration.ofMinutes(4));
        f.players.put(new ServiceId("minigame-2"), 1);
        assertEquals(List.of(), f.scaler.tick());
        f.advance(Duration.ofMinutes(1));
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.LOW_LOAD)), f.scaler.tick());
        assertEquals(List.of("drain minigame-2", "stop minigame-2"), f.target.calls);
    }

    @Test
    void aServiceThatCannotBeEmptiedKeepsRunningAndTheTaskWaitsScaleDownAfterAgain() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        f.target.add("minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.target.add("minigame", 2, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.players.put(new ServiceId("minigame-1"), 10);
        f.players.put(new ServiceId("minigame-2"), 2);
        f.target.drainResult = DrainResult.TIMEOUT;
        assertEquals(List.of(), f.scaler.tick());
        f.advance(Duration.ofMinutes(5));
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.LOW_LOAD)), f.scaler.tick());
        assertEquals(List.of("drain minigame-2"), f.target.calls, "no stop, no kick");
        assertEquals(ServiceState.RUNNING, f.target.services.get(new ServiceId("minigame-2")).state());

        f.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertEquals(List.of(), f.scaler.tick(), "the low-load time starts again after the attempt");
        // Counter-check: once scale-down-after has passed again, the next attempt comes.
        f.target.drainResult = DrainResult.EMPTY;
        f.scaler.tick();
        f.advance(Duration.ofMinutes(5));
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.LOW_LOAD)), f.scaler.tick());
        assertEquals(List.of("drain minigame-2", "drain minigame-2", "stop minigame-2"), f.target.calls);
    }

    @Test
    void theLogSaysWhyAServiceCouldNotBeEmptied() {
        assertEquals("Scaling: minigame-2 keeps running: players are still on it after 30 s, a move did not work",
                failedDrain(DrainResult.TIMEOUT, false));
        assertEquals("Scaling: minigame-2 keeps running: no other open server of its group can take its players",
                failedDrain(DrainResult.NO_TARGET, false));
        assertEquals("Scaling: minigame-2 keeps running: moving its players was interrupted",
                failedDrain(DrainResult.EMPTY, true));
    }

    /** One drain that does not empty minigame-2; returns the warning, checks there was no stop. */
    private static String failedDrain(DrainResult result, boolean interrupted) {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        f.target.add("minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.target.add("minigame", 2, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.players.put(new ServiceId("minigame-1"), 10);
        f.players.put(new ServiceId("minigame-2"), 2);
        f.target.drainResult = result;
        f.target.drainInterrupted = interrupted;
        f.scaler.tick();
        f.advance(Duration.ofMinutes(5));
        List<String> warnings = new ArrayList<>();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(Autoscaler.class.getName());
        java.util.logging.Handler handler = warningsInto(warnings);
        logger.addHandler(handler);
        try {
            // The direct executor carries the drain out on this thread.
            assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-2"), Reason.LOW_LOAD)), f.scaler.tick());
        } finally {
            logger.removeHandler(handler);
        }
        assertEquals(interrupted, Thread.interrupted(), "the interrupt is passed on, and only then");
        assertEquals(List.of("drain minigame-2"), f.target.calls, "no stop, no kick");
        assertEquals(1, warnings.size(), warnings.toString());
        return warnings.getFirst();
    }

    private static java.util.logging.Handler warningsInto(List<String> warnings) {
        return new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                if (r.getLevel() == java.util.logging.Level.WARNING) {
                    warnings.add(java.text.MessageFormat.format(r.getMessage(), r.getParameters()));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void aServiceBeingEmptiedIsNotChosenAgainAndTheLowLoadTimeStartsAnew() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 4);
        List<Runnable> queued = new ArrayList<>();
        Autoscaler scaler = Autoscaler.create(f.target, f::snapshot, f::signals, f.clock(), queued::add);
        f.target.add("minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.target.add("minigame", 2, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.target.add("minigame", 3, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.players.put(new ServiceId("minigame-1"), 5);
        f.players.put(new ServiceId("minigame-2"), 2);
        f.players.put(new ServiceId("minigame-3"), 1);
        scaler.tick();
        f.advance(Duration.ofMinutes(5));
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-3"), Reason.LOW_LOAD)), scaler.tick());
        // The drain has not run yet: the next round must not empty minigame-3 a second time, and
        // minigame-2 waits a full scale-down-after of its own.
        f.advance(Duration.ofMinutes(10));
        assertEquals(List.of(), scaler.tick(), "minigame-3 is being emptied already");
        f.target.duringDrain = () -> {
            f.advance(Duration.ofMinutes(5).minusSeconds(1));
            assertEquals(List.of(), scaler.tick(), "while minigame-3 is emptied");
        };
        queued.forEach(Runnable::run);
        assertEquals(List.of("drain minigame-3", "stop minigame-3"), f.target.calls);
    }

    @Test
    void aServiceThatCrashesWhileAnotherIsEmptiedIsReplacedInTheNextRound() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 2, 4);
        List<Runnable> queued = new ArrayList<>();
        Autoscaler scaler = Autoscaler.create(f.target, f::snapshot, f::signals, f.clock(), queued::add);
        f.target.add("minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.target.add("minigame", 2, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.target.add("minigame", 3, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.players.put(new ServiceId("minigame-1"), 10);
        f.players.put(new ServiceId("minigame-2"), 10);
        f.players.put(new ServiceId("minigame-3"), 0);
        scaler.tick();
        f.advance(Duration.ofMinutes(5));
        assertEquals(List.of(new Drain("minigame", new ServiceId("minigame-3"), Reason.LOW_LOAD)), scaler.tick());
        // Counter-check: while nothing crashed, the round during the drain does nothing.
        f.advance(Autoscaler.INTERVAL);
        assertEquals(List.of(), scaler.tick(), "minigame-1 and -2 are the minimum");

        // minigame-1 crashes without a restart; the service core forgets it. minigame-3 is still
        // being emptied (up to its drain-timeout) and does not count.
        ServiceInfo crashed = f.target.services.remove(new ServiceId("minigame-1"));
        f.players.remove(crashed.id());
        scaler.stateChanged(f.target.info("minigame", 1, TaskMode.DYNAMIC, ServiceState.CRASHED, T0),
                ServiceState.RUNNING);
        f.advance(Autoscaler.INTERVAL);
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.MIN_SERVICES)), scaler.tick());
        queued.forEach(Runnable::run);
        assertEquals(List.of("drain minigame-3", "stop minigame-3", "start minigame"), f.target.calls);
    }

    @Test
    void aReleasedStaticServiceIsJudgedByTheConfigurationAtTheMomentItStopped() {
        Fixture f = new Fixture().task("lobby", TaskMode.STATIC, 1, 1);
        f.target.add("lobby", 1, TaskMode.STATIC, ServiceState.STOPPED);
        ConfigSnapshot whenItStopped = f.snapshot().withCloud(f.cloud.withServers(WantedServers.all()
                .with(ServerKind.LOBBY, false)));
        // The switch is on again by the time the event is delivered: the stop was still its doing.
        f.scaler.stateChanged(f.target.services.get(new ServiceId("lobby-1")), ServiceState.STOPPING, whenItStopped);
        assertEquals(List.of(new Start("lobby", OptionalInt.of(1), Reason.MIN_SERVICES)), f.scaler.tick());

        // Counter-check: judged by the configuration at delivery, it would count as stopped by hand.
        Fixture g = new Fixture().task("lobby", TaskMode.STATIC, 1, 1);
        g.target.add("lobby", 1, TaskMode.STATIC, ServiceState.STOPPED);
        g.scaler.stateChanged(g.target.services.get(new ServiceId("lobby-1")), ServiceState.STOPPING, g.snapshot());
        assertEquals(List.of(), g.scaler.tick());
    }

    @Test
    void threeFailedStartsPauseTheTaskUntilThePauseEndsOrItsFileChanges() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        f.target.failStarts = true;
        for (int i = 0; i < Autoscaler.FAILURE_LIMIT; i++) {
            assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.MIN_SERVICES)), f.scaler.tick());
            f.advance(Duration.ofSeconds(60));
        }
        assertEquals(List.of(new Held("minigame", Hold.PAUSED)), f.scaler.tick());
        // Paused at T0 + 120 s, now T0 + 180 s: one second before the end of the pause.
        f.advance(Autoscaler.FAILURE_PAUSE.minusSeconds(61));
        assertEquals(List.of(new Held("minigame", Hold.PAUSED)), f.scaler.tick(), "still paused");
        f.advance(Duration.ofSeconds(1));
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.MIN_SERVICES)), f.scaler.tick(),
                "ten minutes after the third failure");

        // Paused again; this time the operator changes the task file, which ends the pause.
        for (int i = 0; i < Autoscaler.FAILURE_LIMIT - 1; i++) {
            f.advance(Duration.ofSeconds(60));
            f.scaler.tick();
        }
        f.advance(Duration.ofSeconds(60));
        assertEquals(List.of(new Held("minigame", Hold.PAUSED)), f.scaler.tick());
        f.target.failStarts = false;
        f.task("minigame", TaskMode.DYNAMIC, 1, 4);
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.MIN_SERVICES)), f.scaler.tick());
    }

    @Test
    void aStartThatFailsAfterItsServiceWasPreparedCountsOnce() {
        // As the service core does it: PREPARED -> CRASHED reaches the listener, then start() throws.
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        f.target.failStarts = true;
        f.target.crashEvent = true;
        for (int i = 0; i < Autoscaler.FAILURE_LIMIT; i++) {
            assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.MIN_SERVICES)), f.scaler.tick(),
                    "start " + (i + 1));
            f.advance(Duration.ofSeconds(60));
        }
        assertEquals(List.of(new Held("minigame", Hold.PAUSED)), f.scaler.tick(), "paused after the third");
    }

    @Test
    void crashesCountAsFailuresAndAServiceThatRanFiveMinutesClearsThem() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        f.target.add("minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.players.put(new ServiceId("minigame-1"), 10);
        ServiceInfo crashed = f.target.info("minigame", 9, TaskMode.DYNAMIC, ServiceState.CRASHED, T0);
        f.scaler.stateChanged(crashed, ServiceState.STARTING);
        f.scaler.stateChanged(crashed, ServiceState.STARTING);
        // minigame-1 started at T0 - 1 h: it ran stable, so the two crashes are forgotten.
        assertEquals(List.of(), f.scaler.tick());
        f.scaler.stateChanged(crashed, ServiceState.STARTING);
        f.scaler.stateChanged(crashed, ServiceState.STARTING);
        f.players.put(new ServiceId("minigame-1"), 40);
        List<Decision> after = f.scaler.tick();
        assertEquals(1, after.size());
        assertTrue(after.getFirst() instanceof Start, "two crashes after the reset do not pause: " + after);

        // Counter-check: without a stable service three crashes pause the task.
        Fixture g = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        g.target.services.put(new ServiceId("minigame-1"),
                g.target.info("minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING, T0.minusSeconds(299)));
        g.players.put(new ServiceId("minigame-1"), 40);
        for (int i = 0; i < Autoscaler.FAILURE_LIMIT; i++) {
            g.scaler.stateChanged(crashed, ServiceState.STARTING);
        }
        assertEquals(List.of(new Held("minigame", Hold.PAUSED)), g.scaler.tick());
    }

    @Test
    void aStaticServiceComesBackOnlyIfItsGroupHadBeenSwitchedOff() {
        Fixture f = new Fixture().task("lobby", TaskMode.STATIC, 1, 1);
        f.target.add("lobby", 1, TaskMode.STATIC, ServiceState.STOPPED);
        f.scaler.stateChanged(f.target.services.get(new ServiceId("lobby-1")), ServiceState.STOPPING);
        assertEquals(List.of(), f.scaler.tick(), "stopped by hand while wanted");

        f.cloud = f.cloud.withServers(WantedServers.all().with(ServerKind.LOBBY, false));
        f.scaler.stateChanged(f.target.services.get(new ServiceId("lobby-1")), ServiceState.STOPPING);
        assertEquals(List.of(), f.scaler.tick(), "switched off");
        f.cloud = f.cloud.withServers(WantedServers.all());
        assertEquals(List.of(new Start("lobby", OptionalInt.of(1), Reason.MIN_SERVICES)), f.scaler.tick());
        assertEquals(List.of("start lobby 1"), f.target.calls);
    }

    @Test
    void aTaskWithAStartUnderWayGetsNoSecondDecision() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 2, 3);
        List<Runnable> queued = new ArrayList<>();
        Autoscaler scaler = Autoscaler.create(f.target, f::snapshot, f::signals, f.clock(), queued::add);
        assertEquals(2, scaler.tick().size(), "both missing services in one round");
        assertEquals(1, queued.size(), "one action for the task, which starts them one after the other");
        f.advance(Duration.ofMinutes(5));
        assertEquals(List.of(), scaler.tick(), "the starts have not run yet");
        queued.forEach(Runnable::run);
        assertEquals(List.of("start minigame", "start minigame"), f.target.calls);
        assertEquals(List.of(), scaler.tick(), "min-services is met");
    }

    @Test
    void aMemoryHoldIsReportedAndScalesUpOnceMemoryIsFree() {
        Fixture f = new Fixture().task("minigame", TaskMode.DYNAMIC, 1, 3);
        f.target.add("minigame", 1, TaskMode.DYNAMIC, ServiceState.RUNNING);
        f.players.put(new ServiceId("minigame-1"), 50);
        f.memory = Optional.empty();
        assertEquals(List.of(new Held("minigame", Hold.MEMORY_UNKNOWN)), f.scaler.tick());
        assertEquals(List.of(), f.target.calls);
        f.memory = ROOMY;
        assertEquals(List.of(new Start("minigame", OptionalInt.empty(), Reason.LOAD)), f.scaler.tick(),
                "a hold is no decision: no cooldown");
    }

    // ---------------------------------------------------------------- fixture

    private static final class Fixture {
        final Map<String, GroupConfig> groups = new HashMap<>();
        final Map<String, TaskConfig> tasks = new HashMap<>();
        final Map<ServiceId, Integer> players = new HashMap<>();
        final FakeTarget target = new FakeTarget();
        CloudConfig cloud = CloudConfig.defaults().withServers(WantedServers.all()).withEulaAccepted(true)
                .withSetupComplete(true);
        Optional<Memory> memory = ROOMY;
        Instant now = T0;
        final Autoscaler scaler;

        Fixture() {
            for (ServerKind kind : List.of(ServerKind.LOBBY, ServerKind.MINIGAME)) {
                groups.put(kind.key(), new GroupConfig(GroupConfig.SCHEMA_VERSION, kind.templateFolder(),
                        ServerSoftware.PAPER, "26.3", JavaSelection.AUTO,
                        new HeapConfig(MemorySize.parse("256M"), MemorySize.parse("1G")), List.of(), CombatSystem.MODERN));
            }
            target.fixture = this;
            Executor direct = Runnable::run;
            scaler = Autoscaler.create(target, this::snapshot, this::signals, clock(), direct);
        }

        Fixture task(String name, TaskMode mode, int min, int max) {
            // STATIC: every service static and never scaled, as a task file of schema-version 1 migrates.
            int fixed = mode == TaskMode.STATIC ? min : 0;
            int limit = mode == TaskMode.STATIC ? min : max;
            tasks.put(name, new TaskConfig(TaskConfig.SCHEMA_VERSION, name,
                    new ScalingConfig(fixed, min, limit, 50, 80, 50, TimeSpan.parse("5m"), TimeSpan.parse("60s")), List.of()));
            return this;
        }

        ConfigSnapshot snapshot() {
            return ConfigSnapshot.of(cloud, NodesConfig.defaults(), groups, tasks);
        }

        ScalingPlanner.Signals signals() {
            return new ScalingPlanner.Signals(players, memory);
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        Clock clock() {
            return new Clock() {
                @Override
                public java.time.ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now;
                }
            };
        }
    }

    /** Records every call; a start adds a PREPARED service at the lowest free index, a stop marks it STOPPED. */
    private static final class FakeTarget implements ScalingTarget {
        final Map<ServiceId, ServiceInfo> services = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<Duration> drainTimeouts = new ArrayList<>();
        boolean failStarts;
        boolean crashEvent;
        boolean failStops;
        /** How a drain ends; only {@code EMPTY} takes the players off the service. */
        DrainResult drainResult = DrainResult.EMPTY;
        /** Whether a drain is interrupted, as on a node that is stopping. */
        boolean drainInterrupted;
        /** Runs once inside the next drain, while the service is being emptied. */
        Runnable duringDrain;
        Fixture fixture;

        ServiceInfo info(String task, int index, TaskMode mode, ServiceState state, Instant startedAt) {
            ServiceId id = new ServiceId(task + "-" + index);
            return new ServiceInfo(id, task, task, index, mode, state, 30000 + index, Path.of("x"),
                    state.hasProcess() ? OptionalLong.of(index) : OptionalLong.empty(), Optional.of(startedAt),
                    OptionalInt.empty(), 0);
        }

        void add(String task, int index, TaskMode mode, ServiceState state) {
            services.put(new ServiceId(task + "-" + index), info(task, index, mode, state, T0.minusSeconds(3600)));
        }

        void setState(String id, ServiceState state) {
            ServiceInfo old = services.get(new ServiceId(id));
            services.put(old.id(), info(old.task(), old.index(), old.mode(), state, old.startedAt().orElseThrow()));
        }

        @Override
        public List<ServiceInfo> services() {
            return List.copyOf(services.values());
        }

        @Override
        public ServiceInfo startDynamic(String task) throws ServiceException {
            calls.add("start " + task);
            if (failStarts && crashEvent) {
                fixture.scaler.stateChanged(info(task, 1, TaskMode.DYNAMIC, ServiceState.CRASHED, fixture.now),
                        ServiceState.PREPARED);
            }
            if (failStarts) {
                throw new ServiceException("download failed");
            }
            int index = 1;
            while (services.containsKey(new ServiceId(task + "-" + index))) {
                index++;
            }
            ServiceInfo info = info(task, index, TaskMode.DYNAMIC, ServiceState.PREPARED, fixture.now);
            services.put(info.id(), info);
            return info;
        }

        @Override
        public ServiceInfo start(String task, int index) throws ServiceException {
            calls.add("start " + task + " " + index);
            ServiceInfo info = info(task, index, TaskMode.STATIC, ServiceState.PREPARED, fixture.now);
            services.put(info.id(), info);
            return info;
        }

        @Override
        public ServiceInfo stop(ServiceId service) throws ServiceException {
            calls.add("stop " + service.value());
            if (failStops) {
                throw new ServiceException("stop failed");
            }
            setState(service.value(), ServiceState.STOPPED);
            return services.get(service);
        }

        @Override
        public void reopen(ServiceId service) {
            calls.add("reopen " + service.value());
        }

        @Override
        public DrainResult drain(ServiceId service, Duration timeout) throws InterruptedException {
            calls.add("drain " + service.value());
            drainTimeouts.add(timeout);
            if (duringDrain != null) {
                Runnable during = duringDrain;
                duringDrain = null;
                during.run();
            }
            if (drainInterrupted) {
                throw new InterruptedException("test");
            }
            if (drainResult == DrainResult.EMPTY) {
                fixture.players.put(service, 0);
            }
            return drainResult;
        }
    }
}
