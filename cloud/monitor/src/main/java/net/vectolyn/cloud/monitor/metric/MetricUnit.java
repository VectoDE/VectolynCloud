package net.vectolyn.cloud.monitor.metric;

import java.util.Locale;

/** Unit of a {@link Metric}, with the text forms rules and the console use. */
public enum MetricUnit {
    /** A plain number (TPS, players, counts). */
    NUMBER,
    /** Milliseconds; rules may write {@code ms} after the number. */
    MILLISECONDS,
    /** 0 to 100; rules write {@code %} after the number. */
    PERCENT,
    /** Bytes; rules write {@code KB}, {@code MB}, {@code GB} or {@code TB} (powers of 1024). */
    BYTES;

    /** Largest byte value a rule may name: 1024 TB. */
    public static final double MAX_BYTES = 1024.0 * 1024 * 1024 * 1024 * 1024;

    /**
     * Formats a value for the console, for example {@code 18.5}, {@code 91%} or {@code 4.2 GiB}.
     *
     * @param value the value; NaN gives {@code -}
     * @return the text
     */
    public String format(double value) {
        if (Double.isNaN(value)) {
            return "-";
        }
        return switch (this) {
            case NUMBER -> value == Math.rint(value) && Math.abs(value) < 1e15
                    ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
            case MILLISECONDS -> String.format(Locale.ROOT, "%.1f ms", value);
            case PERCENT -> String.format(Locale.ROOT, "%.0f%%", value);
            case BYTES -> bytes(value);
        };
    }

    /**
     * Formats a byte count with binary units: {@code 512 B}, {@code 1.5 KiB}, {@code 4.2 GiB}.
     *
     * @param bytes the count; negative or NaN gives {@code -}
     * @return the text
     */
    public static String bytes(double bytes) {
        if (Double.isNaN(bytes) || bytes < 0) {
            return "-";
        }
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};
        int unit = 0;
        double value = bytes;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? String.format(Locale.ROOT, "%.0f B", value)
                : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
