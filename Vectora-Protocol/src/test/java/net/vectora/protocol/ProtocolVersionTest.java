package net.vectora.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProtocolVersionTest {

    @Test
    void currentVersionIsOnePointOne() {
        assertEquals(new ProtocolVersion(1, 1), Protocol.PROTOCOL_VERSION);
        assertEquals("1.1", Protocol.PROTOCOL_VERSION.toString());
    }

    @Test
    void sameMajorIsCompatibleDifferentMajorIsNot() {
        assertTrue(new ProtocolVersion(1, 0).isCompatibleWith(new ProtocolVersion(1, 7)));
        assertFalse(new ProtocolVersion(1, 0).isCompatibleWith(new ProtocolVersion(2, 0)));
    }

    @Test
    void negotiationPicksTheLowerMinor() {
        ProtocolVersion older = new ProtocolVersion(1, 2);
        ProtocolVersion newer = new ProtocolVersion(1, 5);
        assertEquals(older, older.negotiate(newer));
        assertEquals(older, newer.negotiate(older));
        assertThrows(IllegalArgumentException.class, () -> older.negotiate(new ProtocolVersion(2, 2)));
    }

    @Test
    void componentsMustFitIntoOneByte() {
        assertEquals(255, new ProtocolVersion(255, 255).major());
        assertThrows(IllegalArgumentException.class, () -> new ProtocolVersion(256, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolVersion(0, -1));
    }

    @Test
    void ordersByMajorThenMinor() {
        assertTrue(new ProtocolVersion(1, 9).compareTo(new ProtocolVersion(2, 0)) < 0);
        assertTrue(new ProtocolVersion(1, 2).compareTo(new ProtocolVersion(1, 1)) > 0);
    }
}
