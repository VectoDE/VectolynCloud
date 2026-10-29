package net.vectora.cloud.download.archive;

/**
 * Upper bounds for one extraction, against zip bombs and archives with millions of entries.
 * The bytes are counted while writing, never taken from the archive's own size fields.
 *
 * @param maxEntries most entries (files, directories, links) in one archive
 * @param maxTotalBytes most bytes written in total
 * @param maxEntryBytes most bytes of one file
 */
public record ExtractionLimits(int maxEntries, long maxTotalBytes, long maxEntryBytes) {

    /**
     * Checks the values.
     *
     * @param maxEntries most entries
     * @param maxTotalBytes most bytes in total
     * @param maxEntryBytes most bytes per file
     */
    public ExtractionLimits {
        if (maxEntries < 1 || maxTotalBytes < 1 || maxEntryBytes < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }

    /**
     * Returns limits that fit a MariaDB server archive (about 1.6 GB and some 10,000 files
     * unpacked on Linux) and a JRE with room to spare: 100,000 entries, 8 GiB in total,
     * 2 GiB per file.
     *
     * @return the default limits
     */
    public static ExtractionLimits defaults() {
        return new ExtractionLimits(100_000, 8L * 1024 * 1024 * 1024, 2L * 1024 * 1024 * 1024);
    }
}
