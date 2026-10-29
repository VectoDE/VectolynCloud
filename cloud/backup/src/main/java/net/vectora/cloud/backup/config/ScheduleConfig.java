package net.vectora.cloud.backup.config;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import net.vectora.cloud.config.model.TimeSpan;

/**
 * When automatic backups run. With {@code enabled: true} exactly one of {@code cron} and
 * {@code interval} must be set.
 *
 * @param enabled whether backups run automatically
 * @param cron five-field cron expression in the node's time zone, for example {@code 0 4 * * *}
 * @param interval fixed distance between two scheduled backups, 5m to 7 days
 */
@JsonPropertyOrder({"enabled", "cron", "interval"})
public record ScheduleConfig(boolean enabled, String cron, TimeSpan interval) {

    /** No automatic backups. */
    public static ScheduleConfig disabled() {
        return new ScheduleConfig(false, null, null);
    }

    /** At the times of a cron expression. */
    public static ScheduleConfig cron(String expression) {
        return new ScheduleConfig(true, expression, null);
    }

    /** Every {@code interval}. */
    public static ScheduleConfig every(TimeSpan interval) {
        return new ScheduleConfig(true, null, interval);
    }
}
