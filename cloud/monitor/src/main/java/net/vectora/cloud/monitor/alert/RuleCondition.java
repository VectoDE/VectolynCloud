package net.vectora.cloud.monitor.alert;

import java.time.Duration;
import java.util.Objects;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.monitor.metric.Metric;

/**
 * A parsed alert expression such as {@code tps < 18 for 60s clear at 19 after 30s}.
 *
 * <ul>
 *   <li>The rule <b>fires</b> once the condition held in every sample for {@link #holdFor()}
 *       (debounce; 0 fires on the first sample). For {@link Metric#CRASHES} it fires as soon as
 *       the crashes within {@link #window()} reach the threshold.</li>
 *   <li>It <b>clears</b> once the value has been on the healthy side of {@link #clearAt()} for
 *       {@link #clearAfter()} (hysteresis plus debounce). Values between threshold and clear value
 *       keep it firing.</li>
 *   <li>A sample without the value (no status report, outdated report, proxy without tick loop)
 *       neither fires nor clears; a pending condition starts over.</li>
 * </ul>
 *
 * @param metric     what is compared
 * @param comparison operator
 * @param threshold  value as written, with its unit
 * @param holdFor    how long the condition must hold before it fires ({@code for}); zero for crashes
 * @param window     counting window of {@link Metric#CRASHES} ({@code within}); zero otherwise
 * @param clearAt    clear value as written; same as the threshold if the rule has no {@code clear at}
 * @param clearAfter how long the value must be healthy before the alert clears ({@code after});
 *                   by default the same as {@code holdFor}
 */
public record RuleCondition(Metric metric, Comparison comparison, Quantity threshold, Duration holdFor,
                            Duration window, Quantity clearAt, Duration clearAfter) {

    /** Checks the parts. */
    public RuleCondition {
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(holdFor, "holdFor");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(clearAt, "clearAt");
        Objects.requireNonNull(clearAfter, "clearAfter");
    }

    /** Whether the value meets the alert condition. */
    public boolean fires(double value) {
        return comparison.test(value, threshold.value());
    }

    /** Whether the value is healthy enough to clear a firing alert. */
    public boolean clears(double value) {
        if (fires(value)) {
            return false;
        }
        return comparison.watchesLow() ? value >= clearAt.value() : value <= clearAt.value();
    }

    /**
     * The canonical text, which {@link RuleParser#parse} reads back to an equal condition.
     *
     * @return for example {@code tps < 18 for 1m clear at 19 after 30s}
     */
    public String canonical() {
        StringBuilder text = new StringBuilder()
                .append(metric.id()).append(' ').append(comparison.symbol()).append(' ').append(threshold.text());
        if (metric.isWindowCount()) {
            text.append(" within ").append(new TimeSpan(window));
        } else if (!holdFor.isZero()) {
            text.append(" for ").append(new TimeSpan(holdFor));
        }
        if (!clearAt.equals(threshold)) {
            text.append(" clear at ").append(clearAt.text());
        }
        Duration defaultClearAfter = metric.isWindowCount() ? Duration.ZERO : holdFor;
        if (!clearAfter.equals(defaultClearAfter)) {
            text.append(" after ").append(new TimeSpan(clearAfter));
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return canonical();
    }

    /**
     * A number with its unit, as written in the rule.
     *
     * @param number the digits, for example {@code 5} or {@code 18.5}
     * @param unit   {@code ""}, {@code %}, {@code ms}, {@code KB}, {@code MB}, {@code GB} or {@code TB}
     * @param value  the value in the metric's base unit (bytes, percent, ...)
     */
    public record Quantity(String number, String unit, double value) {

        /** Checks the parts. */
        public Quantity {
            Objects.requireNonNull(number, "number");
            Objects.requireNonNull(unit, "unit");
        }

        /** Text form, for example {@code 5GB}. */
        public String text() {
            return number + unit;
        }
    }
}
