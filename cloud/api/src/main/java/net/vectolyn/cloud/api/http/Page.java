// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.http;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Cursor paging of the list endpoints ({@code GET /services}, {@code /groups}, {@code /tasks},
 * {@code /nodes}, {@code /backups}).
 *
 * <p>Every list is ordered by its key (service id, group, task or node name, backup id). Without
 * {@code limit} the answer holds the whole list, as before paging existed. With
 * {@code ?limit=n} (1 to {@value #MAX_LIMIT}) it holds at most n entries; {@code ?after=key}
 * starts after that key. The body keeps its shape (an array, or an object by name for groups and
 * tasks), so a client that does not page sees no difference. The paging state travels in headers:
 * {@code X-Total-Count} (entries in the whole list) and, if more follow,
 * {@code Link: <...?limit=n&after=key>; rel="next"} (RFC 8288). A key as cursor stays valid while
 * entries are added or removed: nothing is skipped or repeated that existed through both pages.
 */
public final class Page {

    /** Largest page. */
    public static final int MAX_LIMIT = 500;

    private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]{0,2}");
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");

    private Page() {
    }

    /**
     * Answers one page of a list.
     *
     * @param q     the request ({@code limit}, {@code after})
     * @param path  the path of the endpoint, for the {@code Link} header
     * @param items the whole list in any order
     * @param key   the key of an entry
     * @param <T>   the entry type
     * @return the answer
     * @throws ApiException 400 {@code invalid-query} for a bad {@code limit} or {@code after}
     */
    public static <T> ApiResponse list(ApiRequest q, String path, List<T> items, Function<T, String> key)
            throws ApiException {
        Request request = request(q);
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(key));
        List<T> page = new ArrayList<>();
        @Nullable String last = null;
        boolean more = false;
        for (T item : sorted) {
            String k = key.apply(item);
            if (request.after() != null && k.compareTo(request.after()) <= 0) {
                continue;
            }
            if (page.size() == request.limit()) {
                more = true;
                break;
            }
            page.add(item);
            last = k;
        }
        return answer(page, sorted.size(), path, request, more ? last : null);
    }

    /**
     * Answers one page of a map by name (groups, tasks).
     *
     * @param q     the request
     * @param path  the path of the endpoint
     * @param items the whole map
     * @param <V>   the value type
     * @return the answer, an object by name in name order
     * @throws ApiException 400 {@code invalid-query} for a bad {@code limit} or {@code after}
     */
    public static <V> ApiResponse map(ApiRequest q, String path, Map<String, V> items) throws ApiException {
        Request request = request(q);
        Map<String, V> page = new LinkedHashMap<>();
        @Nullable String last = null;
        boolean more = false;
        for (Map.Entry<String, V> entry : new TreeMap<>(items).entrySet()) {
            if (request.after() != null && entry.getKey().compareTo(request.after()) <= 0) {
                continue;
            }
            if (page.size() == request.limit()) {
                more = true;
                break;
            }
            page.put(entry.getKey(), entry.getValue());
            last = entry.getKey();
        }
        return answer(page, items.size(), path, request, more ? last : null);
    }

    private static ApiResponse answer(Object page, int total, String path, Request request,
            @Nullable String next) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Total-Count", Integer.toString(total));
        if (next != null) {
            headers.put("Link", "<" + path + "?limit=" + request.limit() + "&after=" + next + ">; rel=\"next\"");
        }
        return new ApiResponse(200, ApiJson.write(page), headers);
    }

    private static Request request(ApiRequest q) throws ApiException {
        Optional<String> limit = q.query("limit");
        Optional<String> after = q.query("after");
        if (limit.isPresent() && !(LIMIT.matcher(limit.get()).matches() && Integer.parseInt(limit.get()) <= MAX_LIMIT)) {
            throw ApiException.badRequest("invalid-query", "\"limit\" must be a number from 1 to " + MAX_LIMIT + ".");
        }
        if (after.isPresent() && !KEY.matcher(after.get()).matches()) {
            throw ApiException.badRequest("invalid-query", "\"after\" must be a key of the list.");
        }
        return new Request(limit.map(Integer::parseInt).orElse(Integer.MAX_VALUE), after.orElse(null));
    }

    private record Request(int limit, @Nullable String after) {
    }
}
