// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A request target ({@code /path?query}) checked before anything uses it.
 *
 * <p>Rules: at most {@value #MAX_LENGTH} characters of printable ASCII, starts with {@code /}, no
 * backslash. The path has no {@code %} (no percent-encoding at all, so nothing can be decoded twice)
 * and no empty, {@code .} or {@code ..} segment. The query is split into keys and values without
 * decoding; a key may appear once. Only the query may hold {@code %}, as a single sign-on provider
 * sends codes percent-encoded; an endpoint that reads such a value decodes it once.
 *
 * @param path  the path, starting with {@code /}
 * @param query the query parameters in order
 */
public record RequestTarget(String path, Map<String, String> query) {

    /** Longest request target. */
    public static final int MAX_LENGTH = 2048;

    /** Fixes the query map. */
    public RequestTarget {
        query = Collections.unmodifiableMap(new LinkedHashMap<>(query));
    }

    /**
     * Parses a request target.
     *
     * @param uri the target from the request line
     * @return the target, or empty if it breaks a rule
     */
    public static Optional<RequestTarget> parse(@Nullable String uri) {
        if (uri == null || uri.isEmpty() || uri.length() > MAX_LENGTH || uri.charAt(0) != '/') {
            return Optional.empty();
        }
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '\\' || c == '#') {
                return Optional.empty();
            }
        }
        int q = uri.indexOf('?');
        String path = q < 0 ? uri : uri.substring(0, q);
        if (path.indexOf('%') >= 0) {
            return Optional.empty();
        }
        String rawQuery = q < 0 ? "" : uri.substring(q + 1);
        String[] segments = path.split("/", -1);
        // segments[0] is the empty text before the leading slash; a trailing slash leaves one empty last segment.
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            boolean last = i == segments.length - 1;
            if ((segment.isEmpty() && !last) || segment.equals(".") || segment.equals("..")) {
                return Optional.empty();
            }
        }
        Map<String, String> query = new LinkedHashMap<>();
        if (!rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&", -1)) {
                if (pair.isEmpty()) {
                    return Optional.empty();
                }
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                if (key.isEmpty() || value.indexOf('=') >= 0 || query.putIfAbsent(key, value) != null) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(new RequestTarget(path, query));
    }

    /**
     * The file a path of the frontend maps to: {@code /} and every path ending in {@code /} to its
     * {@code index.html}, anything else to the path without the leading slash.
     *
     * @return the file path below the frontend root
     */
    public String file() {
        String relative = path.substring(1);
        return relative.isEmpty() || relative.endsWith("/") ? relative + "index.html" : relative;
    }
}
