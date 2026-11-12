// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.Properties;
import net.vectolyn.cloud.config.secret.Secret;
import org.junit.jupiter.api.Test;

class PostgresJdbcConnectorTest {

    private static final Secret PASSWORD = Secret.of("PgPasswordForTests12345");

    @Test
    void urlHoldsHostPortAndDatabaseOnly() {
        assertEquals("jdbc:postgresql://db.example.net:5432/luckperms",
                PostgresJdbcConnector.url(ConnectionTarget.of("db.example.net", 5432), "luckperms"));
        assertEquals("jdbc:postgresql://[::1]:5432/luckperms",
                PostgresJdbcConnector.url(ConnectionTarget.of("::1", 5432), "luckperms"));
        assertEquals("jdbc:postgresql://[fd00::5]:5433/lp",
                PostgresJdbcConnector.url(ConnectionTarget.of("[fd00::5]", 5433), "lp"));
        assertEquals("jdbc:postgresql://db.example.net:5432/a%3Fb%26c%3Dd",
                PostgresJdbcConnector.url(ConnectionTarget.of("db.example.net", 5432), "a?b&c=d"),
                "no parameter can be injected through the name");
    }

    @Test
    void remoteServersAreVerifiedAgainstTheJavaTrustStore() {
        Properties p = PostgresJdbcConnector.properties(ConnectionTarget.of("db.example.net", 5432), "lp", PASSWORD);
        assertEquals("lp", p.getProperty("user"));
        assertEquals(PASSWORD.reveal(), p.getProperty("password"));
        assertEquals("verify-full", p.getProperty("sslmode"));
        assertEquals("org.postgresql.ssl.DefaultJavaSSLFactory", p.getProperty("sslfactory"));
        assertEquals("10", p.getProperty("connectTimeout"));
        assertEquals("simple", p.getProperty("preferQueryMode"));
        assertFalse(PostgresJdbcConnector.url(ConnectionTarget.of("db.example.net", 5432), "lp")
                .contains(PASSWORD.reveal()));
    }

    @Test
    void loopbackGoesWithoutTls() {
        Properties p = PostgresJdbcConnector.properties(
                ConnectionTarget.of("127.0.0.1", 5432).withTimeout(Duration.ofMillis(300)), "lp", PASSWORD);
        assertEquals("disable", p.getProperty("sslmode"));
        assertNull(p.getProperty("sslfactory"));
        assertEquals("1", p.getProperty("socketTimeout"), "at least one second");
    }
}
