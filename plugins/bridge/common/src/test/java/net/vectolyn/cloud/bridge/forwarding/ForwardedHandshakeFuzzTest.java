package net.vectolyn.cloud.bridge.forwarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Fuzz targets for the forwarded handshake a 1.8.8 backend reads from whoever reaches its port.
 * Stored inputs live in {@code ForwardedHandshakeFuzzTestInputs/<method>/}; the nightly workflow
 * fuzzes them on Linux. The input is read as UTF-8, as the server reads the handshake string.
 */
class ForwardedHandshakeFuzzTest {

    private static final BungeeGuardCheck CHECK = BungeeGuardCheck.with(ForwardingSecret.of(Handshakes.SECRET));

    /**
     * Any handshake: the check returns a result and never throws. If it accepts, the forwarded
     * properties parse again and no longer contain a token.
     */
    @FuzzTest
    void checkNeverThrowsAndNeverPassesTheToken(byte @NotNull [] input) throws MalformedHandshakeException {
        String handshake = new String(input, StandardCharsets.UTF_8);
        BungeeGuardCheck.Result result = CHECK.check(handshake);
        assertNotNull(result);
        if (result instanceof BungeeGuardCheck.Accepted accepted) {
            List<GameProperty> properties = PropertiesJson.parse(accepted.propertiesJson());
            for (GameProperty property : properties) {
                assertFalse(BungeeGuardCheck.TOKEN_PROPERTY.equals(property.name()));
            }
        }
    }

    /**
     * Any properties JSON: parsing fails only with {@link MalformedHandshakeException}, and what
     * parses is written in a form that parses to the same properties.
     */
    @FuzzTest
    void propertiesParseStrictlyAndRoundTrip(byte @NotNull [] input) {
        String json = new String(input, StandardCharsets.UTF_8);
        List<GameProperty> parsed;
        try {
            parsed = PropertiesJson.parse(json);
        } catch (MalformedHandshakeException expected) {
            return;
        }
        try {
            assertEquals(parsed, PropertiesJson.parse(PropertiesJson.write(parsed)));
        } catch (MalformedHandshakeException e) {
            throw new AssertionError("written properties do not parse again", e);
        }
    }
}
