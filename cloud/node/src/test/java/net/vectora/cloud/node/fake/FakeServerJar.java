package net.vectora.cloud.node.fake;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import net.vectora.cloud.client.CloudClient;
import net.vectora.protocol.ServiceId;

/**
 * Builds the fake server jar: {@link FakeServer} as {@code Main-Class}, and for
 * {@link #writeWithCloudClient} also {@link FakeCloudBridge} with the classes of
 * {@code common/client} and {@code common/protocol}, as a bridge plugin would bring them.
 */
public final class FakeServerJar {

    /** Resource with default system properties of the fake, read when a property is not set. */
    public static final String DEFAULTS = "fake-defaults.properties";

    private FakeServerJar() {
    }

    /**
     * Writes the jar with the cloud client that connects to the cloud channel without any JVM
     * flag: {@code fake.cloud=true} is in its defaults. For the end-to-end test of
     * {@code VectoraCloud.jar}, whose groups come from the setup, not from the test.
     *
     * @param target where to write it
     * @return {@code target}
     * @throws IOException if a class cannot be read or the jar not written
     */
    public static Path writeConnected(Path target) throws IOException {
        return write(target, true, "fake.cloud=true\n");
    }

    /**
     * Writes the jar with only {@link FakeServer}.
     *
     * @param target where to write it
     * @return {@code target}
     * @throws IOException if the class file cannot be read or the jar not written
     */
    public static Path write(Path target) throws IOException {
        return write(target, false, null);
    }

    /**
     * Writes the jar that can run in {@code fake.cloud} mode.
     *
     * @param target where to write it
     * @return {@code target}
     * @throws IOException if a class cannot be read or the jar not written
     */
    public static Path writeWithCloudClient(Path target) throws IOException {
        return write(target, true, null);
    }

    private static Path write(Path target, boolean cloudClient, String defaults) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeServer.class.getName());
        Files.createDirectories(target.toAbsolutePath().getParent());
        Set<String> written = new HashSet<>();
        try (OutputStream file = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(file, manifest)) {
            put(jar, written, resource(FakeServer.class), classFile(FakeServer.class));
            if (cloudClient) {
                put(jar, written, resource(FakeCloudBridge.class), classFile(FakeCloudBridge.class));
                copyClasses(jar, written, location(CloudClient.class));
                copyClasses(jar, written, location(ServiceId.class));
            }
            if (defaults != null) {
                put(jar, written, DEFAULTS, defaults.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            }
        }
        return target;
    }

    private static String resource(Class<?> type) {
        return type.getName().replace('.', '/') + ".class";
    }

    private static byte[] classFile(Class<?> type) throws IOException {
        String resource = resource(type);
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(resource + " is not on the class path");
            }
            return in.readAllBytes();
        }
    }

    private static Path location(Class<?> type) throws IOException {
        java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null) {
            throw new IOException("cannot find the classes of " + type.getName());
        }
        try {
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException("cannot find the classes of " + type.getName(), e);
        }
    }

    /** Copies every class file of a class folder or jar, without its manifest or module-info. */
    private static void copyClasses(JarOutputStream jar, Set<String> written, Path source) throws IOException {
        if (Files.isDirectory(source)) {
            List<Path> classes = new ArrayList<>();
            try (Stream<Path> files = Files.walk(source)) {
                files.filter(f -> f.toString().endsWith(".class")).forEach(classes::add);
            }
            for (Path classFile : classes) {
                String name = source.relativize(classFile).toString().replace('\\', '/');
                if (!name.endsWith("module-info.class")) {
                    put(jar, written, name, Files.readAllBytes(classFile));
                }
            }
            return;
        }
        try (JarFile in = new JarFile(source.toFile())) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.endsWith("module-info.class")) {
                    try (InputStream data = in.getInputStream(entry)) {
                        put(jar, written, name, data.readAllBytes());
                    }
                }
            }
        }
    }

    private static void put(JarOutputStream jar, Set<String> written, String name, byte[] data) throws IOException {
        if (!written.add(name)) {
            return;
        }
        jar.putNextEntry(new JarEntry(name));
        jar.write(data);
        jar.closeEntry();
    }
}
