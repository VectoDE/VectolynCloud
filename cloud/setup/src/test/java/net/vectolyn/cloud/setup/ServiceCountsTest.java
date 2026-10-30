package net.vectolyn.cloud.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Reading {@code static/min/max} as typed on the console or written in the setup file. */
class ServiceCountsTest {

    @Test
    void readsThreeCountsWithSpacesAroundThem() {
        assertEquals(new ServiceCounts(1, 1, 4), ServiceCounts.parse("1/1/4"));
        assertEquals(new ServiceCounts(0, 1, 1000), ServiceCounts.parse(" 0 / 1 / 1000 "));
        assertEquals(new ServiceCounts(9999, 9999, 9999), ServiceCounts.parse("9999/9999/9999"),
                "the largest form is read; the range is checked by problem()");
    }

    @Test
    void anythingElseIsNoCountsInsteadOfAnException() {
        assertNull(ServiceCounts.parse(null));
        assertNull(ServiceCounts.parse(""));
        assertNull(ServiceCounts.parse("1/1"));
        assertNull(ServiceCounts.parse("1/1/4/5"));
        assertNull(ServiceCounts.parse("-1/1/4"));
        assertNull(ServiceCounts.parse("a/1/4"));
        assertNull(ServiceCounts.parse("99999/1/4"), "five digits");
        assertNull(ServiceCounts.parse("99999999999/1/4"), "beyond int");
        assertNull(ServiceCounts.parse("١/1/4"), "a non-ASCII digit");
    }

    @Test
    void problemChecksTheRuleOfTheTaskFile() {
        assertNull(new ServiceCounts(1, 1, 4).problem());
        assertEquals(SetupMessages.CHECK_SERVICES, new ServiceCounts(2, 1, 4).problem(), "static above min");
        assertEquals(SetupMessages.CHECK_SERVICES, new ServiceCounts(0, 0, 0).problem(), "max below 1");
        assertEquals(SetupMessages.CHECK_SERVICES, new ServiceCounts(0, 1, 1001).problem(), "max above 1000");
    }
}
