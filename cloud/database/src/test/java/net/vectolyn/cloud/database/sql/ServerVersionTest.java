package net.vectolyn.cloud.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServerVersionTest {

    @Test
    void readsMariaDbVersions() {
        assertEquals(Optional.of(new ServerVersion(11, 8, 9, true)), ServerVersion.parse("11.8.9-MariaDB"));
        assertEquals(Optional.of(new ServerVersion(11, 8, 9, true)), ServerVersion.parse("11.8.9-MariaDB-log"));
        assertEquals(Optional.of(new ServerVersion(10, 11, 6, true)), ServerVersion.parse("5.5.5-10.11.6-MariaDB-0+deb12u1"));
        assertEquals(Optional.of(new ServerVersion(11, 4, 2, true)), ServerVersion.parse("11.4.2-MariaDB-ubu2404"));
    }

    @Test
    void readsMySqlVersions() {
        assertEquals(Optional.of(new ServerVersion(8, 4, 3, false)), ServerVersion.parse("8.4.3"));
        assertEquals(Optional.of(new ServerVersion(8, 0, 36, false)), ServerVersion.parse("8.0.36-0ubuntu0.22.04.1"));
        // 5.5.5- is only stripped for MariaDB.
        assertEquals(Optional.of(new ServerVersion(5, 5, 5, false)), ServerVersion.parse("5.5.5-something"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "MariaDB", "11.8", "x11.8.9", "11.8.x", "99999.1.1"})
    void findsNothingInGarbage(String text) {
        assertTrue(ServerVersion.parse(text).isEmpty());
    }

    @Test
    void nullAndHugeInputs() {
        assertTrue(ServerVersion.parse(null).isEmpty());
        assertTrue(ServerVersion.parse("1.2.3" + "x".repeat(ServerVersion.MAX_LENGTH)).isEmpty());
    }

    @Test
    void atLeastCompares() {
        ServerVersion v = new ServerVersion(10, 11, 6, true);
        assertTrue(v.atLeast(10, 4));
        assertTrue(v.atLeast(10, 11));
        assertFalse(v.atLeast(11, 0));
        assertEquals("MariaDB 10.11.6", v.toString());
        assertEquals("MySQL 8.4.3", new ServerVersion(8, 4, 3, false).toString());
    }
}
