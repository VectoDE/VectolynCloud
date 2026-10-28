package net.vectora.cloud.bridge.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Server;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Loads the bridge together with the platform API, which needs a Java 25 runtime. */
@Tag("requires-java25")
class PaperBridgeApiTest {

    @Test
    void describeTakesThePlatformServer() throws NoSuchMethodException {
        Method describe = PaperBridge.class.getMethod("describe", Server.class);
        assertEquals(String.class, describe.getReturnType());
        assertTrue(Modifier.isStatic(describe.getModifiers()), "describe must be static");
    }
}
