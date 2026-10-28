package net.vectora.cloud.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.vectora.cloud.config.secret.Secret;
import org.junit.jupiter.api.Test;

class BootstrapStatementsTest {

    private static final Secret ROOT = Secret.of("RootPasswordForTests0123");
    private static final Secret APP = Secret.of("AppPasswordForTests45678");

    @Test
    void initFileSetsRootPasswordAndCleansUpBeforeTheFirstConnection() {
        String file = BootstrapStatements.initFile(ROOT);
        List<String> lines = file.lines().toList();
        assertTrue(file.endsWith("\n"));
        for (String line : lines) {
            assertTrue(line.endsWith(";"), "one statement per line: " + line);
        }
        assertTrue(lines.contains("ALTER USER IF EXISTS 'root'@'localhost' IDENTIFIED BY 'RootPasswordForTests0123';"));
        assertTrue(lines.contains("CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY 'RootPasswordForTests0123';"));
        assertTrue(lines.contains("GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;"));
        assertTrue(lines.contains("DELETE FROM mysql.global_priv WHERE User = '';"));
        assertTrue(lines.contains("DROP DATABASE IF EXISTS `test`;"));
        assertEquals("FLUSH PRIVILEGES;", lines.get(lines.size() - 1));
    }

    @Test
    void initFileRefusesAPasswordThatCouldBreakOut() {
        assertThrows(IllegalArgumentException.class, () -> BootstrapStatements.initFile(Secret.of("abc\\'; DROP")));
        assertThrows(IllegalArgumentException.class, () -> BootstrapStatements.initFile(Secret.of("abc\nDROP")));
        // A quote alone is harmless: it is doubled.
        assertTrue(BootstrapStatements.initFile(Secret.of("it's")).contains("'it''s'"));
    }

    @Test
    void secureDropsAnonymousAndRemoteRootOnly() {
        List<Account> accounts = List.of(
                new Account("root", "localhost"), new Account("root", "127.0.0.1"), new Account("root", "::1"),
                new Account("root", "build-host"), new Account("root", "%"), new Account("", "localhost"),
                new Account("", "build-host"), new Account("mariadb.sys", "localhost"),
                new Account("luckperms", "127.0.0.1"));
        List<String> statements = BootstrapStatements.secure(accounts, BootstrapStatements.LOCAL_ROOT_HOSTS);
        assertEquals(List.of(
                "DROP USER IF EXISTS 'root'@'build-host'",
                "DROP USER IF EXISTS 'root'@'%'",
                "DROP USER IF EXISTS ''@'localhost'",
                "DROP USER IF EXISTS ''@'build-host'",
                "DROP DATABASE IF EXISTS `test`",
                "DELETE FROM mysql.db WHERE Db = 'test' OR Db = CONCAT('test', CHAR(92 USING utf8mb4), '_%')",
                "FLUSH PRIVILEGES"), statements);
    }

    @Test
    void secureKeepsRootOnTheGivenHosts() {
        List<String> statements = BootstrapStatements.secure(List.of(new Account("root", "%"), new Account("ROOT", "x")),
                Set.of("%"));
        assertFalse(statements.stream().anyMatch(s -> s.startsWith("DROP USER")), statements.toString());
    }

    @Test
    void secureRefusesAccountNamesItCannotQuote() {
        assertThrows(IllegalArgumentException.class,
                () -> BootstrapStatements.secure(List.of(new Account("", "evil\\host")), Set.of()));
    }

    @Test
    void applicationGrantsMinimalRightsOnItsOwnDatabase() {
        List<String> statements = BootstrapStatements.application("luckperms", "luckperms", "127.0.0.1", APP);
        assertEquals(List.of(
                "CREATE DATABASE IF NOT EXISTS `luckperms` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                "CREATE USER IF NOT EXISTS 'luckperms'@'127.0.0.1' IDENTIFIED BY 'AppPasswordForTests45678'",
                "ALTER USER 'luckperms'@'127.0.0.1' IDENTIFIED BY 'AppPasswordForTests45678'",
                "REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'luckperms'@'127.0.0.1'",
                "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX ON `luckperms`.* TO"
                        + " 'luckperms'@'127.0.0.1'",
                "FLUSH PRIVILEGES"), statements);
        String all = String.join("\n", statements);
        assertFalse(all.contains("ALL PRIVILEGES ON"), "never ALL on anything");
        assertFalse(all.contains("*.*"), "never a global right");
        assertFalse(all.contains("WITH GRANT OPTION"));
    }

    @Test
    void applicationRefusesInjectionInNames() {
        assertThrows(IllegalArgumentException.class,
                () -> BootstrapStatements.application("lp`; DROP DATABASE mysql; --", "lp", "127.0.0.1", APP));
        assertThrows(IllegalArgumentException.class,
                () -> BootstrapStatements.application("lp", "lp'@'%", "127.0.0.1", APP));
        assertThrows(IllegalArgumentException.class,
                () -> BootstrapStatements.application("lp", "lp", "127.0.0.1' IDENTIFIED BY 'x", APP));
        assertThrows(IllegalArgumentException.class,
                () -> BootstrapStatements.application("lp", "lp", "%", Secret.of("pw\\")));
    }

    @Test
    void sameInputGivesSameStatements() {
        assertEquals(BootstrapStatements.application("db", "u", "%", APP), BootstrapStatements.application("db", "u", "%", APP));
        assertEquals(BootstrapStatements.initFile(ROOT), BootstrapStatements.initFile(ROOT));
    }
}
