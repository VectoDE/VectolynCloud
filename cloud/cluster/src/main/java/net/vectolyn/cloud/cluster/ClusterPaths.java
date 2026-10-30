package net.vectolyn.cloud.cluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Objects;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.io.AtomicFileWriter;
import net.vectolyn.cloud.config.io.RegularPaths;
import net.vectolyn.cloud.config.secret.OwnerOnlyPermissions;
import net.vectolyn.cloud.config.secret.Secret;

/**
 * Where the cluster keeps its files in the home.
 *
 * <pre>
 * config/cluster.yml                head, members, revoked certificates (written by the head)
 * secrets/cluster/                  owner only
 *   ca.p12, ca.password             the cluster CA (head only)
 *   node.p12, node.password         this node's key and certificate
 *   join-tokens.yml                 open join tokens, SHA-256 of their secret only (head only)
 *   work/                           certification requests while keytool signs them
 * </pre>
 *
 * @param home the cloud home
 */
public record ClusterPaths(CloudHome home) {

    /** File name of the cluster file in {@code config/}. */
    public static final String CLUSTER_FILE = "cluster.yml";

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /** Checks the home. */
    public ClusterPaths {
        Objects.requireNonNull(home, "home");
    }

    /** {@code config/cluster.yml}. */
    public Path clusterFile() {
        return home.configDir().resolve(CLUSTER_FILE);
    }

    /** {@code secrets/cluster/}. */
    public Path dir() {
        return home.secretsDir().resolve("cluster");
    }

    /** {@code secrets/cluster/ca.p12}. */
    public Path caKeystore() {
        return dir().resolve("ca.p12");
    }

    /** {@code secrets/cluster/ca.password}. */
    public Path caPassword() {
        return dir().resolve("ca.password");
    }

    /** {@code secrets/cluster/node.p12}. */
    public Path nodeKeystore() {
        return dir().resolve("node.p12");
    }

    /** {@code secrets/cluster/node.password}. */
    public Path nodePassword() {
        return dir().resolve("node.password");
    }

    /** {@code secrets/cluster/join-tokens.yml}. */
    public Path joinTokens() {
        return dir().resolve("join-tokens.yml");
    }

    /** {@code secrets/cluster/work/}. */
    public Path workDir() {
        return dir().resolve("work");
    }

    /** {@code state/cluster/}: the mirror's records. */
    public Path stateDir() {
        return home.stateDir().resolve("cluster");
    }

    /** {@code state/cluster/config-revision}: the head's config revision. */
    public Path configRevision() {
        return stateDir().resolve("config-revision");
    }

    /** {@code state/cluster/config-applied}: the config revision a member took over last. */
    public Path configApplied() {
        return stateDir().resolve("config-applied");
    }

    /** {@code state/cluster/templates}: the templates a member synced, with their digest. */
    public Path templatesState() {
        return stateDir().resolve("templates");
    }

    /** {@code local/templates}. */
    public Path templatesDir() {
        return home.templatesDir();
    }

    /** {@code local/.cluster-sync/}: staging of the template sync, next to the templates. */
    public Path syncWorkDir() {
        return home.templatesDir().resolveSibling(".cluster-sync");
    }

    /**
     * Creates {@code secrets/} and {@code secrets/cluster/} if needed and restricts both to the
     * owner. A symbolic link or a file in their place is an error.
     *
     * @throws IOException if they cannot be created or restricted
     */
    public void ensureDir() throws IOException {
        RegularPaths.requireDirectoryOrMissing(home.secretsDir());
        Files.createDirectories(home.secretsDir());
        OwnerOnlyPermissions.restrictDirectory(home.secretsDir());
        RegularPaths.requireDirectoryOrMissing(dir());
        Files.createDirectories(dir());
        OwnerOnlyPermissions.restrictDirectory(dir());
    }

    /**
     * Reads a password file.
     *
     * @throws IOException if it is missing, empty or no regular file
     */
    static Secret readPassword(Path file) throws IOException {
        RegularPaths.requireFileOrMissing(file);
        if (!Files.isRegularFile(file)) {
            throw new IOException(file.getFileName() + " is missing from secrets/cluster/");
        }
        String text = Files.readString(file, StandardCharsets.US_ASCII).strip();
        if (text.isEmpty()) {
            throw new IOException(file.getFileName() + " in secrets/cluster/ is empty");
        }
        return Secret.of(text);
    }

    /** Writes a new random password (40 letters and digits) into an owner-only file. */
    static Secret newPassword(Path file) throws IOException {
        SecureRandom random = new SecureRandom();
        StringBuilder out = new StringBuilder(40);
        for (int i = 0; i < 40; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        Secret password = Secret.of(out.toString());
        RegularPaths.requireFileOrMissing(file);
        new AtomicFileWriter().write(file, password.reveal().getBytes(StandardCharsets.US_ASCII),
                OwnerOnlyPermissions::restrictFile);
        OwnerOnlyPermissions.restrictFile(file);
        return password;
    }
}
