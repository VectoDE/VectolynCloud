package net.vectolyn.cloud.monitor.alert;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.vectolyn.cloud.monitor.metric.MetricUnit;

/**
 * One firing (or past) alert. Immutable; the engine replaces it when it is acknowledged or resolved.
 *
 * @param id             number, counting from 1 per run of the cloud
 * @param rule           name of the rule
 * @param subject        service id, or {@value #NODE} for node rules
 * @param severity       how urgent
 * @param condition      the rule's condition
 * @param value          value of the metric when it fired
 * @param firedAt        when it fired
 * @param acknowledgedAt when an operator acknowledged it, if so
 * @param resolvedAt     when it cleared, if so
 * @param resolution     why it ended, if so
 */
public record Alert(int id, String rule, String subject, Severity severity, RuleCondition condition, double value,
                    Instant firedAt, Optional<Instant> acknowledgedAt, Optional<Instant> resolvedAt,
                    Optional<Resolution> resolution) {

    /** Subject of node rules. */
    public static final String NODE = "node";

    /** Why an alert ended. */
    public enum Resolution {
        /** The value was healthy long enough. */
        CLEARED,
        /** The service is gone or no longer running, so the rule no longer applies. */
        SERVICE_GONE,
        /** The rule was removed or changed in {@code monitor.yml}. */
        RULE_REMOVED
    }

    /** Checks the values. */
    public Alert {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(firedAt, "firedAt");
        Objects.requireNonNull(acknowledgedAt, "acknowledgedAt");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
        Objects.requireNonNull(resolution, "resolution");
    }

    /** Whether it still fires. */
    public boolean active() {
        return resolvedAt.isEmpty();
    }

    /** Whether an operator acknowledged it. */
    public boolean acknowledged() {
        return acknowledgedAt.isPresent();
    }

    Alert acknowledge(Instant at) {
        return new Alert(id, rule, subject, severity, condition, value, firedAt, Optional.of(at), resolvedAt, resolution);
    }

    Alert resolve(Instant at, Resolution why) {
        return new Alert(id, rule, subject, severity, condition, value, firedAt, acknowledgedAt, Optional.of(at),
                Optional.of(why));
    }

    /**
     * One line for logs and notifications, for example
     * {@code #3 low-tps on lobby-1: tps 12.4 (rule tps < 18 for 1m)}. Contains only names the
     * configuration validated and numbers, never free text from a server.
     *
     * @return the line
     */
    public String summary() {
        MetricUnit unit = condition.metric().unit();
        String shown = unit == MetricUnit.NUMBER && value != Math.rint(value)
                ? String.format(Locale.ROOT, "%.1f", value) : unit.format(value);
        return "#" + id + " " + rule + " on " + subject + ": " + condition.metric().id() + " " + shown
                + " (rule " + condition.canonical() + ")";
    }
}
