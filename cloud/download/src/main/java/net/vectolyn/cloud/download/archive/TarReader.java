package net.vectolyn.cloud.download.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Minimal, strict reader for TAR streams in the formats the cloud meets: POSIX ustar, pax
 * extended headers ({@code path}, {@code linkpath}, {@code size}) and the GNU long-name
 * extensions ({@code L}, {@code K}) that GNU tar writes for MariaDB's Linux archives.
 *
 * <p>Strict means: every header checksum is verified, unknown magic and entry types (devices,
 * FIFOs, sparse files) are refused, sizes in base-256 are refused, metadata records are limited
 * to {@value #MAX_METADATA_BYTES} bytes, and a stream that ends before the end-of-archive block
 * is an error. Every failure is an {@link ArchiveException}; the reader does not check paths,
 * that is {@link SafeExtractor}'s job.
 */
public final class TarReader {

    /** TAR block size. */
    public static final int BLOCK = 512;

    /** Largest pax or GNU long-name record accepted. */
    public static final int MAX_METADATA_BYTES = 64 * 1024;

    private final InputStream in;
    private final byte[] header = new byte[BLOCK];
    private long remaining;
    private long padding;
    private boolean finished;

    /**
     * Creates the reader. It does not close {@code in}.
     *
     * @param in the uncompressed TAR stream
     */
    public TarReader(InputStream in) {
        this.in = Objects.requireNonNull(in, "in");
    }

    /**
     * Moves to the next entry, skipping the rest of the current one.
     *
     * @return the next entry, or {@code null} at the end of the archive
     * @throws ArchiveException if the archive is malformed
     * @throws IOException if reading fails
     */
    public TarEntry next() throws IOException {
        if (finished) {
            return null;
        }
        skip(remaining + padding);
        remaining = 0;
        padding = 0;

        String paxPath = null;
        String paxLink = null;
        long paxSize = -1;
        String longName = null;
        String longLink = null;
        int metadataHeaders = 0;
        while (true) {
            if (!readBlock(header, true)) {
                throw new ArchiveException("TAR stream ends without an end-of-archive block");
            }
            if (isZero(header)) {
                finished = true;
                return null;
            }
            verifyChecksum();
            boolean gnu = checkMagic();
            byte flag = header[156];
            long size = octal(124, 12, "size");
            if (flag == 'x' || flag == 'g' || flag == 'L' || flag == 'K') {
                if (++metadataHeaders > 8) {
                    throw new ArchiveException("too many extended headers before one entry");
                }
                byte[] data = readMetadata(size);
                switch (flag) {
                    case 'x' -> {
                        PaxHeaders pax = PaxHeaders.parse(data);
                        paxPath = pax.path != null ? pax.path : paxPath;
                        paxLink = pax.linkPath != null ? pax.linkPath : paxLink;
                        paxSize = pax.size >= 0 ? pax.size : paxSize;
                    }
                    case 'L' -> longName = cString(data, 0, data.length, "GNU long name");
                    case 'K' -> longLink = cString(data, 0, data.length, "GNU long link");
                    default -> {
                        // 'g': global pax header. None of its keys matter here.
                    }
                }
                continue;
            }
            TarEntry.Type type = switch (flag) {
                case '0', 0, '7' -> TarEntry.Type.FILE;
                case '5' -> TarEntry.Type.DIRECTORY;
                case '2' -> TarEntry.Type.SYMLINK;
                case '1' -> TarEntry.Type.HARDLINK;
                default -> throw new ArchiveException("unsupported TAR entry type '" + printable(flag) + "'");
            };
            String name;
            if (paxPath != null) {
                name = paxPath;
            } else if (longName != null) {
                name = longName;
            } else {
                name = cString(header, 0, 100, "name");
                if (!gnu) {
                    String prefix = cString(header, 345, 155, "prefix");
                    if (!prefix.isEmpty()) {
                        name = prefix + "/" + name;
                    }
                }
            }
            String linkName = paxLink != null ? paxLink : longLink != null ? longLink : cString(header, 157, 100, "linkname");
            if (paxSize >= 0) {
                size = paxSize;
            }
            int mode = (int) (octal(100, 8, "mode") & 07777);
            if (name.isEmpty()) {
                throw new ArchiveException("TAR entry without a name");
            }
            if ((type == TarEntry.Type.SYMLINK || type == TarEntry.Type.HARDLINK) && linkName.isEmpty()) {
                throw new ArchiveException("link without a target: " + name);
            }
            if (type != TarEntry.Type.FILE && size != 0 && type != TarEntry.Type.DIRECTORY) {
                throw new ArchiveException("link entry with content: " + name);
            }
            long pad = (BLOCK - size % BLOCK) % BLOCK;
            if (type != TarEntry.Type.FILE) {
                // Directory content, if any, is skipped and never handed out; links have none.
                remaining = 0;
                padding = size + pad;
                return new TarEntry(name, type, 0, mode, type == TarEntry.Type.DIRECTORY ? "" : linkName);
            }
            remaining = size;
            padding = pad;
            return new TarEntry(name, type, size, mode, "");
        }
    }

    /**
     * Returns a stream over the content of the current entry. It ends at the end of the entry and
     * does not close the underlying stream.
     *
     * @return the content stream
     */
    public InputStream content() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, buffer.length);
                if (remaining <= 0) {
                    return -1;
                }
                if (length == 0) {
                    return 0;
                }
                int n = in.read(buffer, offset, (int) Math.min(length, remaining));
                if (n < 0) {
                    throw new ArchiveException("TAR stream ends inside an entry");
                }
                remaining -= n;
                return n;
            }
        };
    }

    private byte[] readMetadata(long size) throws IOException {
        if (size > MAX_METADATA_BYTES) {
            throw new ArchiveException("extended header of " + size + " bytes, the limit is " + MAX_METADATA_BYTES);
        }
        byte[] data = new byte[(int) size];
        readFully(data, 0, data.length);
        skip((BLOCK - size % BLOCK) % BLOCK);
        return data;
    }

    private boolean readBlock(byte[] block, boolean eofAllowedAtStart) throws IOException {
        int offset = 0;
        while (offset < BLOCK) {
            int n = in.read(block, offset, BLOCK - offset);
            if (n < 0) {
                if (offset == 0 && eofAllowedAtStart) {
                    return false;
                }
                throw new ArchiveException("TAR stream ends inside a header");
            }
            offset += n;
        }
        return true;
    }

    private void readFully(byte[] data, int offset, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int n = in.read(data, offset + done, length - done);
            if (n < 0) {
                throw new ArchiveException("TAR stream ends inside an extended header");
            }
            done += n;
        }
    }

    private void skip(long count) throws IOException {
        byte[] buffer = new byte[8192];
        long left = count;
        while (left > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) {
                throw new ArchiveException("TAR stream ends inside an entry");
            }
            left -= n;
        }
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private void verifyChecksum() throws IOException {
        long stored = octal(148, 8, "checksum");
        long unsigned = 0;
        long signed = 0;
        for (int i = 0; i < BLOCK; i++) {
            int b = (i >= 148 && i < 156) ? ' ' : header[i];
            unsigned += b & 0xff;
            signed += (i >= 148 && i < 156) ? ' ' : header[i];
        }
        if (stored != unsigned && stored != signed) {
            throw new ArchiveException("TAR header checksum mismatch");
        }
    }

    /** Accepts POSIX "ustar\0" + "00" and GNU "ustar " + " \0". Returns true for GNU. */
    private boolean checkMagic() throws IOException {
        boolean posix = header[257] == 'u' && header[258] == 's' && header[259] == 't' && header[260] == 'a'
                && header[261] == 'r' && header[262] == 0 && header[263] == '0' && header[264] == '0';
        boolean gnu = header[257] == 'u' && header[258] == 's' && header[259] == 't' && header[260] == 'a'
                && header[261] == 'r' && header[262] == ' ' && header[263] == ' ' && header[264] == 0;
        if (!posix && !gnu) {
            throw new ArchiveException("not a ustar, pax or GNU TAR header");
        }
        return gnu;
    }

    private long octal(int offset, int length, String field) throws IOException {
        if ((header[offset] & 0x80) != 0) {
            throw new ArchiveException("TAR field " + field + " uses base-256, which is not supported");
        }
        int i = offset;
        int end = offset + length;
        while (i < end && header[i] == ' ') {
            i++;
        }
        long value = 0;
        int digits = 0;
        while (i < end && header[i] >= '0' && header[i] <= '7') {
            value = value * 8 + (header[i] - '0');
            digits++;
            i++;
        }
        while (i < end) {
            if (header[i] != 0 && header[i] != ' ') {
                throw new ArchiveException("TAR field " + field + " is not an octal number");
            }
            i++;
        }
        if (digits > 11 && field.equals("size")) {
            throw new ArchiveException("TAR size field too long");
        }
        return value;
    }

    private static String cString(byte[] data, int offset, int length, String field) throws ArchiveException {
        int end = offset;
        while (end < offset + length && data[end] != 0) {
            end++;
        }
        return utf8(data, offset, end - offset, field);
    }

    static String utf8(byte[] data, int offset, int length, String field) throws ArchiveException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, length))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ArchiveException("TAR " + field + " is not valid UTF-8", e);
        }
    }

    private static String printable(byte flag) {
        return flag >= 0x20 && flag < 0x7f ? Character.toString((char) flag) : "0x" + Integer.toHexString(flag & 0xff);
    }

    /** The pax keys this reader uses. */
    private static final class PaxHeaders {

        String path;
        String linkPath;
        long size = -1;

        /** Parses records of the form {@code "<length> <key>=<value>\n"}. */
        static PaxHeaders parse(byte[] data) throws ArchiveException {
            PaxHeaders headers = new PaxHeaders();
            int position = 0;
            while (position < data.length) {
                int space = position;
                long length = 0;
                while (space < data.length && data[space] >= '0' && data[space] <= '9') {
                    length = length * 10 + (data[space] - '0');
                    if (length > data.length) {
                        throw new ArchiveException("pax record length out of range");
                    }
                    space++;
                }
                if (space == position || space >= data.length || data[space] != ' ') {
                    throw new ArchiveException("malformed pax record");
                }
                int recordEnd = position + (int) length;
                if (length <= space - position + 1 || recordEnd > data.length || data[recordEnd - 1] != '\n') {
                    throw new ArchiveException("malformed pax record");
                }
                int equals = -1;
                for (int i = space + 1; i < recordEnd - 1; i++) {
                    if (data[i] == '=') {
                        equals = i;
                        break;
                    }
                }
                if (equals < 0) {
                    throw new ArchiveException("pax record without '='");
                }
                String key = utf8(data, space + 1, equals - space - 1, "pax key");
                String value = utf8(data, equals + 1, recordEnd - 1 - equals - 1, "pax value");
                switch (key) {
                    case "path" -> headers.path = value;
                    case "linkpath" -> headers.linkPath = value;
                    case "size" -> headers.size = parseSize(value);
                    default -> {
                        // mtime, uid, uname and the like are not needed.
                    }
                }
                position = recordEnd;
            }
            return headers;
        }

        private static long parseSize(String value) throws ArchiveException {
            if (value.isEmpty() || value.length() > 18 || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new ArchiveException("pax size is not a number");
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new ArchiveException("pax size out of range", e);
            }
        }
    }
}
