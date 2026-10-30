package net.vectolyn.cloud.template;

import java.util.Map;

/**
 * The "Powered by VectolynCloud" line at the end of the TAB footer ({@code branding.powered-by} in
 * {@code cloud.yml}).
 *
 * <p>The TAB config of a backend template has {@code {{VECTOLYN_TAB_POWERED_BY}}} alone on a line,
 * indented like the footer's list items. Switched on, the value is the whole YAML list item
 * {@value #LINE}; switched off it is empty, which leaves a line of spaces that YAML ignores, so the
 * footer ends with the line before it and has no empty line. The value is fixed here and never
 * comes from user input.
 */
public final class PoweredByLine {

    /** Name of the placeholder. */
    public static final String PLACEHOLDER = PlaceholderRenderer.NAME_PREFIX + "TAB_POWERED_BY";

    /** The footer list item while the line is on: MiniMessage, dark gray. */
    public static final String LINE = "- \"<dark_gray>Powered by VectolynCloud\"";

    private PoweredByLine() {
    }

    /**
     * The value of {@value #PLACEHOLDER}.
     *
     * @param poweredBy {@code branding.powered-by} of {@code cloud.yml}
     * @return {@link #LINE}, or an empty string when the line is off
     */
    public static String value(boolean poweredBy) {
        return poweredBy ? LINE : "";
    }

    /**
     * The placeholder with its value.
     *
     * @param poweredBy {@code branding.powered-by} of {@code cloud.yml}
     * @return a source that knows {@value #PLACEHOLDER} only
     */
    public static PlaceholderValues placeholderValues(boolean poweredBy) {
        return PlaceholderValues.of(Map.of(PLACEHOLDER, value(poweredBy)));
    }
}
