// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * The files of the panel frontend, read once from the jar.
 *
 * <p>The Gradle build copies the static export of the Next.js app (or a placeholder page when the
 * frontend is not built) to {@code net/vectolyn/cloud/panel/web/} and writes {@code files.index}
 * with one path per line, because a class loader cannot list a folder. Each file gets:
 * <ul>
 *   <li>a strong {@code ETag} from the SHA-256 of its content, so a browser revalidates with
 *       {@code If-None-Match} and gets {@code 304} without the body;</li>
 *   <li>a {@code Cache-Control}: one year and {@code immutable} for the hashed files under
 *       {@code _next/static/}, {@code no-cache} (always revalidate) for everything else;</li>
 *   <li>a gzip copy for text files when it is smaller;</li>
 *   <li>for HTML a {@code Content-Security-Policy} that allows exactly the inline scripts and
 *       styles of that page by their SHA-256, and nothing inline beyond them.</li>
 * </ul>
 */
public final class WebAssets {

    /** Folder of the files in the jar. */
    public static final String ROOT = "net/vectolyn/cloud/panel/web/";

    /** The list of files in {@link #ROOT}. */
    public static final String INDEX = "files.index";

    /** Largest single file. */
    static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

    private static final Pattern INLINE_SCRIPT = Pattern.compile(
            "<script(?![^>]*\\bsrc\\s*=)[^>]*>(.*?)</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_STYLE = Pattern.compile(
            "<style[^>]*>(.*?)</style>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_.~@()+-]+(/[A-Za-z0-9_.~@()+-]+)*");

    private final Map<String, Asset> files;
    private final boolean placeholder;

    private WebAssets(Map<String, Asset> files, boolean placeholder) {
        this.files = Map.copyOf(files);
        this.placeholder = placeholder;
    }

    /**
     * One file.
     *
     * @param path         path below the root, without a leading slash
     * @param body         the content
     * @param gzip         the content compressed with gzip, or null if that is not smaller
     * @param contentType  media type with charset for text
     * @param etag         strong ETag, quoted
     * @param cacheControl the {@code Cache-Control} value
     * @param csp          the {@code Content-Security-Policy} for an HTML page, or null
     */
    // The bodies are sent as they are on every request; a List<Byte> would only cost memory.
    @SuppressWarnings("ArrayRecordComponent")
    public record Asset(String path, byte[] body, byte @Nullable [] gzip, String contentType, String etag,
                        String cacheControl, @Nullable String csp) {
    }

    /**
     * Reads the files listed in the index from the class path.
     *
     * @param loader the class loader of the panel
     * @param secure whether the panel runs with TLS ({@code upgrade-insecure-requests} in the CSP)
     * @return the files
     * @throws UncheckedIOException if the index or a listed file cannot be read
     */
    public static WebAssets load(ClassLoader loader, boolean secure) {
        Map<String, byte[]> content = new HashMap<>();
        try {
            byte[] index = read(loader, ROOT + INDEX);
            for (String line : new String(index, StandardCharsets.UTF_8).split("\n", -1)) {
                String path = line.strip();
                if (path.isEmpty()) {
                    continue;
                }
                if (!PATH.matcher(path).matches() || path.contains("..")) {
                    throw new IOException("invalid path in " + INDEX);
                }
                content.put(path, read(loader, ROOT + path));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The panel files cannot be read from the jar: " + e.getMessage(), e);
        }
        return of(content, secure);
    }

    /**
     * Builds the assets from content in memory (tests and {@link #load}).
     *
     * @param content path to content
     * @param secure  whether the panel runs with TLS
     * @return the files
     */
    public static WebAssets of(Map<String, byte[]> content, boolean secure) {
        Map<String, Asset> files = new HashMap<>();
        content.forEach((path, body) -> files.put(path, asset(path, body, secure)));
        return new WebAssets(files, content.containsKey("placeholder.txt"));
    }

    /**
     * A file by its path below the root.
     *
     * @param path the path without a leading slash
     * @return the file, or empty
     */
    public Optional<Asset> get(String path) {
        return Optional.ofNullable(files.get(path));
    }

    /**
     * Whether the jar carries the placeholder page instead of the built frontend.
     *
     * @return true for a build without the frontend
     */
    public boolean placeholder() {
        return placeholder;
    }

    /**
     * Number of files.
     *
     * @return the count
     */
    public int size() {
        return files.size();
    }

    private static Asset asset(String path, byte[] body, boolean secure) {
        String type = contentType(path);
        String etag = "\"" + base64Url(sha256(body), 22) + "\"";
        String cache = path.startsWith("_next/static/") ? "public, max-age=31536000, immutable" : "no-cache";
        byte @Nullable [] gzip = isText(type) ? gzip(body) : null;
        if (gzip != null && gzip.length >= body.length) {
            gzip = null;
        }
        @Nullable String csp = type.startsWith("text/html") ? htmlPolicy(new String(body, StandardCharsets.UTF_8), secure) : null;
        return new Asset(path, body, gzip, type, etag, cache, csp);
    }

    /**
     * The CSP of one HTML page: only files of this origin, plus the inline scripts and styles of the
     * page by hash. No {@code unsafe-inline}, no {@code unsafe-eval}, no other origin.
     *
     * @param html   the page
     * @param secure with TLS, {@code upgrade-insecure-requests} is added
     * @return the policy
     */
    static String htmlPolicy(String html, boolean secure) {
        List<String> scripts = hashes(INLINE_SCRIPT.matcher(html));
        List<String> styles = hashes(INLINE_STYLE.matcher(html));
        StringBuilder policy = new StringBuilder("default-src 'self'; script-src 'self'");
        scripts.forEach(h -> policy.append(" '").append(h).append('\''));
        policy.append("; style-src 'self'");
        styles.forEach(h -> policy.append(" '").append(h).append('\''));
        policy.append("; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none';"
                + " form-action 'self'; frame-ancestors 'none'; manifest-src 'self'; worker-src 'self'");
        if (secure) {
            policy.append("; upgrade-insecure-requests");
        }
        return policy.toString();
    }

    private static List<String> hashes(Matcher matcher) {
        Set<String> result = new LinkedHashSet<>();
        while (matcher.find()) {
            result.add("sha256-" + Base64.getEncoder().encodeToString(sha256(matcher.group(1).getBytes(StandardCharsets.UTF_8))));
        }
        return new ArrayList<>(result);
    }

    /**
     * Media type by file extension; unknown types are {@code application/octet-stream}.
     *
     * @param path the path
     * @return the type, with {@code charset=utf-8} for text
     */
    static String contentType(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json", "map" -> "application/json; charset=utf-8";
            case "txt" -> "text/plain; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "ico" -> "image/x-icon";
            case "webp" -> "image/webp";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "webmanifest" -> "application/manifest+json";
            default -> "application/octet-stream";
        };
    }

    private static boolean isText(String type) {
        return type.startsWith("text/") || type.startsWith("application/json") || type.equals("image/svg+xml")
                || type.startsWith("application/manifest+json") || type.equals("font/ttf");
    }

    private static byte[] read(ClassLoader loader, String name) throws IOException {
        try (InputStream in = loader.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException(name + " is missing");
            }
            byte[] body = in.readNBytes(MAX_FILE_BYTES + 1);
            if (body.length > MAX_FILE_BYTES) {
                throw new IOException(name + " is larger than " + MAX_FILE_BYTES + " bytes");
            }
            return body;
        }
    }

    private static byte[] gzip(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length / 3 + 64);
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    private static String base64Url(byte[] data, int length) {
        String text = Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        return text.substring(0, Math.min(length, text.length()));
    }
}
