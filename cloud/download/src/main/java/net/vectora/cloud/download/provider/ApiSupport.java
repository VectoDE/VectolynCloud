package net.vectora.cloud.download.provider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import net.vectora.cloud.download.ArtifactNotAvailableException;
import net.vectora.cloud.download.FileNames;
import net.vectora.cloud.download.HostNotAllowedException;
import net.vectora.cloud.download.HttpStatusException;
import net.vectora.cloud.download.MalformedResponseException;
import net.vectora.cloud.download.http.DownloadClient;
import net.vectora.cloud.download.http.HostAllowlist;

/** Shared helpers of the providers: safe URL building and API calls. */
final class ApiSupport {

    /** Largest API response accepted. ViaVersion's full Modrinth version list is about 2 MB. */
    static final long MAX_JSON_BYTES = 16L * 1024 * 1024;

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._+-]{1,128}");

    private ApiSupport() {
    }

    /**
     * Validates a value that becomes one URL path segment, so a request can never change the path.
     */
    static String segment(String value, String name) {
        if (value == null || !SEGMENT.matcher(value).matches() || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(name + " must match " + SEGMENT.pattern() + ": " + value);
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Like {@link #segment}, for values taken from an API response. */
    static String apiSegment(String value, String context) throws MalformedResponseException {
        try {
            return segment(value, context);
        } catch (IllegalArgumentException e) {
            throw new MalformedResponseException(context + ": unexpected characters in " + value, e);
        }
    }

    static String query(Map<String, String> parameters) {
        StringJoiner joiner = new StringJoiner("&", "?", "");
        parameters.forEach((key, value) -> joiner.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return joiner.toString();
    }

    /** Fetches API JSON; 404 and 400 become {@link ArtifactNotAvailableException}. */
    static byte[] fetchJson(DownloadClient client, URI uri, HostAllowlist hosts, String what)
            throws IOException, InterruptedException {
        try {
            return client.fetchBytes(uri, hosts, MAX_JSON_BYTES);
        } catch (HttpStatusException e) {
            if (e.status() == 404 || e.status() == 400) {
                throw new ArtifactNotAvailableException(what + " not found (HTTP " + e.status() + " from " + uri + ")");
            }
            throw e;
        }
    }

    /**
     * Checks a file name taken from an API response: it must be a plain base name
     * ({@link FileNames#isSafeBaseName}), never a path or a Windows device name.
     */
    static String fileName(String name, String source) throws MalformedResponseException {
        if (!FileNames.isSafeBaseName(name)) {
            throw new MalformedResponseException(source + ": file name is not a plain base name: "
                    + (name == null ? "null" : name.codePoints().limit(80)
                            .collect(StringBuilder::new, (b, cp) -> b.appendCodePoint(Character.isISOControl(cp) ? '?' : cp),
                                    StringBuilder::append)));
        }
        return name;
    }

    /** Makes sure a download URL taken from an API response stays on the source's hosts. */
    static URI checkDownloadUrl(URI url, HostAllowlist hosts, String what) throws HostNotAllowedException {
        if (!hosts.permits(url)) {
            throw new HostNotAllowedException(what + ": download URL " + url + " is outside " + hosts.hosts());
        }
        return url;
    }
}
