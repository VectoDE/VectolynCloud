package net.vectora.cloud.node;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The server templates that travel inside the node ({@code docs/cloud/node.md#built-in-templates}),
 * so that {@code java -jar VectoraCloud.jar} in an empty folder has every
 * {@code <Server>/template/} it needs.
 *
 * <p>The build copies the tracked files of {@code Server-Network/<Server>/template/} under
 * {@value #RESOURCE_DIR}, without jars or key files, and lists each one with its SHA-256 in
 * {@value #INDEX}. On every start {@link #seed} writes the template folders the home does not
 * have. A folder that exists, also an empty one or a link, belongs to the operator and is never
 * touched; the cloud does not merge or update templates. Each folder is written into a staging
 * folder next to it first and moved into place once every file matched the index, so a crash
 * leaves either the whole template or none.
 */
public final class BuiltInTemplates {

    private static final System.Logger LOG = System.getLogger(BuiltInTemplates.class.getName());

    /** Folder of the templates on the class path. */
    public static final String RESOURCE_DIR = "net/vectora/cloud/node/templates/";

    /** Index of the template files: {@code <sha256>  <Server>/template/<path>} per line. */
    public static final String INDEX = "templates.index";

    /** Staging folder inside {@code <home>/<Server>/} while a template is written. */
    static final String STAGING = ".vectora-template-incoming";

    private static final Pattern INDEX_LINE = Pattern.compile("([0-9a-f]{64})  ([A-Za-z][A-Za-z0-9-]{0,31})/template/(.+)");

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");

    /** Where the files come from; the class path in the node. */
    @FunctionalInterface
    public interface Source {

        /**
         * Opens one entry below {@value #RESOURCE_DIR}.
         *
         * @param name {@value #INDEX} or a path from it
         * @return the stream, or {@code null} if there is no such entry
         * @throws IOException if it cannot be opened
         */
        InputStream open(String name) throws IOException;
    }

    private record Entry(String path, String relative, String sha256) {
    }

    private final Source source;

    /**
     * Creates the templates on a source.
     *
     * @param source where {@value #INDEX} and the files are read from
     */
    public BuiltInTemplates(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * The templates on the node's own class path.
     *
     * @return the templates
     */
    public static BuiltInTemplates fromClassPath() {
        ClassLoader loader = BuiltInTemplates.class.getClassLoader();
        return new BuiltInTemplates(name -> loader.getResourceAsStream(RESOURCE_DIR + name));
    }

    /**
     * Writes every built-in template whose folder the home does not have.
     *
     * @param home the cloud home
     * @return the server folders that got their template, sorted
     * @throws IOException if the index is malformed, a file does not match it, or writing fails
     */
    public List<String> seed(Path home) throws IOException {
        Objects.requireNonNull(home, "home");
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> folder : index().entrySet()) {
            Path server = home.resolve(folder.getKey());
            Path target = server.resolve("template");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(server)) {
                continue;
            }
            Files.createDirectories(server);
            Path staging = server.resolve(STAGING);
            deleteTree(staging);
            try {
                for (Entry entry : folder.getValue()) {
                    write(entry, staging);
                }
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(staging, target);
                }
            } finally {
                deleteTree(staging);
            }
            written.add(folder.getKey());
        }
        if (!written.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "Wrote the built-in templates of {0}", String.join(", ", written));
        }
        return written;
    }

    private void write(Entry entry, Path staging) throws IOException {
        Path file = staging;
        for (String segment : entry.relative().split("/")) {
            file = file.resolve(segment);
        }
        Files.createDirectories(file.getParent());
        MessageDigest digest = sha256();
        try (InputStream in = source.open(entry.path());
             OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (in == null) {
                throw new IOException("the template file " + entry.path() + " is listed in " + INDEX + " but missing");
            }
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(entry.sha256())) {
            throw new IOException("the template file " + entry.path() + " does not match " + INDEX);
        }
    }

    /** The index, grouped by server folder in name order. */
    private Map<String, List<Entry>> index() throws IOException {
        Map<String, List<Entry>> folders = new TreeMap<>();
        try (InputStream in = source.open(INDEX)) {
            if (in == null) {
                return folders;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n", -1)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                var matcher = INDEX_LINE.matcher(trimmed);
                if (!matcher.matches() || !safeRelative(matcher.group(3))) {
                    throw new IOException(INDEX + " has a malformed line");
                }
                String relative = matcher.group(3);
                folders.computeIfAbsent(matcher.group(2), key -> new ArrayList<>())
                        .add(new Entry(matcher.group(2) + "/template/" + relative, relative, matcher.group(1)));
            }
        }
        return folders;
    }

    /** Plain segments only: no {@code ..}, no hidden names, no backslash, no empty part. */
    private static boolean safeRelative(String relative) {
        for (String segment : relative.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }

    /** Deletes the staging folder; links inside are removed as links, never followed. */
    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(dir);
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path folder, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(folder);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256.
            throw new IllegalStateException(e);
        }
    }
}
