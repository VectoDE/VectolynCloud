package net.vectolyn.cloud.monitor.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Predicate;
import net.vectolyn.cloud.monitor.metric.Metric;
import net.vectolyn.cloud.monitor.metric.RingBuffer;

/**
 * Evaluates the alert rules sample by sample and keeps the alerts. One state per rule and subject
 * (service id or {@value Alert#NODE}):
 *
 * <pre>
 * OK ──condition true──▶ PENDING ──held for "for"──▶ FIRING ──healthy (clear at)──▶ CLEARING ──held for "after"──▶ OK
 *  ▲                        │                          ▲                                │
 *  └──condition false───────┘                          └────── not healthy any more ────┘
 * </pre>
 *
 * A sample without the value leaves FIRING as it is (missing data never clears an alert) and
 * returns PENDING to OK (missing data never fires one). Thread-safe; every method returns the
 * events it caused, in order, for the caller to hand to its listeners.
 */
public final class AlertEngine {

    /** Resolved alerts kept for {@code alerts list}. */
    public static final int KEEP_RESOLVED = 50;

    /** Values of one subject for one evaluation. */
    @FunctionalInterface
    public interface Values {
        /**
         * Returns the current value of a metric.
         *
         * @param metric the metric of a rule
         * @param window the rule's window, for {@link Metric#CRASHES}; zero for other metrics
         * @return the value, or empty if it was not measured
         */
        OptionalDouble value(Metric metric, Duration window);
    }

    private record Key(String rule, String subject) {
    }

    private static final class Track {
        Instant conditionSince;
        Instant healthySince;
        Alert firing;
    }

    private final Map<String, AlertRule> rules = new LinkedHashMap<>();
    private final Map<Key, Track> tracks = new HashMap<>();
    private final RingBuffer<Alert> resolved = new RingBuffer<>(KEEP_RESOLVED);
    private int nextId = 1;

    /**
     * Creates the engine.
     *
     * @param rules the rules; names must be unique
     */
    public AlertEngine(List<AlertRule> rules) {
        setRules(rules);
    }

    private void setRules(List<AlertRule> list) {
        rules.clear();
        for (AlertRule rule : list) {
            if (rules.putIfAbsent(rule.name(), rule) != null) {
                throw new IllegalArgumentException("two rules are named " + rule.name());
            }
        }
    }

    /** The rules, in configuration order. */
    public synchronized List<AlertRule> rules() {
        return List.copyOf(rules.values());
    }

    /**
     * Evaluates every rule that applies to a service.
     *
     * @param now     time of the sample
     * @param service service id
     * @param group   group of the service, for the rules' group filter
     * @param values  the service's values
     * @return events caused
     */
    public synchronized List<AlertEvent> evaluateService(Instant now, String service, String group, Values values) {
        Objects.requireNonNull(group, "group");
        List<AlertEvent> events = new ArrayList<>();
        for (AlertRule rule : rules.values()) {
            if (!rule.isNodeRule() && rule.appliesToGroup(group)) {
                evaluate(now, rule, service, values, events);
            }
        }
        return events;
    }

    /**
     * Evaluates every node rule.
     *
     * @param now    time of the sample
     * @param values the node's values
     * @return events caused
     */
    public synchronized List<AlertEvent> evaluateNode(Instant now, Values values) {
        List<AlertEvent> events = new ArrayList<>();
        for (AlertRule rule : rules.values()) {
            if (rule.isNodeRule()) {
                evaluate(now, rule, Alert.NODE, values, events);
            }
        }
        return events;
    }

    private void evaluate(Instant now, AlertRule rule, String subject, Values values, List<AlertEvent> events) {
        RuleCondition condition = rule.condition();
        OptionalDouble measured = values.value(condition.metric(), condition.window());
        Key key = new Key(rule.name(), subject);
        Track track = tracks.get(key);
        if (measured.isEmpty()) {
            if (track != null) {
                if (track.firing == null) {
                    tracks.remove(key);
                } else {
                    track.healthySince = null;
                }
            }
            return;
        }
        double value = measured.getAsDouble();
        if (track == null || track.firing == null) {
            if (!condition.fires(value)) {
                tracks.remove(key);
                return;
            }
            if (track == null) {
                track = new Track();
                tracks.put(key, track);
            }
            if (track.conditionSince == null) {
                track.conditionSince = now;
            }
            if (!elapsed(track.conditionSince, now).minus(condition.holdFor()).isNegative()) {
                track.firing = new Alert(nextId++, rule.name(), subject, rule.severity(), condition, value, now,
                        Optional.empty(), Optional.empty(), Optional.empty());
                track.healthySince = null;
                events.add(new AlertEvent.Fired(track.firing));
            }
            return;
        }
        if (!condition.clears(value)) {
            track.healthySince = null;
            return;
        }
        if (track.healthySince == null) {
            track.healthySince = now;
        }
        if (!elapsed(track.healthySince, now).minus(condition.clearAfter()).isNegative()) {
            Alert done = track.firing.resolve(now, Alert.Resolution.CLEARED);
            tracks.remove(key);
            resolved.add(done);
            events.add(new AlertEvent.Resolved(done));
        }
    }

    private static Duration elapsed(Instant from, Instant to) {
        Duration d = Duration.between(from, to);
        return d.isNegative() ? Duration.ZERO : d;
    }

    /**
     * Ends the alerts and pending conditions of services that are gone or no longer running.
     * Node rules are never affected.
     *
     * @param now     the time
     * @param present whether a subject still counts
     * @return the {@link AlertEvent.Resolved} events
     */
    public synchronized List<AlertEvent> retainServices(Instant now, Predicate<String> present) {
        List<AlertEvent> events = new ArrayList<>();
        Iterator<Map.Entry<Key, Track>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Track> entry = it.next();
            String subject = entry.getKey().subject();
            if (subject.equals(Alert.NODE) || present.test(subject)) {
                continue;
            }
            it.remove();
            Alert firing = entry.getValue().firing;
            if (firing != null) {
                Alert done = firing.resolve(now, Alert.Resolution.SERVICE_GONE);
                resolved.add(done);
                events.add(new AlertEvent.Resolved(done));
            }
        }
        return events;
    }

    /**
     * Ends the alerts and pending conditions of one rule for subjects it no longer applies to,
     * for example a service that left {@code RUNNING} while a gauge rule watched it.
     *
     * @param now     the time
     * @param subject service id
     * @param keep    whether the alert of a rule may stay
     * @return the {@link AlertEvent.Resolved} events
     */
    public synchronized List<AlertEvent> endFor(Instant now, String subject, Predicate<AlertRule> keep) {
        List<AlertEvent> events = new ArrayList<>();
        Iterator<Map.Entry<Key, Track>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Track> entry = it.next();
            if (!entry.getKey().subject().equals(subject)) {
                continue;
            }
            AlertRule rule = rules.get(entry.getKey().rule());
            if (rule != null && keep.test(rule)) {
                continue;
            }
            it.remove();
            Alert firing = entry.getValue().firing;
            if (firing != null) {
                Alert done = firing.resolve(now, Alert.Resolution.SERVICE_GONE);
                resolved.add(done);
                events.add(new AlertEvent.Resolved(done));
            }
        }
        return events;
    }

    /**
     * Replaces the rules, for example after {@code monitor.yml} changed. Alerts of a rule that is
     * gone or whose condition, severity or groups changed end with {@link Alert.Resolution#RULE_REMOVED};
     * unchanged rules keep their state.
     *
     * @param now   the time
     * @param fresh the new rules
     * @return the {@link AlertEvent.Resolved} events
     */
    public synchronized List<AlertEvent> replaceRules(Instant now, List<AlertRule> fresh) {
        Map<String, AlertRule> before = new LinkedHashMap<>(rules);
        setRules(fresh);
        List<AlertEvent> events = new ArrayList<>();
        Iterator<Map.Entry<Key, Track>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Track> entry = it.next();
            String name = entry.getKey().rule();
            if (Objects.equals(before.get(name), rules.get(name))) {
                continue;
            }
            it.remove();
            Alert firing = entry.getValue().firing;
            if (firing != null) {
                Alert done = firing.resolve(now, Alert.Resolution.RULE_REMOVED);
                resolved.add(done);
                events.add(new AlertEvent.Resolved(done));
            }
        }
        return events;
    }

    /** Alerts that fire now, oldest first. */
    public synchronized List<Alert> active() {
        return tracks.values().stream().map(t -> t.firing).filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(Alert::id)).toList();
    }

    /** The last {@value #KEEP_RESOLVED} resolved alerts, oldest first. */
    public synchronized List<Alert> resolved() {
        return resolved.snapshot();
    }

    /**
     * Acknowledges an active alert.
     *
     * @param id  alert number
     * @param now the time
     * @return the event, or empty if no active alert has the number or it was acknowledged before
     */
    public synchronized Optional<AlertEvent> acknowledge(int id, Instant now) {
        for (Track track : tracks.values()) {
            if (track.firing != null && track.firing.id() == id) {
                if (track.firing.acknowledged()) {
                    return Optional.empty();
                }
                track.firing = track.firing.acknowledge(now);
                return Optional.of(new AlertEvent.Acknowledged(track.firing));
            }
        }
        return Optional.empty();
    }

    /**
     * Acknowledges every active alert that is not acknowledged yet.
     *
     * @param now the time
     * @return one event per alert
     */
    public synchronized List<AlertEvent> acknowledgeAll(Instant now) {
        List<AlertEvent> events = new ArrayList<>();
        for (Alert alert : active()) {
            acknowledge(alert.id(), now).ifPresent(events::add);
        }
        return events;
    }
}
