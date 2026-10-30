package net.vectolyn.cloud.node.software;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import net.vectolyn.cloud.config.io.AtomicFileWriter;
import net.vectolyn.cloud.download.FileNames;

/**
 * The list of files the cloud put into a plugin or mod folder, {@value #FILE} in that folder.
 *
 * <p>On every start the node writes what it installed; files it installed last time and not now
 * (a plugin switched off, a new version with another file name) are deleted. Files an operator put
 * there by hand are never on the list and are never touched. Only plain base names count, so the
 * list cannot point outside the folder, and only regular files are deleted.
 */
final class ManagedFiles {

    /** The list in each folder. */
    static final String FILE = ".vectolyn-managed";

    private static final System.Logger LOG = System.getLogger(ManagedFiles.class.getName());

    private ManagedFiles() {
    }

    /**
     * Reads the list of a folder.
     *
     * @param directory the folder
     * @return the names on it; empty if there is none or it is unreadable
     */
    static Set<String> read(Path directory) {
        Path file = directory.resolve(FILE);
        Set<String> names = new TreeSet<>();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return names;
        }
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            String text = new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8);
            for (String line : text.split("\n", -1)) {
                String name = line.strip();
                if (!name.isEmpty() && !name.startsWith("#") && FileNames.isSafeBaseName(name) && !FILE.equals(name)) {
                    names.add(name);
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read {0}; files of an earlier start may stay", file);
        }
        return names;
    }

    /**
     * Deletes what the last start installed and this one does not, then writes the new list.
     *
     * @param directory the folder
     * @param current   what this start installed
     * @throws IOException if the list cannot be written
     */
    static void replace(Path directory, Set<String> current) throws IOException {
        for (String old : read(directory)) {
            if (current.contains(old)) {
                continue;
            }
            Path file = directory.resolve(old);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                Files.deleteIfExists(file);
                LOG.log(System.Logger.Level.INFO, "Removed {0}: it is no longer part of the group", file);
            }
        }
        StringBuilder text = new StringBuilder("# Installed by VectolynCloud; removed again when no longer needed.\n");
        for (String name : new TreeSet<>(current)) {
            text.append(name).append('\n');
        }
        Files.createDirectories(directory);
        new AtomicFileWriter().write(directory.resolve(FILE), text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
