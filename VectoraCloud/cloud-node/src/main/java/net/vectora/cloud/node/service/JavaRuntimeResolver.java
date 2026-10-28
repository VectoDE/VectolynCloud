package net.vectora.cloud.node.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import net.vectora.cloud.config.model.GroupConfig;

/**
 * Finds the Java runtime a service of a group runs on.
 *
 * <p>This is the seam for the JRE management (Adoptium downloads per group, stage 2 PR 7). Until
 * that is plugged in, {@link #current()} runs every service on the JVM the cloud itself runs on.
 * An implementation may download or unpack a runtime and may block while it does so; the node
 * calls it from a worker thread, never while it holds a lock.
 */
@FunctionalInterface
public interface JavaRuntimeResolver {

    /**
     * Returns the runtime for one start of a service.
     *
     * @param group  group name
     * @param config the group, including {@link GroupConfig#java()} ({@code auto} or a major version)
     * @return the runtime to start the service with
     * @throws IOException if the runtime cannot be found or prepared
     */
    JavaRuntime resolve(String group, GroupConfig config) throws IOException;

    /**
     * Runtime that runs every service on the JVM of the cloud ({@code java.home}).
     *
     * @return the default resolver
     */
    static JavaRuntimeResolver current() {
        return (group, config) -> JavaRuntime.current();
    }

    /**
     * A Java runtime on disk.
     *
     * @param executable     the {@code java} or {@code java.exe} launcher
     * @param featureVersion its major version, for example 21; 0 if unknown
     */
    record JavaRuntime(Path executable, int featureVersion) {

        /** Checks the values. */
        public JavaRuntime {
            Objects.requireNonNull(executable, "executable");
            if (featureVersion < 0) {
                throw new IllegalArgumentException("featureVersion must not be negative");
            }
        }

        /**
         * The runtime the cloud itself runs on.
         *
         * @return {@code <java.home>/bin/java} ({@code java.exe} on Windows) and its feature version
         * @throws IOException if the launcher is not where it should be
         */
        public static JavaRuntime current() throws IOException {
            Path home = Path.of(System.getProperty("java.home"));
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
            Path executable = home.resolve("bin").resolve(windows ? "java.exe" : "java");
            if (!Files.isRegularFile(executable)) {
                throw new IOException("no java launcher at " + executable);
            }
            return new JavaRuntime(executable, Runtime.version().feature());
        }
    }
}
