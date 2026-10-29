package net.vectora.cloud.backup.config;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Which backups survive a cleanup. A backup is kept if any rule keeps it:
 *
 * <ul>
 *   <li>{@code keep-last}: the newest n backups (at least 1, so the newest is never deleted);</li>
 *   <li>{@code keep-daily}: the newest backup of each of the last n days that have one;</li>
 *   <li>{@code keep-weekly}: the same per ISO week;</li>
 *   <li>{@code keep-monthly}: the same per calendar month.</li>
 * </ul>
 *
 * Days, weeks and months are counted in the node's time zone. Only backups made by hand or by the
 * schedule are cleaned up; the automatic ones before a restore or a Minecraft version change stay
 * until they are deleted by hand.
 *
 * @param keepLast newest backups to keep, 1 to {@link #MAX}
 * @param keepDaily days to keep one backup of, 0 to {@link #MAX}
 * @param keepWeekly weeks to keep one backup of, 0 to {@link #MAX}
 * @param keepMonthly months to keep one backup of, 0 to {@link #MAX}
 */
@JsonPropertyOrder({"keep-last", "keep-daily", "keep-weekly", "keep-monthly"})
public record RetentionConfig(Integer keepLast, Integer keepDaily, Integer keepWeekly, Integer keepMonthly) {

    /** Upper limit of each rule. */
    public static final int MAX = 1000;

    /** Missing rules: keep-last 7, the others 0. */
    public RetentionConfig {
        keepLast = keepLast == null ? 7 : keepLast;
        keepDaily = keepDaily == null ? 0 : keepDaily;
        keepWeekly = keepWeekly == null ? 0 : keepWeekly;
        keepMonthly = keepMonthly == null ? 0 : keepMonthly;
    }

    /** Last 7, daily 7, weekly 4, monthly 6. */
    public static RetentionConfig defaults() {
        return new RetentionConfig(7, 7, 4, 6);
    }
}
