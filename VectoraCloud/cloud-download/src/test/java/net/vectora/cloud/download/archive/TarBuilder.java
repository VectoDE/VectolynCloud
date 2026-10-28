package net.vectora.cloud.download.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/** Writes small TAR archives for tests, including hostile ones the real tar tools refuse to make. */
final class TarBuilder {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private boolean gnu;

    TarBuilder gnu() {
        this.gnu = true;
        return this;
    }

    TarBuilder file(String name, String content) {
        return entry(name, '0', content.getBytes(StandardCharsets.UTF_8), "", 0644);
    }

    TarBuilder file(String name, byte[] content, int mode) {
        return entry(name, '0', content, "", mode);
    }

    TarBuilder directory(String name) {
        return entry(name, '5', new byte[0], "", 0755);
    }

    TarBuilder symlink(String name, String target) {
        return entry(name, '2', new byte[0], target, 0777);
    }

    TarBuilder hardlink(String name, String target) {
        return entry(name, '1', new byte[0], target, 0644);
    }

    TarBuilder type(String name, char type) {
        return entry(name, type, new byte[0], "", 0644);
    }

    TarBuilder gnuLongName(String name, String content) {
        byte[] longName = (name + "\0").getBytes(StandardCharsets.UTF_8);
        entry("././@LongLink", 'L', longName, "", 0644);
        return entry(name.substring(0, Math.min(99, name.length())), '0', content.getBytes(StandardCharsets.UTF_8), "", 0644);
    }

    TarBuilder paxPath(String path, String content) {
        String record = " path=" + path + "\n";
        int recordBytes = record.getBytes(StandardCharsets.UTF_8).length;
        int length = recordBytes + 1;
        while (Integer.toString(length).length() + recordBytes != length) {
            length = Integer.toString(length).length() + recordBytes;
        }
        byte[] pax = (length + record).getBytes(StandardCharsets.UTF_8);
        entry("PaxHeaders/x", 'x', pax, "", 0644);
        return entry("short-name", '0', content.getBytes(StandardCharsets.UTF_8), "", 0644);
    }

    TarBuilder raw(byte[] block) {
        out.writeBytes(block);
        return this;
    }

    TarBuilder entry(String name, char type, byte[] content, String link, int mode) {
        byte[] header = header(name, type, content.length, link, mode, gnu);
        out.writeBytes(header);
        out.writeBytes(content);
        int padding = (TarReader.BLOCK - content.length % TarReader.BLOCK) % TarReader.BLOCK;
        out.writeBytes(new byte[padding]);
        return this;
    }

    static byte[] header(String name, char type, long size, String link, int mode, boolean gnu) {
        byte[] header = new byte[TarReader.BLOCK];
        put(header, 0, 100, name);
        put(header, 100, 8, String.format("%07o", mode));
        put(header, 108, 8, "0000000");
        put(header, 116, 8, "0000000");
        put(header, 124, 12, String.format("%011o", size));
        put(header, 136, 12, "00000000000");
        header[156] = (byte) type;
        put(header, 157, 100, link);
        if (gnu) {
            put(header, 257, 8, "ustar  ");
        } else {
            put(header, 257, 6, "ustar");
            put(header, 263, 2, "00");
        }
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        long sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }
        put(header, 148, 8, String.format("%06o", sum) + "\0 ");
        return header;
    }

    private static void put(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }

    byte[] build() {
        out.writeBytes(new byte[TarReader.BLOCK * 2]);
        return out.toByteArray();
    }

    byte[] buildWithoutEnd() {
        return out.toByteArray();
    }

    byte[] buildGzip() {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gz)) {
            gzip.write(build());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return gz.toByteArray();
    }
}
