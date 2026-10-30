package net.vectolyn.cloud.backup.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * A classic five-field cron expression: {@code minute hour day-of-month month day-of-week}.
 *
 * <ul>
 *   <li>Each field takes {@code *}, a number, a range {@code a-b}, a step {@code *}{@code /n} or
 *       {@code a-b/n}, and lists of these separated by {@code ,}.</li>
 *   <li>Ranges: minute 0-59, hour 0-23, day 1-31, month 1-12, weekday 0-7 (0 and 7 are Sunday).
 *       Names such as {@code MON} or {@code JAN} and the extensions {@code L}, {@code W},
 *       {@code #}, {@code ?} and {@code @daily} are not supported.</li>
 *   <li>As in Vixie cron: if both day-of-month and day-of-week are restricted (not {@code *}), a
 *       day matches when either matches.</li>
 *   <li>Times are local to the zone passed to {@link #next}. A time skipped by a daylight saving
 *       change is not run that day; a repeated hour runs once.</li>
 * </ul>
 *
 * <p>Examples: {@code 0 4 * * *} daily at 04:00; {@code 30 3 * * 0} Sundays at 03:30;
 * {@code 0 *}{@code /6 * * *} every six hours.
 */
public final class CronExpression {

    /** Describes the format, for error messages. */
    public static final String FORMAT = "five fields: minute hour day-of-month month day-of-week, for example 0 4 * * *";

    private static final int MAX_LENGTH = 200;
    /** Upper bound of the search; every valid expression fires within this many days (Feb 29 within 8 years). */
    private static final int SEARCH_DAYS = 366 * 9;

    private final String text;
    private final long minutes;
    private final long hours;
    private final long days;
    private final long months;
    private final long weekdays;
    private final boolean dayRestricted;
    private final boolean weekdayRestricted;

    private CronExpression(String text, long minutes, long hours, long days, long months, long weekdays,
                           boolean dayRestricted, boolean weekdayRestricted) {
        this.text = text;
        this.minutes = minutes;
        this.hours = hours;
        this.days = days;
        this.months = months;
        this.weekdays = weekdays;
        this.dayRestricted = dayRestricted;
        this.weekdayRestricted = weekdayRestricted;
    }

    /**
     * Parses an expression.
     *
     * @param text the expression
     * @return the parsed expression
     * @throws IllegalArgumentException if it is not valid; the message does not quote the text
     */
    public static CronExpression parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("cron expression too long");
        }
        String[] fields = text.strip().split("[ \\t]+");
        if (fields.length != 5 || fields[0].isEmpty()) {
            throw new IllegalArgumentException("cron expression needs " + FORMAT);
        }
        long minutes = field(fields[0], 0, 59, "minute");
        long hours = field(fields[1], 0, 23, "hour");
        long days = field(fields[2], 1, 31, "day-of-month");
        long months = field(fields[3], 1, 12, "month");
        long weekdays = field(fields[4], 0, 7, "day-of-week");
        if ((weekdays & 1L << 7) != 0) {
            weekdays |= 1L;
            weekdays &= ~(1L << 7);
        }
        CronExpression cron = new CronExpression(String.join(" ", fields), minutes, hours, days, months, weekdays,
                !fields[2].equals("*"), !fields[4].equals("*"));
        if (!cron.anyDayPossible()) {
            throw new IllegalArgumentException("cron expression never matches a real date");
        }
        return cron;
    }

    /**
     * The first matching minute strictly after {@code after}.
     *
     * @param after the start
     * @return the next time, or empty if none within nine years (cannot happen for a parsed expression)
     */
    public Optional<ZonedDateTime> next(ZonedDateTime after) {
        LocalDateTime start = after.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        LocalDate day = start.toLocalDate();
        for (int i = 0; i < SEARCH_DAYS; i++, day = day.plusDays(1)) {
            if (!matchesDay(day)) {
                continue;
            }
            int fromMinuteOfDay = day.equals(start.toLocalDate()) ? start.getHour() * 60 + start.getMinute() : 0;
            for (int hour = fromMinuteOfDay / 60; hour < 24; hour++) {
                if ((hours & 1L << hour) == 0) {
                    continue;
                }
                int firstMinute = hour == fromMinuteOfDay / 60 ? fromMinuteOfDay % 60 : 0;
                for (int minute = firstMinute; minute < 60; minute++) {
                    if ((minutes & 1L << minute) == 0) {
                        continue;
                    }
                    LocalDateTime local = day.atTime(hour, minute);
                    ZonedDateTime zoned = ZonedDateTime.ofLocal(local, after.getZone(), after.getOffset());
                    // A local time in a daylight-saving gap does not exist; skip it.
                    if (zoned.toLocalDateTime().equals(local) && zoned.isAfter(after)) {
                        return Optional.of(zoned);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean matchesDay(LocalDate day) {
        if ((months & 1L << day.getMonthValue()) == 0) {
            return false;
        }
        boolean dayMatch = (days & 1L << day.getDayOfMonth()) != 0;
        int weekday = day.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : day.getDayOfWeek().getValue();
        boolean weekdayMatch = (weekdays & 1L << weekday) != 0;
        if (dayRestricted && weekdayRestricted) {
            return dayMatch || weekdayMatch;
        }
        return dayMatch && weekdayMatch;
    }

    /** Whether some month has one of the days (rules out {@code 0 0 31 2 *}). */
    private boolean anyDayPossible() {
        if (weekdayRestricted && dayRestricted) {
            return true;
        }
        if (!dayRestricted) {
            return true;
        }
        int[] longest = {0, 31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        for (int month = 1; month <= 12; month++) {
            if ((months & 1L << month) == 0) {
                continue;
            }
            for (int day = 1; day <= longest[month]; day++) {
                if ((days & 1L << day) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long field(String text, int min, int max, String name) {
        if (text.isEmpty() || text.length() > 100) {
            throw new IllegalArgumentException("invalid cron " + name + " field");
        }
        long bits = 0;
        for (String part : text.split(",", -1)) {
            bits |= part(part, min, max, name);
        }
        return bits;
    }

    private static long part(String part, int min, int max, String name) {
        int step = 1;
        String range = part;
        int slash = part.indexOf('/');
        if (slash >= 0) {
            range = part.substring(0, slash);
            step = number(part.substring(slash + 1), 1, max - min + 1, name);
        }
        int from;
        int to;
        if (range.equals("*")) {
            from = min;
            to = max;
        } else {
            int dash = range.indexOf('-');
            if (dash >= 0) {
                from = number(range.substring(0, dash), min, max, name);
                to = number(range.substring(dash + 1), min, max, name);
                if (from > to) {
                    throw new IllegalArgumentException("cron " + name + " range must go upwards");
                }
            } else {
                from = number(range, min, max, name);
                to = slash >= 0 ? max : from;
            }
        }
        long bits = 0;
        for (int value = from; value <= to; value += step) {
            bits |= 1L << value;
        }
        return bits;
    }

    private static int number(String text, int min, int max, String name) {
        if (text.isEmpty() || text.length() > 2 || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("invalid cron " + name + " field");
        }
        int value = Integer.parseInt(text);
        if (value < min || value > max) {
            throw new IllegalArgumentException("cron " + name + " must be between " + min + " and " + max);
        }
        return value;
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CronExpression cron && cron.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }
}
