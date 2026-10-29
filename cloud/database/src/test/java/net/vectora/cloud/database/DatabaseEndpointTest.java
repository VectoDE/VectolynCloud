package net.vectora.cloud.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DatabaseEndpointTest {

    @Test
    void placeholderValuesForMariaDb() {
        DatabaseEndpoint endpoint = new DatabaseEndpoint(StorageMethod.MARIADB, "127.0.0.1", 3307, "luckperms",
                "luckperms", Optional.empty());
        assertEquals(Map.of(
                "VECTORA_DB_HOST", "127.0.0.1",
                "VECTORA_DB_PORT", "3307",
                "VECTORA_DB_NAME", "luckperms",
                "VECTORA_DB_USER", "luckperms",
                "VECTORA_DB_STORAGE_METHOD", "mariadb",
                "VECTORA_DB_POOL_SIZE", "4"), endpoint.placeholderValues(4));
    }

    @Test
    void storageMethodsAreLuckPermsNames() {
        assertEquals("mariadb", StorageMethod.MARIADB.placeholderValue());
        assertEquals("mysql", StorageMethod.MYSQL.placeholderValue());
        assertEquals("h2", StorageMethod.H2.placeholderValue());
    }

    /**
     * The values must pass the renderer's rules (docs/cloud/template.md, "Allowed values"): no control
     * characters, ports 1-65535, and none of the new names may end in _PASSWORD, _SECRET or _PORT,
     * or the renderer would check them as secrets or ports.
     */
    @Test
    void valuesFollowTheTemplateRules() {
        for (StorageMethod method : StorageMethod.values()) {
            DatabaseEndpoint endpoint = new DatabaseEndpoint(method, "db.example.net", 3306, "lp", "lp", Optional.empty());
            for (Map.Entry<String, String> e : endpoint.placeholderValues(2).entrySet()) {
                assertTrue(e.getKey().matches("VECTORA_[A-Z0-9_]+"), e.getKey());
                assertFalse(e.getValue().chars().anyMatch(c -> Character.isISOControl((char) c)), e.getKey());
                assertFalse(e.getKey().endsWith("_PASSWORD") || e.getKey().endsWith("_SECRET"), e.getKey());
                if (e.getKey().endsWith("_PORT")) {
                    int port = Integer.parseInt(e.getValue());
                    assertTrue(port >= 1 && port <= 65535);
                }
            }
        }
        assertFalse(DatabaseEndpoint.POOL_SIZE.endsWith("_PORT"));
        assertFalse(DatabaseEndpoint.STORAGE_METHOD.endsWith("_PORT"));
    }

    @Test
    void poolSizeBelowTheMinimumIsRefused() {
        DatabaseEndpoint endpoint = new DatabaseEndpoint(StorageMethod.H2, "127.0.0.1", 3307, "lp", "lp", Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> endpoint.placeholderValues(1));
    }
}
