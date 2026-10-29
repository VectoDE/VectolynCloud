package net.vectora.cloud.monitor.console;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import net.vectora.cloud.monitor.metric.MetricUnit;

/**
 * Draws a history as an area chart in plain ASCII, so it reads the same in a JLine terminal, the
 * plain console, a Pterodactyl panel and a log file:
 *
 * <pre>
 *  20.0 |      ##########
 *       |  ##############
 *       |################
 *   0.0 |################
 *       +----------..----
 *        -15m         now
 * </pre>
 *
 * Each column is the average of the samples in its time slot. A slot without any value is blank
 * and marked {@code .} on the axis.
 */
public final class TextChart {

    /** One value at one time; NaN for "not measured". */
    public record Point(Instant at, double value) {
        /** Checks the time. */
        public Point {
            Objects.requireNonNull(at, "at");
        }
    }

    private TextChart() {
    }

    /**
     * Draws the chart.
     *
     * @param points  values, oldest first
     * @param unit    unit for the axis labels
     * @param floor   lowest value of the scale (usually 0)
     * @param ceiling highest value of the scale at least (for example 20 for TPS, 100 for percent);
     *                a higher value in the data raises it
     * @param width   columns, 8 to 120
     * @param height  rows, 2 to 20
     * @return the lines; empty if there is no point
     */
    public static List<String> render(List<Point> points, MetricUnit unit, double floor, double ceiling, int width,
                                      int height) {
        if (width < 8 || width > 120 || height < 2 || height > 20) {
            throw new IllegalArgumentException("width 8 to 120, height 2 to 20");
        }
        if (points.isEmpty()) {
            return List.of();
        }
        Instant first = points.get(0).at();
        Instant last = points.get(points.size() - 1).at();
        long span = Math.max(1, Duration.between(first, last).getSeconds() + 1);
        int columns = (int) Math.min(width, span);
        double[] sum = new double[columns];
        int[] count = new int[columns];
        double max = ceiling;
        for (Point p : points) {
            long offset = Math.max(0, Duration.between(first, p.at()).getSeconds());
            int column = (int) Math.min(columns - 1, offset * columns / span);
            if (Double.isFinite(p.value())) {
                sum[column] += p.value();
                count[column]++;
                max = Math.max(max, p.value());
            }
        }
        if (!(max > floor)) {
            max = floor + 1;
        }
        String top = label(unit, max);
        String bottom = label(unit, floor);
        int labelWidth = Math.max(top.length(), bottom.length());
        List<String> lines = new ArrayList<>();
        for (int row = height; row >= 1; row--) {
            StringBuilder line = new StringBuilder();
            String label = row == height ? top : row == 1 ? bottom : "";
            line.append(" ".repeat(labelWidth - label.length())).append(label).append(" |");
            for (int c = 0; c < columns; c++) {
                if (count[c] == 0) {
                    line.append(' ');
                    continue;
                }
                double average = sum[c] / count[c];
                double level = (average - floor) / (max - floor) * height;
                // Any value above the floor shows at least in the bottom row.
                boolean filled = level >= row - 0.5 || row == 1 && average > floor;
                line.append(filled ? '#' : ' ');
            }
            lines.add(stripEnd(line));
        }
        StringBuilder axis = new StringBuilder(" ".repeat(labelWidth)).append(" +");
        for (int c = 0; c < columns; c++) {
            axis.append(count[c] == 0 ? '.' : '-');
        }
        lines.add(axis.toString());
        String from = "-" + ago(Duration.between(first, last));
        String to = "now";
        int gap = Math.max(1, columns - from.length() - to.length());
        lines.add(" ".repeat(labelWidth + 2) + from + " ".repeat(gap) + to);
        return lines;
    }

    /**
     * Summary line: newest, lowest, average and highest value.
     *
     * @param points values
     * @param unit   unit
     * @return for example {@code now 19.8  min 12.0  avg 19.1  max 20.0}, or empty without values
     */
    public static String summary(List<Point> points, MetricUnit unit) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0;
        int n = 0;
        OptionalDouble latest = OptionalDouble.empty();
        for (Point p : points) {
            if (Double.isFinite(p.value())) {
                min = Math.min(min, p.value());
                max = Math.max(max, p.value());
                sum += p.value();
                n++;
                latest = OptionalDouble.of(p.value());
            }
        }
        if (n == 0) {
            return "";
        }
        return "now " + label(unit, latest.getAsDouble()) + "  min " + label(unit, min) + "  avg " + label(unit, sum / n)
                + "  max " + label(unit, max);
    }

    private static String label(MetricUnit unit, double value) {
        return unit == MetricUnit.NUMBER ? String.format(java.util.Locale.ROOT, "%.1f", value) : unit.format(value);
    }

    private static String ago(Duration d) {
        long s = d.getSeconds();
        if (s >= 7200) {
            return s / 3600 + "h";
        }
        if (s >= 120) {
            return s / 60 + "m";
        }
        return s + "s";
    }

    private static String stripEnd(StringBuilder line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            end--;
        }
        return line.substring(0, end);
    }
}
