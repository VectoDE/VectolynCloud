package net.vectolyn.cloud.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Properties;
import net.vectolyn.cloud.config.secret.Secret;
import org.junit.jupiter.api.Test;

class ConnectionTargetTest {

    @Test
    void loopbackGetsNoTlsEverythingElseFullVerification() {
        assertEquals(TlsMode.DISABLE, ConnectionTarget.of("127.0.0.1", 3307).tls());
        assertEquals(TlsMode.DISABLE, ConnectionTarget.of("localhost", 3307).tls());
        assertEquals(TlsMode.DISABLE, ConnectionTarget.of("::1", 3307).tls());
        assertEquals(TlsMode.VERIFY_FULL, ConnectionTarget.of("db.example.net", 3306).tls());
        assertEquals(TlsMode.VERIFY_FULL, ConnectionTarget.of("10.0.0.5", 3306).tls());
    }

    @Test
    void tlsCannotBeSwitchedOffForRemoteHosts() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConnectionTarget("db.example.net", 3306, TlsMode.DISABLE, Duration.ofSeconds(1)));
    }

    @Test
    void urlHasNoCredentialsAndBracketsIpv6() {
        assertEquals("jdbc:mariadb://127.0.0.1:3307/", ConnectionTarget.of("127.0.0.1", 3307).jdbcUrl());
        assertEquals("jdbc:mariadb://[::1]:3307/", ConnectionTarget.of("::1", 3307).jdbcUrl());
        assertEquals("jdbc:mariadb://[::1]:3307/", ConnectionTarget.of("[::1]", 3307).jdbcUrl());
    }

    @Test
    void refusesBadValues() {
        assertThrows(IllegalArgumentException.class, () -> ConnectionTarget.of("bad host", 3306));
        assertThrows(IllegalArgumentException.class, () -> ConnectionTarget.of("db/../x?user=root", 3306));
        assertThrows(IllegalArgumentException.class, () -> ConnectionTarget.of("db", 0));
        assertThrows(IllegalArgumentException.class, () -> ConnectionTarget.of("db", 65536));
        assertThrows(IllegalArgumentException.class,
                () -> new ConnectionTarget("db", 3306, TlsMode.VERIFY_FULL, Duration.ZERO));
    }

    @Test
    void propertiesCarryCredentialsAndSafeDefaults() {
        Properties p = JdbcConnector.properties(ConnectionTarget.of("127.0.0.1", 3307), "root",
                Secret.of("RootPasswordForTests0123"));
        assertEquals("root", p.getProperty("user"));
        assertEquals("RootPasswordForTests0123", p.getProperty("password"));
        assertEquals("disable", p.getProperty("sslMode"));
        assertEquals("false", p.getProperty("allowMultiQueries"));
        assertEquals("false", p.getProperty("allowLocalInfile"));
        assertEquals("10000", p.getProperty("connectTimeout"));
        assertFalse(ConnectionTarget.of("127.0.0.1", 3307).toString().contains("root"));
        assertEquals("verify-full",
                JdbcConnector.properties(ConnectionTarget.of("db.example.net", 3306), "u", Secret.of("x")).getProperty("sslMode"));
    }
}
