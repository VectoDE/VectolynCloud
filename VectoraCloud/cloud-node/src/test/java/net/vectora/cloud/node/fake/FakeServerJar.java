package net.vectora.cloud.node.fake;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Builds the fake server jar: {@link FakeServer} as {@code Main-Class}, nothing else. */
public final class FakeServerJar {

    private FakeServerJar() {
    }

    /**
     * Writes the jar.
     *
     * @param target where to write it
     * @return {@code target}
     * @throws IOException if the class file cannot be read or the jar not written
     */
    public static Path write(Path target) throws IOException {
        String resource = FakeServer.class.getName().replace('.', '/') + ".class";
        byte[] classFile;
        try (InputStream in = FakeServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(resource + " is not on the class path");
            }
            classFile = in.readAllBytes();
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeServer.class.getName());
        Files.createDirectories(target.toAbsolutePath().getParent());
        try (OutputStream file = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(file, manifest)) {
            jar.putNextEntry(new JarEntry(resource));
            jar.write(classFile);
            jar.closeEntry();
        }
        return target;
    }
}
