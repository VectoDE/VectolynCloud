package net.vectora.cloud.cluster;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.io.AtomicFileWriter;
import net.vectora.cloud.config.io.ConfigFormat;
import net.vectora.cloud.config.io.ConfigMappers;
import net.vectora.cloud.config.io.ConfigYaml;
import net.vectora.cloud.config.io.RegularPaths;
import net.vectora.cloud.config.model.Language;

/** Reads and writes {@code config/cluster.yml} ({@link ClusterConfig}). */
public final class ClusterFile {

    private static final ConfigFormat<ClusterConfig> FORMAT =
            new ConfigFormat<>(ClusterConfig.class, ClusterConfig.SCHEMA_VERSION, List.of());

    private static final String HEADER = """
            # Written by VectoraCloud (head, members, revoked certificates of the cluster).
            # Change it with the "cluster" commands; a hand edit takes effect at the next start.
            # Reference: docs/cloud/cluster.md
            """;

    private ClusterFile() {
    }

    /**
     * Reads the file.
     *
     * @param file {@code config/cluster.yml}
     * @return the cluster, or empty if the file does not exist (no cluster)
     * @throws IOException if it cannot be read or is invalid; the message names the problem
     */
    public static Optional<ClusterConfig> read(Path file) throws IOException {
        RegularPaths.requireFileOrMissing(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        byte[] content;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            content = in.readNBytes(ConfigMappers.MAX_FILE_BYTES + 1);
        }
        return Optional.of(parse(content));
    }

    /**
     * Parses and checks the content of the file.
     *
     * @param content raw bytes (UTF-8)
     * @return the cluster
     * @throws IOException if it is invalid
     */
    public static ClusterConfig parse(byte[] content) throws IOException {
        ClusterConfig config;
        try {
            config = ConfigYaml.read(ClusterPaths.CLUSTER_FILE, content, FORMAT).value();
        } catch (ConfigException e) {
            throw new IOException(e.describe(Language.EN), e);
        }
        List<String> problems = config.problems();
        if (!problems.isEmpty()) {
            throw new IOException(ClusterPaths.CLUSTER_FILE + ": " + String.join("; ", problems));
        }
        return config;
    }

    /**
     * Writes the file atomically.
     *
     * @param file   {@code config/cluster.yml}
     * @param config the cluster; must be valid
     * @throws IOException if writing fails
     */
    public static void write(Path file, ClusterConfig config) throws IOException {
        List<String> problems = config.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        RegularPaths.requireFileOrMissing(file);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] content = (HEADER + ConfigMappers.yaml().writeValueAsString(config)).getBytes(StandardCharsets.UTF_8);
        new AtomicFileWriter().write(file, content);
    }
}
