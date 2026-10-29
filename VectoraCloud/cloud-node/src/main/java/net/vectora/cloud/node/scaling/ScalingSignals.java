package net.vectora.cloud.node.scaling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.vectora.cloud.monitor.Monitor;
import net.vectora.cloud.monitor.ServiceView;
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

    /**
     * The signals of a running node:
     * <ul>
     *   <li>players: the newest sample of the monitor, that is {@code ServiceStatus.playersOnline}
     *       from the service's bridge, missing once the report is older than {@code stale-after};
     *       and the players the proxies report on that service ({@code PlayerJoin},
     *       {@code PlayerSwitch}), whichever is higher. A count the proxies report alone is a lower
     *       bound: enough to scale up, never enough to call a service empty;</li>
     *   <li>memory: the newest node sample of the monitor ({@code node-memory}).</li>
     * </ul>
     * A sample older than {@code stale-after} of {@code monitor.yml} is ignored, so a monitor that
     * stopped sampling cannot keep an old count alive. With the monitor switched off there are no
     * samples: the scaler then keeps {@code min-services} and does not scale.
     *
     * @param monitor the monitor of the node
     * @param players the players the proxies report
     * @param clock   the clock the monitor samples with
     * @return the signals
     */
    static ScalingSignals of(Monitor monitor, NetworkPlayers players, Clock clock) {
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(clock, "clock");
        return () -> {
            Instant now = clock.instant();
            Duration maxAge = monitor.config().staleAfter().duration();
            Map<ServiceId, Integer> counts = new HashMap<>();
            Map<ServiceId, Integer> fromProxies = new HashMap<>();
            for (NetworkPlayers.Player player : players.online()) {
                fromProxies.merge(player.service(), 1, Integer::sum);
            }
            for (ServiceView view : monitor.services()) {
                Optional<ServiceSample> latest = view.latest();
                if (latest.isPresent() && latest.get().playersOnline() >= 0
                        && !latest.get().at().plus(maxAge).isBefore(now)) {
                    counts.put(view.service().id(), latest.get().playersOnline());
                }
            }
            fromProxies.forEach((id, count) -> counts.merge(id, count, Math::max));
            Optional<ScalingPlanner.Memory> memory = monitor.node()
                    .filter(n -> n.memoryUsed() != NodeSample.UNKNOWN && n.memoryTotal() != NodeSample.UNKNOWN)
                    .filter(n -> !n.at().plus(maxAge).isBefore(now))
                    .map(n -> new ScalingPlanner.Memory(n.memoryUsed(), n.memoryTotal()));
            return new ScalingPlanner.Signals(counts, memory);
        };
    }
}
