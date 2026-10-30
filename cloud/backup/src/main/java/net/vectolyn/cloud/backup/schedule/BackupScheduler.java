package net.vectolyn.cloud.backup.schedule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.vectolyn.cloud.backup.BackupException;
import net.vectolyn.cloud.backup.BackupInfo;
import net.vectolyn.cloud.backup.BackupReason;
import net.vectolyn.cloud.backup.BackupRequest;
import net.vectolyn.cloud.backup.BackupService;
import net.vectolyn.cloud.backup.config.BackupConfig;
import net.vectolyn.cloud.backup.config.ScheduleConfig;

/**
 * Runs scheduled backups and the retention cleanup after each of them. cloud-node starts it once
 * the services run and closes it before it stops them.
 *
 * <p>Every {@value #TICK_SECONDS} seconds it reads {@code backup.yml} again and computes the next
 * run, so a changed schedule applies without a restart:
 *
 * <ul>
 *   <li>{@code cron}: the next matching minute in the node's time zone.</li>
 *   <li>{@code interval}: the newest scheduled backup plus the interval; without one, one interval
 *       after the start. A run missed while the node was down happens at the next tick.</li>
 * </ul>
 *
 * A failed run is logged and tried again at the next scheduled time. A broken {@code backup.yml}
 * is logged once and pauses the schedule until it is fixed.
 */
public final class BackupScheduler implements AutoCloseable {

    /** Seconds between two checks. */
    public static final long TICK_SECONDS = 30;

    private static final System.Logger LOG = System.getLogger(BackupScheduler.class.getName());

    private final BackupService service;
    private final ScheduledExecutorService executor;
    private final Instant started;
    private Instant next;
    private ScheduleConfig planned;
    private String lastProblem;

    /**
     * Creates the scheduler; nothing runs before {@link #start()}.
     *
     * @param service the backup service
     */
    public BackupScheduler(BackupService service) {
        this.service = Objects.requireNonNull(service, "service");
        this.started = service.clock().instant();
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "vectolyn-backup-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts checking. */
    public void start() {
        executor.scheduleWithFixedDelay(this::tickSafely, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /** When the next scheduled backup runs; empty if none is planned. */
    public synchronized Optional<Instant> nextRun() {
        return Optional.ofNullable(next);
    }

    /**
     * The next run after {@code now}.
     *
     * @param schedule the schedule
     * @param now the current time
     * @param lastScheduled the newest scheduled backup, if any
     * @param startedAt when the scheduler started
     * @param zone time zone of cron expressions
     * @return the next run; empty if the schedule is off
     */
    public static Optional<Instant> nextRun(ScheduleConfig schedule, Instant now, Optional<Instant> lastScheduled,
                                            Instant startedAt, ZoneId zone) {
        if (!schedule.enabled()) {
            return Optional.empty();
        }
        if (schedule.cron() != null) {
            return CronExpression.parse(schedule.cron()).next(ZonedDateTime.ofInstant(now, zone))
                    .map(ZonedDateTime::toInstant);
        }
        if (schedule.interval() == null) {
            return Optional.empty();
        }
        Instant base = lastScheduled.orElse(startedAt);
        return Optional.of(base.plus(schedule.interval().duration()));
    }

    /** One check; package-private so tests can drive it without waiting. */
    synchronized void tick() throws InterruptedException {
        BackupConfig settings;
        try {
            settings = service.settings();
        } catch (BackupException e) {
            if (!e.getMessage().equals(lastProblem)) {
                LOG.log(System.Logger.Level.WARNING, "Backup schedule paused: {0}", e.getMessage());
                lastProblem = e.getMessage();
            }
            next = null;
            planned = null;
            return;
        }
        lastProblem = null;
        Instant now = service.clock().instant();
        if (!settings.schedule().equals(planned) || next == null) {
            planned = settings.schedule();
            next = nextRun(planned, now, lastScheduled(), started, service.zone()).orElse(null);
        }
        if (next == null || now.isBefore(next)) {
            return;
        }
        try {
            BackupInfo info = service.create(BackupRequest.all(BackupReason.SCHEDULED));
            info.warnings().forEach(w -> LOG.log(System.Logger.Level.WARNING, "Backup {0}: {1}", info.id(), w));
            service.prune();
        } catch (BackupException e) {
            LOG.log(System.Logger.Level.WARNING, "Scheduled backup failed: {0}", e.getMessage());
        }
        Instant after = service.clock().instant();
        next = nextRun(planned, after, Optional.of(now), started, service.zone()).orElse(null);
    }

    private Optional<Instant> lastScheduled() {
        try {
            for (BackupInfo info : service.list()) {
                if (info.manifest().isPresent() && info.manifest().get().reasonType() == BackupReason.SCHEDULED) {
                    return Optional.of(info.manifest().get().created());
                }
            }
        } catch (BackupException e) {
            LOG.log(System.Logger.Level.DEBUG, "Cannot list backups: {0}", e.getMessage());
        }
        return Optional.empty();
    }

    private void tickSafely() {
        try {
            tick();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Backup scheduler failed", e);
        }
    }

    /** Stops the checks; a running backup is interrupted. */
    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
