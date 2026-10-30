package net.vectolyn.cloud.cluster;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.vectolyn.cloud.cluster.MirrorMessages.ChunkRequest;
import net.vectolyn.cloud.cluster.MirrorMessages.FileEntry;
import net.vectolyn.cloud.cluster.MirrorMessages.Manifest;
import net.vectolyn.cloud.cluster.MirrorMessages.ManifestRequest;
import net.vectolyn.protocol.ChannelName;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.ProtocolException;

/**
 * The member's side of a transfer: reads a whole manifest page by page and downloads a file in
 * chunks, checking everything the head sends against the limits before it is used.
 */
final class MirrorClient {

    /** Timeout of one request. */
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** Chunk requests open at a time for one file. */
    static final int WINDOW = 8;

    private MirrorClient() {
    }

    /**
     * A whole manifest.
     *
     * @param revision the config revision, 0 for a template
     * @param digest   the digest the head named, checked against the entries
     * @param entries  every entry, sorted by path, paths unique also ignoring case
     */
    record Listing(long revision, byte[] digest, List<FileEntry> entries) {
    }

    /** Receives the chunks of a file, in order. */
    @FunctionalInterface
    interface Sink {
        void write(byte[] chunk) throws IOException;
    }

    /**
     * Reads every page of a manifest and checks it: the pages belong together, the entries are
     * sorted and unique (also ignoring case, for case-insensitive file systems), each file and the
     * sum stay within the limits, and the digest is the digest of the entries.
     */
    static Listing manifest(ClusterSession session, ChannelName channel, String template, int pageSize,
                            int maxEntries, long maxFile, long maxTotal) throws IOException {
        Manifest first = decode(session.call(channel, new ManifestRequest(template, new byte[0], 0).write(), TIMEOUT));
        if (first.page() != 0 || first.total() > maxEntries) {
            throw new MirrorException("the manifest has more than " + maxEntries + " entries");
        }
        List<FileEntry> all = new ArrayList<>(first.total());
        int pages = Math.max(1, (first.total() + pageSize - 1) / pageSize);
        Manifest page = first;
        for (int p = 0; p < pages; p++) {
            if (p > 0) {
                page = decode(session.call(channel, new ManifestRequest(template, first.digest(), p).write(), TIMEOUT));
            }
            int expected = Math.min(pageSize, first.total() - p * pageSize);
            if (page.page() != p || page.revision() != first.revision() || page.total() != first.total()
                    || !MessageDigest.isEqual(page.digest(), first.digest()) || page.entries().size() != expected) {
                throw new MirrorException("the pages of the manifest do not belong together");
            }
            all.addAll(page.entries());
        }
        long total = 0;
        Set<String> folded = new HashSet<>();
        for (int i = 0; i < all.size(); i++) {
            FileEntry entry = all.get(i);
            if (i > 0 && all.get(i - 1).path().compareTo(entry.path()) >= 0) {
                throw new MirrorException("the manifest is not sorted");
            }
            if (!folded.add(entry.path().toLowerCase(Locale.ROOT))) {
                throw new MirrorException("two paths differ in case only");
            }
            if (entry.size() > maxFile) {
                throw new MirrorException(entry.path() + " is larger than " + maxFile + " bytes");
            }
            total += entry.size();
            if (total > maxTotal) {
                throw new MirrorException("the files are larger than " + maxTotal + " bytes together");
            }
        }
        if (!MessageDigest.isEqual(MirrorMessages.digest(all), first.digest())) {
            throw new MirrorException("the manifest's digest does not match its entries");
        }
        return new Listing(first.revision(), first.digest(), List.copyOf(all));
    }

    private static Manifest decode(byte[] payload) throws MirrorException {
        try {
            return Manifest.read(new PacketReader(payload));
        } catch (ProtocolException e) {
            throw new MirrorException("invalid manifest");
        }
    }

    /**
     * Downloads a file in chunks, up to {@link #WINDOW} requests at a time, hands them to the sink
     * in order and checks size and SHA-256 at the end.
     *
     * @throws MirrorException if a chunk has the wrong size or the SHA-256 differs
     */
    static void fetch(ClusterSession session, ChannelName channel, String template, byte[] digest, FileEntry entry,
                      Sink sink) throws IOException {
        MessageDigest sha = MirrorMessages.sha256();
        ArrayDeque<CompletableFuture<byte[]>> window = new ArrayDeque<>();
        ArrayDeque<Integer> lengths = new ArrayDeque<>();
        long next = 0;
        long received = 0;
        try {
            while (received < entry.size()) {
                while (next < entry.size() && window.size() < WINDOW) {
                    int length = (int) Math.min(MirrorMessages.CHUNK, entry.size() - next);
                    window.addLast(session.request(channel,
                            new ChunkRequest(template, digest, entry.path(), next, length).write(), TIMEOUT));
                    lengths.addLast(length);
                    next += length;
                }
                byte[] chunk = await(window.removeFirst());
                if (chunk.length != lengths.removeFirst()) {
                    throw new MirrorException(entry.path() + ": a chunk has the wrong size");
                }
                sha.update(chunk);
                sink.write(chunk);
                received += chunk.length;
            }
        } finally {
            for (CompletableFuture<byte[]> open : window) {
                open.cancel(false);
            }
        }
        if (!MessageDigest.isEqual(sha.digest(), entry.sha256Bytes())) {
            throw new MirrorException(entry.path() + ": the SHA-256 does not match");
        }
    }

    private static byte[] await(CompletableFuture<byte[]> future) throws ClusterRpcException {
        try {
            return future.get(TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ClusterRpcException rpc) {
                throw rpc;
            }
            throw new ClusterRpcException(e.getCause() instanceof TimeoutException ? ClusterRpcException.TIMEOUT
                    : ClusterRpcException.FAILED);
        } catch (TimeoutException e) {
            throw new ClusterRpcException(ClusterRpcException.TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClusterRpcException(ClusterRpcException.CLOSED);
        }
    }
}
