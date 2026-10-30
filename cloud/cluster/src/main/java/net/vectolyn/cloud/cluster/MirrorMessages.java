package net.vectolyn.cloud.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.vectolyn.protocol.ChannelName;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.PacketWriter;
import net.vectolyn.protocol.ProtocolException;

/**
 * Payloads of the config mirror and the template sync on a cluster session. The member asks, the
 * head answers; the head pushes "changed" when something changed. Every reader throws only
 * {@link ProtocolException} and checks every length before it allocates. A file travels in chunks
 * of at most {@link #CHUNK} bytes; a chunk answer is the raw bytes.
 */
public final class MirrorMessages {

    /** Request: a page of the config manifest ({@link ManifestRequest} to {@link Manifest}). */
    public static final ChannelName CONFIG_MANIFEST = new ChannelName("cluster.config.manifest");
    /** Request: a chunk of a mirrored config file ({@link ChunkRequest} to raw bytes). */
    public static final ChannelName CONFIG_CHUNK = new ChannelName("cluster.config.chunk");
    /** Push from the head: the config has a new revision ({@link Changed}). */
    public static final ChannelName CONFIG_CHANGED = new ChannelName("cluster.config.changed");
    /** Request: the head's templates ({@link ManifestRequest} to {@link TemplateList}). */
    public static final ChannelName TEMPLATE_LIST = new ChannelName("cluster.templates.list");
    /** Request: a page of a template's manifest ({@link ManifestRequest} to {@link Manifest}). */
    public static final ChannelName TEMPLATE_MANIFEST = new ChannelName("cluster.templates.manifest");
    /** Request: a chunk of a template file ({@link ChunkRequest} to raw bytes). */
    public static final ChannelName TEMPLATE_CHUNK = new ChannelName("cluster.templates.chunk");
    /** Push from the head: the templates changed ({@link Changed}, revision 0). */
    public static final ChannelName TEMPLATES_CHANGED = new ChannelName("cluster.templates.changed");

    /** Largest chunk of a file in one answer, 48 KiB (an answer may have 64 KiB). */
    public static final int CHUNK = 48 * 1024;
    /** Length of a SHA-256. */
    public static final int SHA256_BYTES = 32;
    /** Highest config revision (18 digits, as {@code state/cluster/config-applied} stores it). */
    public static final long MAX_REVISION = 999_999_999_999_999_999L;
    /** Entries in one page of a config manifest. */
    public static final int CONFIG_PAGE = 256;
    /** Entries in one page of a template manifest. */
    public static final int TEMPLATE_PAGE = 128;
    /** Most pages of one manifest. */
    static final int MAX_PAGES = 1024;
    /** Largest name of a template, as {@code ConfigNames.TEMPLATE}. */
    static final int MAX_NAME_BYTES = 64;

    private MirrorMessages() {
    }

    /** SHA-256 of {@code bytes}. */
    static byte[] sha256(byte[] bytes) {
        return sha256().digest(bytes);
    }

    /** A new SHA-256. */
    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from the JDK", e);
        }
    }

    /**
     * The digest of a set of files: SHA-256 over every entry in path order (path, a zero byte,
     * size as 8 bytes, its SHA-256). The same files give the same digest on every node.
     *
     * @param entries the entries, sorted by path
     * @return 32 bytes
     */
    static byte[] digest(List<FileEntry> entries) {
        MessageDigest digest = sha256();
        for (FileEntry entry : entries) {
            digest.update(entry.path().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            long size = entry.size();
            for (int shift = 56; shift >= 0; shift -= 8) {
                digest.update((byte) (size >>> shift));
            }
            digest.update(entry.sha256Bytes());
        }
        return digest.digest();
    }

    private static byte[] readSha(PacketReader in) {
        byte[] value = in.readByteArray(SHA256_BYTES);
        if (value.length != SHA256_BYTES) {
            throw new ProtocolException("a SHA-256 has 32 bytes");
        }
        return value;
    }

    private static long readSize(PacketReader in) {
        long value = in.readVarLong();
        if (value < 0) {
            throw new ProtocolException("negative size");
        }
        return value;
    }

    private static int readCount(PacketReader in, int max) {
        int value = in.readVarInt();
        if (value < 0 || value > max) {
            throw new ProtocolException("count " + value + " out of range");
        }
        return value;
    }

    private static String readName(PacketReader in) {
        String name = in.readString(MAX_NAME_BYTES);
        if (!name.isEmpty() && !MirrorPaths.isTemplateName(name)) {
            throw new ProtocolException("invalid template name");
        }
        return name;
    }

    /**
     * The head pushes a new config revision, or (revision 0) that the templates changed.
     *
     * @param revision the revision, 0 or more
     */
    public record Changed(long revision) {

        /** Checks the value. */
        public Changed {
            if (revision < 0 || revision > MAX_REVISION) {
                throw new IllegalArgumentException("invalid revision");
            }
        }

        /** Reads the payload. */
        public static Changed read(PacketReader in) {
            long revision = readSize(in);
            in.expectEnd();
            return new Changed(revision);
        }

        /** Writes the payload. */
        public byte[] write() {
            return new PacketWriter().writeVarLong(revision).toByteArray();
        }
    }

    /**
     * A member asks for a page of a manifest, or for the template list (page 0, no name).
     *
     * @param template the template, empty for the config and the template list
     * @param digest   the digest the member got on page 0 (32 bytes), empty for page 0
     * @param page     the page, from 0
     */
    public record ManifestRequest(String template, byte[] digest, int page) {

        /** Checks the values. */
        public ManifestRequest {
            Objects.requireNonNull(template, "template");
            Objects.requireNonNull(digest, "digest");
            if (!template.isEmpty() && !MirrorPaths.isTemplateName(template)) {
                throw new IllegalArgumentException("invalid template name");
            }
            if (page < 0 || page >= MAX_PAGES || (digest.length != 0 && digest.length != SHA256_BYTES)
                    || (page > 0 && digest.length == 0)) {
                throw new IllegalArgumentException("invalid page request");
            }
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }

        /** Reads the payload. */
        public static ManifestRequest read(PacketReader in) {
            String template = readName(in);
            byte[] digest = in.readByteArray(SHA256_BYTES);
            int page = readCount(in, MAX_PAGES - 1);
            in.expectEnd();
            try {
                return new ManifestRequest(template, digest, page);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(e.getMessage(), e);
            }
        }

        /** Writes the payload. */
        public byte[] write() {
            return new PacketWriter().writeString(template, MAX_NAME_BYTES).writeByteArray(digest, SHA256_BYTES)
                    .writeVarInt(page).toByteArray();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ManifestRequest r && template.equals(r.template) && Arrays.equals(digest, r.digest)
                    && page == r.page;
        }

        @Override
        public int hashCode() {
            return Objects.hash(template, Arrays.hashCode(digest), page);
        }

        @Override
        public String toString() {
            return "ManifestRequest[" + template + ", page " + page + "]";
        }
    }

    /**
     * One file of a manifest.
     *
     * @param path   relative path with {@code /}, checked by {@link MirrorPaths#isSafePath}
     * @param size   size in bytes
     * @param sha256 SHA-256 of the content (32 bytes)
     */
    public record FileEntry(String path, long size, byte[] sha256) {

        /** Checks the values. */
        public FileEntry {
            if (path == null || !MirrorPaths.isSafePath(path)) {
                throw new IllegalArgumentException("unsafe path");
            }
            if (size < 0 || sha256 == null || sha256.length != SHA256_BYTES) {
                throw new IllegalArgumentException("invalid entry");
            }
            sha256 = sha256.clone();
        }

        @Override
        public byte[] sha256() {
            return sha256.clone();
        }

        /** The SHA-256 without a copy, for the digest. */
        byte[] sha256Bytes() {
            return sha256;
        }

        static FileEntry read(PacketReader in) {
            String path = in.readString(MirrorPaths.MAX_PATH_BYTES);
            long size = readSize(in);
            byte[] sha = readSha(in);
            try {
                return new FileEntry(path, size, sha);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(e.getMessage(), e);
            }
        }

        void write(PacketWriter out) {
            out.writeString(path, MirrorPaths.MAX_PATH_BYTES).writeVarLong(size).writeByteArray(sha256, SHA256_BYTES);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FileEntry e && path.equals(e.path) && size == e.size && Arrays.equals(sha256, e.sha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, size, Arrays.hashCode(sha256));
        }

        @Override
        public String toString() {
            return "FileEntry[" + path + ", " + size + " bytes]";
        }
    }

    /**
     * A page of the config manifest or of a template's manifest.
     *
     * @param revision the config revision; 0 for a template
     * @param digest   digest of the whole set ({@link #digest(List)}), 32 bytes
     * @param total    entries in the whole set
     * @param page     this page
     * @param entries  the entries of this page, sorted by path
     */
    public record Manifest(long revision, byte[] digest, int total, int page, List<FileEntry> entries) {

        /** Checks the values. */
        public Manifest {
            Objects.requireNonNull(digest, "digest");
            entries = List.copyOf(entries);
            if (revision < 0 || revision > MAX_REVISION || digest.length != SHA256_BYTES || total < 0 || page < 0 || page >= MAX_PAGES
                    || entries.size() > Math.max(CONFIG_PAGE, TEMPLATE_PAGE)) {
                throw new IllegalArgumentException("invalid manifest");
            }
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }

        /** Reads the payload. */
        public static Manifest read(PacketReader in) {
            long revision = readSize(in);
            byte[] digest = readSha(in);
            int total = readCount(in, Integer.MAX_VALUE);
            int page = readCount(in, MAX_PAGES - 1);
            List<FileEntry> entries = in.readList(Math.max(CONFIG_PAGE, TEMPLATE_PAGE), FileEntry::read);
            in.expectEnd();
            try {
                return new Manifest(revision, digest, total, page, entries);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(e.getMessage(), e);
            }
        }

        /** Writes the payload. */
        public byte[] write() {
            PacketWriter out = new PacketWriter().writeVarLong(revision).writeByteArray(digest, SHA256_BYTES)
                    .writeVarInt(total).writeVarInt(page);
            out.writeList(entries, Math.max(CONFIG_PAGE, TEMPLATE_PAGE), (o, e) -> e.write(o));
            return out.toByteArray();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Manifest m && revision == m.revision && Arrays.equals(digest, m.digest)
                    && total == m.total && page == m.page && entries.equals(m.entries);
        }

        @Override
        public int hashCode() {
            return Objects.hash(revision, Arrays.hashCode(digest), total, page, entries);
        }

        @Override
        public String toString() {
            return "Manifest[revision " + revision + ", " + total + " entries, page " + page + "]";
        }
    }

    /**
     * A member asks for a chunk of a file.
     *
     * @param template the template, empty for a config file
     * @param digest   the digest of the set the file belongs to (32 bytes); the head answers
     *                 {@link ClusterRpcException#CHANGED} if the set is no longer that
     * @param path     the file
     * @param offset   where the chunk starts
     * @param length   bytes wanted, 1 to {@link #CHUNK}
     */
    public record ChunkRequest(String template, byte[] digest, String path, long offset, int length) {

        /** Checks the values. */
        public ChunkRequest {
            Objects.requireNonNull(template, "template");
            if (!template.isEmpty() && !MirrorPaths.isTemplateName(template)) {
                throw new IllegalArgumentException("invalid template name");
            }
            if (digest == null || digest.length != SHA256_BYTES || path == null || !MirrorPaths.isSafePath(path)
                    || offset < 0 || length < 1 || length > CHUNK) {
                throw new IllegalArgumentException("invalid chunk request");
            }
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }

        /** Reads the payload. */
        public static ChunkRequest read(PacketReader in) {
            String template = readName(in);
            byte[] digest = readSha(in);
            String path = in.readString(MirrorPaths.MAX_PATH_BYTES);
            long offset = readSize(in);
            int length = readCount(in, CHUNK);
            in.expectEnd();
            try {
                return new ChunkRequest(template, digest, path, offset, length);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(e.getMessage(), e);
            }
        }

        /** Writes the payload. */
        public byte[] write() {
            return new PacketWriter().writeString(template, MAX_NAME_BYTES).writeByteArray(digest, SHA256_BYTES)
                    .writeString(path, MirrorPaths.MAX_PATH_BYTES).writeVarLong(offset).writeVarInt(length)
                    .toByteArray();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ChunkRequest r && template.equals(r.template) && Arrays.equals(digest, r.digest)
                    && path.equals(r.path) && offset == r.offset && length == r.length;
        }

        @Override
        public int hashCode() {
            return Objects.hash(template, Arrays.hashCode(digest), path, offset, length);
        }

        @Override
        public String toString() {
            return "ChunkRequest[" + template + ":" + path + " @" + offset + "+" + length + "]";
        }
    }

    /**
     * One template of the head.
     *
     * @param name   its folder under {@code local/templates/}
     * @param digest digest of its files (32 bytes)
     * @param files  number of files
     * @param bytes  their total size
     */
    public record TemplateEntry(String name, byte[] digest, int files, long bytes) {

        /** Checks the values. */
        public TemplateEntry {
            if (name == null || !MirrorPaths.isTemplateName(name) || digest == null || digest.length != SHA256_BYTES
                    || files < 0 || bytes < 0) {
                throw new IllegalArgumentException("invalid template entry");
            }
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }

        static TemplateEntry read(PacketReader in) {
            String name = in.readString(MAX_NAME_BYTES);
            byte[] digest = readSha(in);
            int files = readCount(in, Integer.MAX_VALUE);
            long bytes = readSize(in);
            try {
                return new TemplateEntry(name, digest, files, bytes);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(e.getMessage(), e);
            }
        }

        void write(PacketWriter out) {
            out.writeString(name, MAX_NAME_BYTES).writeByteArray(digest, SHA256_BYTES).writeVarInt(files)
                    .writeVarLong(bytes);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TemplateEntry e && name.equals(e.name) && Arrays.equals(digest, e.digest)
                    && files == e.files && bytes == e.bytes;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, Arrays.hashCode(digest), files, bytes);
        }

        @Override
        public String toString() {
            return "TemplateEntry[" + name + ", " + files + " files, " + bytes + " bytes]";
        }
    }

    /**
     * The head's templates.
     *
     * @param templates sorted by name, at most {@link MirrorPaths#MAX_TEMPLATES}
     */
    public record TemplateList(List<TemplateEntry> templates) {

        /** Checks the values. */
        public TemplateList {
            templates = List.copyOf(templates);
            if (templates.size() > MirrorPaths.MAX_TEMPLATES) {
                throw new IllegalArgumentException("too many templates");
            }
        }

        /** Reads the payload. */
        public static TemplateList read(PacketReader in) {
            List<TemplateEntry> templates = in.readList(MirrorPaths.MAX_TEMPLATES, TemplateEntry::read);
            in.expectEnd();
            return new TemplateList(templates);
        }

        /** Writes the payload. */
        public byte[] write() {
            return new PacketWriter().writeList(templates, MirrorPaths.MAX_TEMPLATES, (o, e) -> e.write(o)).toByteArray();
        }
    }
}
