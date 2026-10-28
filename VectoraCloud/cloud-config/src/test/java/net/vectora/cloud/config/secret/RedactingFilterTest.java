package net.vectora.cloud.config.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class RedactingFilterTest {

    @Test
    void masksEveryOccurrenceOfEverySecret() {
        RedactingFilter filter = RedactingFilter.of(List.of(Secret.of("alpha-123"), Secret.of("beta-456")));
        assertEquals("a=*** b=*** again ***", filter.redact("a=alpha-123 b=beta-456 again alpha-123"));
        assertEquals(2, filter.size());
    }

    @Test
    void longerSecretsWinOverSecretsTheyContain() {
        RedactingFilter filter = RedactingFilter.of(List.of(Secret.of("pass"), Secret.of("password-long")));
        assertEquals("x *** y ***", filter.redact("x password-long y pass"));
    }

    @Test
    void ignoresShortAndEmptyValues() {
        RedactingFilter filter = RedactingFilter.of(List.of(Secret.of(""), Secret.of("abc"), Secret.of("3307")));
        assertEquals(1, filter.size());
        assertEquals("abc ***", filter.redact("abc 3307"));
    }

    @Test
    void nullAndEmptyFiltersAreHarmless() {
        assertNull(RedactingFilter.none().redact(null));
        assertEquals("text", RedactingFilter.none().redact("text"));
        assertEquals("text", RedactingFilter.of(List.of()).redact("text"));
    }
}
