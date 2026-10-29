package net.vectora.cloud.setup;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.validation.ConfigValidator;
import net.vectora.cloud.console.Text;

/**
 * How many services of one server the assistant sets up: {@code static-services},
 * {@code min-services} and {@code max-services} of its task, written {@code static/min/max} on the
 * console, for example {@code 1/1/4}.
 *
 * @param staticServices static services: keep their folder, always run, never stopped by the scaler
 * @param minServices    services that always run
 * @param maxServices    upper limit; the services above {@code staticServices} are dynamic
 */
public record ServiceCounts(int staticServices, int minServices, int maxServices) {

    private static final Pattern TEXT = Pattern.compile("\\s*(\\d{1,4})\\s*/\\s*(\\d{1,4})\\s*/\\s*(\\d{1,4})\\s*");

    /** The counts of an existing task. */
    public static ServiceCounts of(ScalingConfig scaling) {
        return new ServiceCounts(scaling.staticServices(), scaling.minServices(), scaling.maxServices());
    }

    /**
     * Parses {@code static/min/max}.
     *
     * @param text for example {@code 1/1/4}
     * @return the counts, or null if the text has another form
     */
    public static ServiceCounts parse(String text) {
        Matcher m = TEXT.matcher(text == null ? "" : text);
        if (!m.matches()) {
            return null;
        }
        int staticServices = count(m.group(1));
        int minServices = count(m.group(2));
        int maxServices = count(m.group(3));
        if (staticServices < 0 || minServices < 0 || maxServices < 0) {
            return null;
        }
        return new ServiceCounts(staticServices, minServices, maxServices);
    }

    /** One count of 1 to 4 ASCII digits, or -1 if it is no such number. */
    private static int count(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The rule the task file has: {@code 0 <= static <= min <= max}, {@code 1 <= max <= 1000}.
     *
     * @return the problem, or null if the counts are valid
     */
    public Text problem() {
        boolean valid = staticServices >= 0 && staticServices <= minServices && minServices <= maxServices
                && maxServices >= 1 && maxServices <= ConfigValidator.MAX_SERVICES;
        return valid ? null : SetupMessages.CHECK_SERVICES;
    }

    /** The scaling of an existing task with these counts. */
    public ScalingConfig applyTo(ScalingConfig scaling) {
        return scaling.withCounts(staticServices, minServices, maxServices);
    }

    /** {@code static/min/max}. */
    @Override
    public String toString() {
        return staticServices + "/" + minServices + "/" + maxServices;
    }
}
