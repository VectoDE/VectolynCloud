package net.vectolyn.cloud.config.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SecretTest {

    private static final String VALUE = "hunter2-correct-horse";

    @Test
    void toStringNeverShowsTheValue() {
        Secret secret = Secret.of(VALUE);
        assertEquals("***", secret.toString());
        assertEquals("***", String.valueOf(secret));
        assertFalse(("value: " + secret).contains(VALUE));
        assertFalse(List.of(secret).toString().contains(VALUE));
        assertFalse(new EnvFile.Entry("KEY", secret).toString().contains(VALUE));
    }

    @Test
    void valueIsOnlyReachableThroughReveal() {
        assertEquals(VALUE, Secret.of(VALUE).reveal());
        assertEquals(VALUE.length(), Secret.of(VALUE).length());
        assertTrue(Secret.of("").isEmpty());
    }

    @Test
    void equalityComparesTheValues() {
        assertEquals(Secret.of(VALUE), Secret.of(new String(VALUE.toCharArray())));
        assertNotEquals(Secret.of(VALUE), Secret.of(VALUE + "x"));
        assertNotEquals(Secret.of(VALUE), Secret.of("hunter2-correct-horsf"));
        assertNotEquals(Secret.of(VALUE), VALUE);
        assertEquals(Secret.of("a").hashCode(), Secret.of("b").hashCode(), "hash reveals nothing");
    }
}
