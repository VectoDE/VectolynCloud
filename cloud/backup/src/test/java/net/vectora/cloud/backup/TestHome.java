package net.vectora.cloud.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.vectora.cloud.backup.config.BackupConfig;

/** A temporary cloud home with a few static services, plus helpers for archives. */
public final class TestHome {

    /** A rendered secret; no archive may contain it. */
    public static final String SECRET = "RenderedSecret-0123456789abcdef";

    public final Path home;
    public final List<BackupTarget> targets = new ArrayList<>();
    public final FakeServiceControl control = new FakeServiceControl();
    public BackupConfig config = BackupConfig.defaults();
    public final MutableClock clock = new MutableClock(Instant.parse("2026-10-29T03:15:00Z"));

    public TestHome(Path home) {
        this.home = home;
    }

    /**
     * Adds a static service with a world, a config, a jar, logs, a cache and the files the cloud
     * renders with secrets, as the templates under {@code templates/} produce them.
     */
    public Path addServer(String id, String group, String template, boolean worldSaving) throws IOException {
        Path dir = home.resolve(template).resolve("runtime");
        targets.add(new BackupTarget(id, group, "26.3", dir, worldSaving));
        write(dir.resolve("server.properties"), "motd=" + id + "\nmanagement-server-secret=" + SECRET + "\n");
        write(dir.resolve("config/paper-global.yml"), "proxies:\n  velocity:\n    secret: " + SECRET + "\n");
        write(dir.resolve("config/paper-world-defaults.yml"), "anticheat: {}\n");
        write(dir.resolve("config/FabricProxy-Lite.toml"), "secret = \"" + SECRET + "\"\n");
        write(dir.resolve("config/luckperms/luckperms.conf"), "password = \"" + SECRET + "\"\n");
        write(dir.resolve("secrets/vectora.env"), "VECTORA_DB_PASSWORD=" + SECRET + "\n");
        write(dir.resolve("vectora.env"), "VECTORA_DB_PASSWORD=" + SECRET + "\n");
        write(dir.resolve("world/level.dat"), "level-" + id);
        write(dir.resolve("world/region/r.0.0.mca"), "region-" + id);
        write(dir.resolve("world/session.lock"), "lock");
        write(dir.resolve("paper.jar"), "jar");
        write(dir.resolve("plugins/LuckPerms.jar"), "plugin jar");
        write(dir.resolve("plugins/LuckPerms/config.yml"), "storage-method: h2\npassword: '" + SECRET + "'\n");
        write(dir.resolve("plugins/TAB/config.yml"), "header: vectora\n");
        write(dir.resolve("plugins/floodgate/key.pem"), "secret key");
        write(dir.resolve("logs/latest.log"), "log");
        write(dir.resolve("cache/mojang_1.jar"), "cache");
        write(dir.resolve("forwarding.secret"), SECRET);
        return dir;
    }

    public BackupService.Builder builder() {
        return BackupService.builder(home)
                .config(() -> config)
                .targets(() -> List.copyOf(targets))
                .control(control)
                .clock(clock, ZoneOffset.UTC)
                .cloudVersion("1.0.0-test")
                .searchPath(null, false);
    }

    public BackupService service() {
        return builder().build();
    }

    public static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** Entry names and contents of a zip. */
    public static Map<String, String> entries(Path zip) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> all = file.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                try (InputStream in = file.getInputStream(entry)) {
                    result.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }

    /** Writes a zip from names to contents, in order. */
    public static void zip(Path target, Map<String, byte[]> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    /** Raw entries of a zip, in order. */
    public static Map<String, byte[]> rawEntries(Path zip) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> all = file.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                try (InputStream in = file.getInputStream(entry)) {
                    result.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return result;
    }

    /** A clock tests can move. */
    public static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        public void set(Instant value) {
            now = value;
        }

        public void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
