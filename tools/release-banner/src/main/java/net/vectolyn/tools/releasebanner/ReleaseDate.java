package net.vectolyn.tools.releasebanner;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.regex.Pattern;

/** The release date: read as ISO {@code yyyy-MM-dd}, shown in English as {@code Oct 30, 2026}. */
public final class ReleaseDate {

    private static final Pattern ISO = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final DateTimeFormatter PARSE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    // Fixed English month names; never the JVM's default locale.
    private static final String[] MONTHS =
            {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private ReleaseDate() {
    }

    /**
     * Parses {@code yyyy-MM-dd}.
     *
     * @throws IllegalArgumentException for any other form or a date that does not exist
     */
    public static LocalDate parse(String iso) {
        if (iso == null || !ISO.matcher(iso).matches()) {
            throw new IllegalArgumentException("release date must be yyyy-MM-dd: "
                    + (iso == null ? "missing" : Text.printable(iso)));
        }
        try {
            return LocalDate.parse(iso, PARSE);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("release date does not exist: " + iso, e);
        }
    }

    /** {@code 2026-10-30} becomes {@code Oct 30, 2026}. */
    public static String format(LocalDate date) {
        return MONTHS[date.getMonthValue() - 1] + " " + date.getDayOfMonth() + ", " + date.getYear();
    }
}
