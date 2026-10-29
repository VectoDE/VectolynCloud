package net.vectora.cloud.bridge.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class VelocityBridgePluginTest {

    private static String descriptor() throws IOException {
        try (InputStream in = VelocityBridgePluginTest.class.getClassLoader().getResourceAsStream("velocity-plugin.json")) {
            assertNotNull(in, "velocity-plugin.json must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        assertTrue(matcher.find(), name + " missing in velocity-plugin.json");
        return matcher.group(1);
    }

    @Test
    void descriptorNamesThePluginAndItsVersion() throws IOException {
        String json = descriptor();
        String id = field(json, "id");
        assertTrue(id.matches("[a-z][a-z0-9-_]{0,63}"), "Velocity's plugin id rule");
        assertEquals("net.vectora.cloud.bridge.velocity.VelocityBridgePlugin", field(json, "main"));
        assertEquals(System.getProperty("vectora.expectedVersion"), field(json, "version"));
    }

    @Test
    @Tag("requires-java25") // loads velocity-api 4.3 classes (class file version 69)
    void velocityCanCreateAndWireThePlugin() throws Exception {
        Class<?> main = Class.forName(field(descriptor(), "main"), false, getClass().getClassLoader());
        assertTrue(main.getConstructor(ProxyServer.class).isAnnotationPresent(Inject.class),
                "Velocity creates the main class through Guice");
        long handlers = Arrays.stream(main.getMethods()).filter(m -> m.isAnnotationPresent(Subscribe.class)).count();
        assertEquals(5, handlers, "listener bound (connect and ready), initial server, kicked (fallback),"
                + " server connected, disconnect");
        for (Method method : main.getMethods()) {
            if (method.isAnnotationPresent(Subscribe.class)) {
                assertEquals(1, method.getParameterCount(), method.getName());
            }
        }
    }
}
