package net.vectora.cloud.node.scaling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.vectora.cloud.monitor.Monitor;
import net.vectora.cloud.monitor.metric.NodeSample;
import net.vectora.cloud.monitor.metric.ServiceSample;
import net.vectora.cloud.node.channel.NetworkPlayers;
import net.vectora.protocol.ServiceId;

/** Where the autoscaler gets player counts and the memory of the node from. */
@FunctionalInterface
public interface ScalingSignals {

    /**
     * Reads the current values.
     *
     * @return player counts per service and the memory of the node
     */
    ScalingPlanner.Signals read();

    /** {@link #reportGrace()} unless the monitor says otherwise: the default {@code stale-after}. */
    Duration DEFAULT_REPORT_GRACE = Duration.ofSeconds(60);

    /**
     * How long a running service may go without a known player count before the autoscaler warns
     * about it. A service that has just become ready has not reported yet: the bridge's first
     * report comes a few seconds later and reaches the monitor with its next sample.
     *
     * @return {@code stale-after} of {@code monitor.yml}, {@link #DEFAULT_REPORT_GRACE} if unknown
     */
    default Duration reportGrace() {
        return DEFAULT_REPORT_GRACE;
    }

    /**
     * Why a running service has no known player count, for the autoscaler's warning. Called only
     * for a service {@link #read()} left without a count.
     *
     * @param service the service
     * @return the case; without a monitor, {@link Missing.Cause#NO_SAMPLE}
     */
    default Missing whyMissing(ServiceId service) {
        return new Missing(Missing.Cause.NO_SAMPLE, Optional.empty(), reportGrace());
    }

    /**
     * Why a player count is missing.
     *
     * @param cause      which case it is
     * @param age        for {@link Cause#MONITOR_STALLED} the age of the newest sample, for
     *                   {@link Cause#STALE_REPORT} the age of the bridge's last report (empty if it
     *                   has not reported since its process started)
     * @param staleAfter {@code stale-after} of {@code monitor.yml}
     */
    record Missing(Cause cause, Optional<Duration> age, Duration staleAfter) {

        /** The cases. */
        public enum Cause {
            /** The monitor has no sample of the service: it has not sampled since it started, or is off. */
            NO_SAMPLE,
            /** The newest sample is recent, but the bridge's last report was older than {@code stale-after}. */
            STALE_REPORT,
            /** The newest sample itself is older than {@code stale-after}: the monitor has stopped sampling. */
            MONITOR_STALLED
        }

        /** Checks the arguments. */
        public Missing {
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(age, "age");
            Objects.requireNonNull(staleAfter, "staleAfter");
        }

        /**
         * The case for the log, for example {@code its bridge has not reported for 75 s}.
         *
         * @return the text
         */
        public String describe() {
            String limit = " (stale-after " + staleAfter.toSeconds() + " s of monitor.yml)";
            return switch (cause) {
                case NO_SAMPLE -> "the monitor has no sample of it yet (enabled: false in monitor.yml switches sampling off)";
                case STALE_REPORT -> age.map(a -> "its bridge has not reported for " + a.toSeconds() + " s" + limit)
                        .orElse("its bridge has not reported since the server started" + limit);
                case MONITOR_STALLED -> "the monitor's newest sample is " + age.map(a -> a.toSeconds() + " s").orElse("too")
                        + " old" + limit + ", so the monitor has stopped sampling; look for \"Monitor sample failed\""
                        + " in the cloud log";
            };
        }
    }

    /**
     * The newest report of a service's bridge.
     *
     * @param service the service
     * @param sample  its newest monitor sample
     */
    record Report(ServiceId service, ServiceSample sample) {
    }

    /**
     * The signals of a running node, see {@link #merge}.
     *
     * @param monitor  the monitor of the node
     * @param players  the players the proxies report
     * @param watched  whether a proxy that lists the backend is connected right now
     * @param clock    the clock the monitor samples with
     * @return the signals
     */
    static ScalingSignals of(Monitor monitor, NetworkPlayers players, Predicate<ServiceId> watched, Clock clock) {
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(watched, "watched");
        Objects.requireNonNull(clock, "clock");
        long nodeHeap = Runtime.getRuntime().maxMemory() == Long.MAX_VALUE ? 0 : Runtime.getRuntime().maxMemory();
        return new ScalingSignals() {
            @Override
            public ScalingPlanner.Signals read() {
                Map<ServiceId, Integer> fromProxies = new HashMap<>();
                for (NetworkPlayers.Player player : players.online()) {
                    fromProxies.merge(player.service(), 1, Integer::sum);
                }
                List<Report> reports = monitor.services().stream()
                        .filter(view -> view.latest().isPresent())
                        .map(view -> new Report(view.service().id(), view.latest().get()))
                        .toList();
                return merge(reports, fromProxies, watched, monitor.node(), clock.instant(),
                        monitor.config().staleAfter().duration(), nodeHeap);
            }

            @Override
            public Duration reportGrace() {
                return monitor.config().staleAfter().duration();
            }

            @Override
            public Missing whyMissing(ServiceId service) {
                return missing(monitor.latest(service), monitor.lastStatusReport(service), clock.instant(),
                        monitor.config().staleAfter().duration());
            }
        };
    }

    /**
     * Tells the cases of a missing player count apart, see {@link Missing.Cause}.
     *
     * @param latest     the newest monitor sample of the service
     * @param lastReport when its bridge last reported
     * @param now        now
     * @param staleAfter {@code stale-after} of {@code monitor.yml}
     * @return the case
     */
    static Missing missing(Optional<ServiceSample> latest, Optional<Instant> lastReport, Instant now,
                           Duration staleAfter) {
        if (latest.isEmpty()) {
            return new Missing(Missing.Cause.NO_SAMPLE, Optional.empty(), staleAfter);
        }
        Duration sampleAge = Duration.between(latest.get().at(), now);
        if (sampleAge.compareTo(staleAfter) > 0) {
            return new Missing(Missing.Cause.MONITOR_STALLED, Optional.of(sampleAge), staleAfter);
        }
        return new Missing(Missing.Cause.STALE_REPORT, lastReport.map(at -> Duration.between(at, now)), staleAfter);
    }

    /**
     * Combines the reports into the signals the planner uses.
     * <ul>
     *   <li>A bridge report older than {@code staleAfter} is not used: the service has no known
     *       count, so a server that stopped reporting neither scales up nor counts as empty.</li>
     *   <li>A count is at most the player limit the server itself reports.</li>
     *   <li>While a proxy that lists the backend is connected, the count is the lower one of the
     *       bridge and the proxy: a backend that reports more players than the proxy sees on it
     *       cannot start servers. Without such a proxy, the bridge's count is used.</li>
     *   <li>For "is it empty" ({@code present}) the higher one counts instead: a server is only
     *       empty if neither the bridge nor the proxy sees a player on it.</li>
     *   <li>Memory: the newest node sample, unless it is older than {@code staleAfter} or a value
     *       is not measured.</li>
     * </ul>
     *
     * @param reports     newest sample of each service
     * @param fromProxies players the proxies report on each backend
     * @param watched     whether a proxy listing the backend is connected
     * @param node        newest node sample
     * @param now         now
     * @param staleAfter  {@code stale-after} of {@code monitor.yml}
     * @param nodeHeap    maximum heap of the node's JVM
     * @return the signals
     */
    static ScalingPlanner.Signals merge(List<Report> reports, Map<ServiceId, Integer> fromProxies,
                                       Predicate<ServiceId> watched, Optional<NodeSample> node, Instant now,
                                       Duration staleAfter, long nodeHeap) {
        Map<ServiceId, Integer> counts = new HashMap<>();
        Map<ServiceId, Integer> present = new HashMap<>();
        for (Report report : reports) {
            ServiceSample sample = report.sample();
            if (sample.playersOnline() < 0 || sample.at().plus(staleAfter).isBefore(now)) {
                continue;
            }
            int count = sample.maxPlayers() >= 0 ? Math.min(sample.playersOnline(), sample.maxPlayers())
                    : sample.playersOnline();
            int high = count;
            if (watched.test(report.service())) {
                int proxy = fromProxies.getOrDefault(report.service(), 0);
                count = Math.min(count, proxy);
                // Whoever the bridge or the proxy sees counts for "is it empty", even right after
                // a join the bridge has not reported yet.
                high = Math.max(sample.playersOnline(), proxy);
            }
            counts.put(report.service(), count);
            present.put(report.service(), high);
        }
        Optional<ScalingPlanner.Memory> memory = node
                .filter(n -> n.memoryUsed() != NodeSample.UNKNOWN && n.memoryTotal() != NodeSample.UNKNOWN)
                .filter(n -> !n.at().plus(staleAfter).isBefore(now))
                .map(n -> new ScalingPlanner.Memory(n.memoryUsed(), n.memoryTotal(), nodeHeap));
        return new ScalingPlanner.Signals(counts, present, memory);
    }
}
