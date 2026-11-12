// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Cursor paging of the list endpoints, the rate limit headers and the CORS exposure of both. */
@Timeout(120)
class PagingTest {

    @TempDir
    Path home;

    @Test
    void servicesArePagedByIdWithALinkToTheNextPage() throws Exception {
        try (TestApi api = new TestApi(home)) {
            api.backend.services.clear();
            for (String id : new String[] {"lobby-3", "lobby-1", "survival-1", "lobby-2"}) {
                api.backend.services.put(id, FakeBackend.view(id, "running"));
            }
            HttpResponse<String> all = api.get("/api/v1/services", api.admin);
            assertEquals(200, all.statusCode());
            assertEquals("4", all.headers().firstValue("X-Total-Count").orElseThrow());
            assertTrue(all.headers().firstValue("Link").isEmpty(), "everything in one answer without limit");
            assertTrue(all.body().indexOf("lobby-1") < all.body().indexOf("lobby-2")
                    && all.body().indexOf("lobby-2") < all.body().indexOf("lobby-3")
                    && all.body().indexOf("lobby-3") < all.body().indexOf("survival-1"), "ordered by id");

            HttpResponse<String> first = api.get("/api/v1/services?limit=2", api.admin);
            assertEquals(200, first.statusCode());
            assertTrue(first.body().startsWith("["), "the body stays an array");
            assertTrue(first.body().contains("lobby-1") && first.body().contains("lobby-2"));
            assertFalse(first.body().contains("lobby-3"));
            String next = first.headers().firstValue("Link").orElseThrow();
            assertEquals("</api/v1/services?limit=2&after=lobby-2>; rel=\"next\"", next);

            HttpResponse<String> second = api.get(next.substring(1, next.indexOf('>')), api.admin);
            assertTrue(second.body().contains("lobby-3") && second.body().contains("survival-1"));
            assertFalse(second.body().contains("lobby-2"));
            assertTrue(second.headers().firstValue("Link").isEmpty(), "the last page has no next link");
        }
    }

    @Test
    void groupsStayAnObjectByName() throws Exception {
        try (TestApi api = new TestApi(home)) {
            api.send("PUT", "/api/v1/groups/alpha", api.admin, ApiServerTest.GROUP);
            api.send("PUT", "/api/v1/groups/beta", api.admin, ApiServerTest.GROUP);
            HttpResponse<String> page = api.get("/api/v1/groups?limit=1", api.admin);
            assertEquals(200, page.statusCode(), page.body());
            assertTrue(page.body().startsWith("{\"alpha\":"), page.body());
            assertFalse(page.body().contains("\"beta\""));
            assertEquals("2", page.headers().firstValue("X-Total-Count").orElseThrow());
            HttpResponse<String> rest = api.get("/api/v1/groups?limit=1&after=alpha", api.admin);
            assertTrue(rest.body().startsWith("{\"beta\":"), rest.body());
        }
    }

    @Test
    void badPagingParametersAre400() throws Exception {
        try (TestApi api = new TestApi(home)) {
            for (String query : new String[] {"limit=0", "limit=501", "limit=-1", "limit=abc", "limit=01",
                    "after=UPPER", "after=."}) {
                HttpResponse<String> response = api.get("/api/v1/services?" + query, api.admin);
                assertEquals(400, response.statusCode(), query);
                assertTrue(response.body().contains("\"code\":\"invalid-query\""), query + ": " + response.body());
            }
            assertEquals(200, api.get("/api/v1/services?limit=500", api.admin).statusCode());
        }
    }

    @Test
    void answersCarryTheRateLimitOfTheToken() throws Exception {
        try (TestApi api = new TestApi(home)) {
            HttpResponse<String> one = api.get("/api/v1/services", api.admin);
            int limit = Integer.parseInt(one.headers().firstValue("X-RateLimit-Limit").orElseThrow());
            int remaining = Integer.parseInt(one.headers().firstValue("X-RateLimit-Remaining").orElseThrow());
            assertEquals(ApiLimits.defaults().burstPerToken(), limit);
            assertEquals(limit - 1, remaining);
            HttpResponse<String> two = api.get("/api/v1/services", api.admin);
            assertEquals(remaining - 1, Integer.parseInt(two.headers().firstValue("X-RateLimit-Remaining").orElseThrow()));
        }
    }
}
