package net.vectora.cloud.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ConnectionBudgetTest {

    private final ConnectionBudget managed = ConnectionBudget.managed();

    @Test
    void keepsTheOldPoolSizeWhileItFits() throws DatabaseException {
        assertEquals(130, managed.available());
        assertEquals(4, managed.poolSizeFor(0));
        assertEquals(4, managed.poolSizeFor(1));
        // The seven servers of today plus the proxy: 7 x 4 = 28.
        assertEquals(4, managed.poolSizeFor(7));
        assertEquals(4, managed.poolSizeFor(32));
    }

    @Test
    void shrinksEvenlyWhenManyServicesRun() throws DatabaseException {
        assertEquals(3, managed.poolSizeFor(33));
        assertEquals(3, managed.poolSizeFor(43));
        assertEquals(2, managed.poolSizeFor(44));
        assertEquals(2, managed.poolSizeFor(65));
    }

    @Test
    void sumOfPoolsNeverExceedsTheBudget() throws DatabaseException {
        for (int services = 1; services <= 65; services++) {
            int size = managed.poolSizeFor(services);
            assertEquals(true, services * size <= managed.available(), services + " services x " + size);
        }
    }

    @Test
    void refusesWhenEvenTheSmallestPoolsDoNotFit() {
        DatabaseException e = assertThrows(DatabaseException.class, () -> managed.poolSizeFor(66));
        assertEquals(DatabaseException.Reason.CONNECTION_BUDGET, e.reason());
        assertThrows(IllegalArgumentException.class, () -> managed.poolSizeFor(-1));
    }

    @Test
    void refusesABudgetWithoutRoom() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionBudget(10, 9));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionBudget(10, -1));
        assertEquals(2, new ConnectionBudget(10, 8).available());
    }
}
