package net.vectolyn.cloud.monitor.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.vectolyn.cloud.monitor.metric.MetricUnit;
import org.junit.jupiter.api.Test;

class TextChartTest {

    private static final Instant T0 = Instant.parse("2026-10-29T12:00:00Z");

    private static List<TextChart.Point> points(double... values) {
        List<TextChart.Point> list = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            list.add(new TextChart.Point(T0.plusSeconds(i * 10L), values[i]));
        }
        return list;
    }

    @Test
    void drawsAnAreaWithGapsAndLabels() {
        List<String> lines = TextChart.render(points(20, 20, 10, Double.NaN, 0, 5, 20, 20), MetricUnit.NUMBER, 0, 20, 8, 4);
        assertEquals(List.of(
                "20.0 |##    ##",
                "     |##    ##",
                "     |###   ##",
                " 0.0 |###  ###",
                "     +---.----",
                "      -70s now"), lines);
    }

    @Test
    void higherValuesRaiseTheScaleAndPercentLabelsShowUnits() {
        List<String> lines = TextChart.render(points(50, 100), MetricUnit.PERCENT, 0, 100, 8, 2);
        assertTrue(lines.get(0).startsWith("100% |"), lines.get(0));
        List<String> raised = TextChart.render(points(10, 40), MetricUnit.NUMBER, 0, 20, 8, 2);
        assertTrue(raised.get(0).startsWith("40.0 |"), raised.get(0));
    }

    @Test
    void manySamplesShareColumns() {
        double[] values = new double[360];
        java.util.Arrays.fill(values, 20);
        List<String> lines = TextChart.render(points(values), MetricUnit.NUMBER, 0, 20, 60, 8);
        assertEquals(10, lines.size());
        assertEquals("20.0 |" + "#".repeat(60), lines.get(0));
        assertTrue(lines.get(9).contains("-59m"), lines.get(9));
    }

    @Test
    void summaryAndEdgeCases() {
        assertEquals("now 20.0  min 10.0  avg 16.7  max 20.0", TextChart.summary(points(20, 10, Double.NaN, 20), MetricUnit.NUMBER));
        assertEquals("", TextChart.summary(points(Double.NaN), MetricUnit.NUMBER));
        assertTrue(TextChart.render(List.of(), MetricUnit.NUMBER, 0, 20, 8, 2).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> TextChart.render(points(1), MetricUnit.NUMBER, 0, 20, 7, 2));
        List<String> flat = TextChart.render(points(0, 0), MetricUnit.NUMBER, 0, 0, 8, 2);
        assertEquals("1.0 |", flat.get(0), "a scale of 0 to 0 becomes 0 to 1");
    }
}
