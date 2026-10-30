package net.vectolyn.cloud.monitor.alert;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.vectolyn.cloud.monitor.metric.Metric;

/**
 * One alert rule of {@code monitor.yml}.
 *
 * @param name      unique name, {@code [a-z0-9][a-z0-9_-]{0,31}}
 * @param condition the parsed expression
 * @param severity  how urgent it is
 * @param groups    groups the rule applies to; empty for every group. Ignored for node metrics.
 */
public record AlertRule(String name, RuleCondition condition, Severity severity, Set<String> groups) {

    /** Pattern of rule names (the same as group and task names). */
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    /** Checks the values. */
    public AlertRule {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("rule name must match " + NAME.pattern());
        }
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(severity, "severity");
        groups = Set.copyOf(groups);
    }

    /** Shorthand that parses the expression. */
    public static AlertRule of(String name, String expression, Severity severity) {
        return new AlertRule(name, RuleParser.parse(expression), severity, Set.of());
    }

    /** Whether the rule is about the node rather than single services. */
    public boolean isNodeRule() {
        return condition.metric().scope() == Metric.Scope.NODE;
    }

    /** Whether the rule applies to a service of this group. */
    public boolean appliesToGroup(String group) {
        return groups.isEmpty() || groups.contains(group);
    }
}
