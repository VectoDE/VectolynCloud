package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.backend.BackupOrder;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.model.ServerKind;
import net.vectolyn.cloud.config.operation.SetServerWanted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
class ApiServerTest {

    static final String GROUP = """
            {"schema-version":1,"template":"Lobby-Server","software":"paper","minecraft-version":"26.3",
             "java":"auto","heap":{"min":"256M","max":"768M"},"combat":"modern","catalog":[]}""";

    @TempDir
    Path home;

    @Test
    void withoutATokenEverythingIs401() throws Exception {
        try (TestApi api = new TestApi(home)) {
            for (String path : List.of("/api/v1/services", "/api/v1/config", "/api/v1/nothing-here", "/", "/api/v1/events")) {
                HttpResponse<String> response = api.get(path, null);
                assertEquals(401, response.statusCode(), path);
                assertEquals("Bearer realm=\"vectolyn\"", response.headers().firstValue("WWW-Authenticate").orElse(null));
                assertTrue(response.body().contains("\"code\":\"unauthorized\""), response.body());
            }
            assertEquals(401, api.send("DELETE", "/api/v1/groups/lobby?confirm=lobby", null, null).statusCode());
            assertEquals(401, api.send("OPTIONS", "/api/v1/services", null, null).statusCode());
            // Counter-check: the same request with the token works.
            assertEquals(200, api.get("/api/v1/services", api.admin).statusCode());
        }
    }

    @Test
    void wrongMalformedAndRevokedTokensAre401() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String other = api.token("reader", Scope.SERVICES_READ);
            assertEquals(200, api.get("/api/v1/services", other).statusCode());
            String wrong = "vct_" + "A".repeat(43);
            assertEquals(401, api.get("/api/v1/services", wrong).statusCode());
            assertEquals(401, api.get("/api/v1/services", other.substring(0, other.length() - 1) + "x").statusCode());
            assertEquals(401, api.get("/api/v1/services", "not-a-token").statusCode());
            HttpResponse<String> basic = api.send(HttpRequest.newBuilder(api.uri("/api/v1/services"))
                    .header("Authorization", "Basic YWRtaW46YWRtaW4=").build());
            assertEquals(401, basic.statusCode());

            api.tokens.revoke("reader");
            assertEquals(401, api.get("/api/v1/services", other).statusCode());
        }
    }

    @Test
    void aTokenWithoutTheScopeGets403() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String reader = api.token("reader", Scope.SERVICES_READ);
            assertEquals(200, api.get("/api/v1/services", reader).statusCode());
            HttpResponse<String> config = api.get("/api/v1/config", reader);
            assertEquals(403, config.statusCode());
            assertTrue(config.body().contains("config:read"), config.body());
            assertEquals(403, api.send("POST", "/api/v1/services/lobby-1/stop", reader, null).statusCode());
            assertEquals("running", api.backend.services.get("lobby-1").state());
            // The token sees its own scopes.
            HttpResponse<String> self = api.get("/api/v1/tokens/self", reader);
            assertEquals(200, self.statusCode());
            assertTrue(self.body().contains("\"scopes\":[\"services:read\"]"), self.body());
        }
    }

    @Test
    void rateLimitPerTokenAndPerAddress() throws Exception {
        try (TestApi api = new TestApi(home, s -> s.withLimits(new ApiLimits(256 * 1024, 600, 100, 60, 3, 3, 64, 256, 32,
                32, 120)))) {
            for (int i = 0; i < 3; i++) {
                assertEquals(200, api.get("/api/v1/services", api.admin).statusCode());
            }
            HttpResponse<String> limited = api.get("/api/v1/services", api.admin);
            assertEquals(429, limited.statusCode());
            assertEquals("1", limited.headers().firstValue("Retry-After").orElse(null));
            // Another token from the same address still has its own bucket.
            String second = api.token("second", Scope.SERVICES_READ);
            assertEquals(200, api.get("/api/v1/services", second).statusCode());
            // One second later the first token has a request again (60 per minute).
            api.nanos.addAndGet(1_000_000_000L);
            assertEquals(200, api.get("/api/v1/services", api.admin).statusCode());
        }
    }

    @Test
    void wrongTokensBlockTheAddressForAWhile() throws Exception {
        try (TestApi api = new TestApi(home, s -> s.withLimits(new ApiLimits(256 * 1024, 600, 100, 300, 60, 3, 64, 256, 32,
                32, 120)))) {
            String wrong = "vct_" + "B".repeat(43);
            for (int i = 0; i < 3; i++) {
                assertEquals(401, api.get("/api/v1/services", wrong).statusCode());
            }
            // Now even the right token is refused from this address, so guessing does not pay off.
            assertEquals(429, api.get("/api/v1/services", api.admin).statusCode());
            api.nanos.addAndGet(60_000_000_000L);
            assertEquals(200, api.get("/api/v1/services", api.admin).statusCode());
        }
    }

    @Test
    void oversizedBodiesAre413() throws Exception {
        try (TestApi api = new TestApi(home, s -> s.withLimits(new ApiLimits(1024, 600, 100, 300, 60, 10, 64, 256, 32, 32,
                120)))) {
            String big = "{\"command\":\"" + "x".repeat(2000) + "\"}";
            HttpResponse<String> response = api.send("POST", "/api/v1/services/lobby-1/command", api.admin, big);
            assertEquals(413, response.statusCode());
            assertTrue(api.backend.commands.isEmpty());
            // Chunked, without Content-Length: the limit still holds.
            HttpRequest chunked = HttpRequest.newBuilder(api.uri("/api/v1/services/lobby-1/command"))
                    .header("Authorization", "Bearer " + api.admin).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(
                            big.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).build();
            assertEquals(413, api.send(chunked).statusCode());
            assertTrue(api.backend.commands.isEmpty());
            // Counter-check: a small body passes.
            assertEquals(202, api.send("POST", "/api/v1/services/lobby-1/command", api.admin, "{\"command\":\"say hi\"}")
                    .statusCode());
            assertEquals(List.of("lobby-1: say hi"), api.backend.commands);
        }
    }

    @Test
    void unknownFieldsAndWrongTypesAreRefused() throws Exception {
        try (TestApi api = new TestApi(home)) {
            HttpResponse<String> unknown = api.send("POST", "/api/v1/services/lobby-1/command", api.admin,
                    "{\"command\":\"say hi\",\"comand\":\"x\"}");
            assertEquals(400, unknown.statusCode());
            assertTrue(unknown.body().contains("\"code\":\"unknown-field\""), unknown.body());
            assertTrue(unknown.body().contains("comand"), unknown.body());

            String typo = GROUP.replace("\"combat\"", "\"combatt\"");
            HttpResponse<String> group = api.send("PUT", "/api/v1/groups/lobby", api.admin, typo);
            assertEquals(400, group.statusCode());
            assertFalse(Files.exists(home.resolve("config/groups/lobby.yml")));

            HttpResponse<String> type = api.send("PUT", "/api/v1/servers/mod", api.admin, "{\"wanted\":\"false\"}");
            assertEquals(400, type.statusCode());
            assertTrue(api.config.snapshot().cloud().servers().mod());

            HttpResponse<String> duplicate = api.send("PUT", "/api/v1/servers/mod", api.admin,
                    "{\"wanted\":true,\"wanted\":false}");
            assertEquals(400, duplicate.statusCode());
            assertEquals(415, api.send(HttpRequest.newBuilder(api.uri("/api/v1/servers/mod"))
                    .header("Authorization", "Bearer " + api.admin).header("Content-Type", "text/plain")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"wanted\":false}")).build()).statusCode());
            assertEquals(400, api.send("PUT", "/api/v1/servers/mod", api.admin, "{\"wanted\":false} {}").statusCode());
            assertEquals(400, api.send("PUT", "/api/v1/servers/mod", api.admin, "[".repeat(100)).statusCode());

            // Counter-check: the correct body changes the switch.
            assertEquals(200, api.send("PUT", "/api/v1/servers/mod", api.admin, "{\"wanted\":false}").statusCode());
            assertFalse(api.config.snapshot().cloud().servers().mod());
        }
    }

    @Test
    void forbiddenJvmFlagsAreRefusedAndAllowedOnesAccepted() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String agent = GROUP.replace("\"catalog\":[]", "\"catalog\":[],\"process\":{\"jvm-flags\":[\"-javaagent:/tmp/x.jar\"]}");
            HttpResponse<String> refused = api.send("PUT", "/api/v1/groups/lobby", api.admin, agent);
            assertEquals(422, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("\"code\":\"jvm-flag-forbidden\""), refused.body());
            assertTrue(refused.body().contains("process.jvm-flags[0]"), refused.body());
            assertFalse(refused.body().contains("/tmp/x.jar"), "the flag must not be echoed");
            assertFalse(Files.exists(home.resolve("config/groups/lobby.yml")));

            for (String flag : List.of("-XX:OnOutOfMemoryError=kill", "-agentpath:/x.so", "-XX:OnError=sh",
                    "-Dcom.sun.management.jmxremote.port=1")) {
                String body = GROUP.replace("\"catalog\":[]", "\"catalog\":[],\"process\":{\"jvm-flags\":[\"" + flag + "\"]}");
                assertEquals(422, api.send("PUT", "/api/v1/groups/lobby", api.admin, body).statusCode(), flag);
            }
            String unlisted = GROUP.replace("\"catalog\":[]", "\"catalog\":[],\"process\":{\"jvm-flags\":[\"-XX:SomeFlag=1\"]}");
            HttpResponse<String> notListed = api.send("PUT", "/api/v1/groups/lobby", api.admin, unlisted);
            assertEquals(422, notListed.statusCode());
            assertTrue(notListed.body().contains("jvm-flag-not-listed"), notListed.body());

            String good = GROUP.replace("\"catalog\":[]",
                    "\"catalog\":[],\"process\":{\"jvm-flags\":[\"-XX:+UseG1GC\",\"-XX:MaxGCPauseMillis=200\"]}");
            HttpResponse<String> created = api.send("PUT", "/api/v1/groups/lobby", api.admin, good);
            assertEquals(201, created.statusCode(), created.body());
            assertTrue(Files.readString(home.resolve("config/groups/lobby.yml")).contains("-XX:MaxGCPauseMillis=200"));

            // A property only some library reads (Fabric's extra mods, Paper's Maven mirror) is refused too.
            for (String flag : List.of("-Dfabric.addMods=//evil.example/share/x.jar",
                    "-Dorg.bukkit.plugin.java.LibraryLoader.centralURL=https://evil.example/m2", "-Dmyplugin.debug=true")) {
                String body = GROUP.replace("\"catalog\":[]", "\"catalog\":[],\"process\":{\"jvm-flags\":[\"" + flag + "\"]}");
                assertEquals(422, api.send("PUT", "/api/v1/groups/lobby", api.admin, body).statusCode(), flag);
            }
            // The operator's own flag (console or file) is not refused there and stays when the API changes the group.
            api.config.apply(new net.vectolyn.cloud.config.operation.PutGroup("lobby",
                    api.config.snapshot().groups().get("lobby").withProcess(new net.vectolyn.cloud.config.model.ProcessConfig(
                            List.of("-Dmyplugin.debug=true"), null, null, null))));
            String kept = GROUP.replace("768M", "1G")
                    .replace("\"catalog\":[]", "\"catalog\":[],\"process\":{\"jvm-flags\":[\"-Dmyplugin.debug=true\"]}");
            assertEquals(200, api.send("PUT", "/api/v1/groups/lobby", api.admin, kept).statusCode());
            assertTrue(api.get("/api/v1/config/warnings", api.admin).body().contains("jvm-flag-not-listed"));
        }
    }

    @Test
    void nodesAreReadAndTheirMemorySettingsWritten() throws Exception {
        try (TestApi api = new TestApi(home)) {
            HttpResponse<String> list = api.get("/api/v1/nodes", api.admin);
            assertEquals(200, list.statusCode(), list.body());
            assertTrue(list.body().startsWith("[") && list.body().contains("\"id\":\"node-1\""), list.body());
            HttpResponse<String> one = api.get("/api/v1/nodes/node-1", api.admin);
            assertEquals(200, one.statusCode());
            String etag = one.headers().firstValue("ETag").orElseThrow();
            assertEquals(404, api.get("/api/v1/nodes/node-9", api.admin).statusCode());

            String limited = one.body().replace("\"service-ports\"",
                    "\"max-memory\":\"8G\",\"memory-reserve\":{\"system\":\"2G\"},\"service-ports\"");
            HttpResponse<String> put = api.send(HttpRequest.newBuilder(api.uri("/api/v1/nodes/node-1"))
                    .header("Authorization", "Bearer " + api.admin).header("Content-Type", "application/json")
                    .header("If-Match", etag).PUT(HttpRequest.BodyPublishers.ofString(limited)).build());
            assertEquals(200, put.statusCode(), put.body());
            var node = api.config.snapshot().nodes().find("node-1").orElseThrow();
            assertEquals("8G", node.maxMemory().toString());
            assertEquals("2G", node.memoryReserve().system().toString());
            assertEquals("256M", node.memoryReserve().nodeOverhead().toString(), "missing field: default");
            assertTrue(Files.readString(home.resolve("config/nodes.yml")).contains("memory-reserve:"));
            // The old ETag no longer fits.
            assertEquals(412, api.send(HttpRequest.newBuilder(api.uri("/api/v1/nodes/node-1"))
                    .header("Authorization", "Bearer " + api.admin).header("Content-Type", "application/json")
                    .header("If-Match", etag).PUT(HttpRequest.BodyPublishers.ofString(one.body())).build()).statusCode());

            // Refused: below 512M, a reserve field above 64G, another id, a wrong form, an unknown field.
            HttpResponse<String> small = api.send("PUT", "/api/v1/nodes/node-1", api.admin, limited.replace("8G", "256M"));
            assertEquals(422, small.statusCode(), small.body());
            assertTrue(small.body().contains("nodes[0].max-memory"), small.body());
            HttpResponse<String> big = api.send("PUT", "/api/v1/nodes/node-1", api.admin,
                    limited.replace("\"system\":\"2G\"", "\"database\":\"65G\""));
            assertEquals(422, big.statusCode(), big.body());
            assertTrue(big.body().contains("nodes[0].memory-reserve.database"), big.body());
            assertEquals(400, api.send("PUT", "/api/v1/nodes/node-1", api.admin, limited.replace("\"node-1\"", "\"node-2\""))
                    .statusCode());
            assertEquals(400, api.send("PUT", "/api/v1/nodes/node-1", api.admin, limited.replace("\"2G\"", "\"2 GB\""))
                    .statusCode());
            assertEquals(400, api.send("PUT", "/api/v1/nodes/node-1", api.admin, limited.replace("\"system\"", "\"sytem\""))
                    .statusCode());
            assertEquals("8G", api.config.snapshot().nodes().find("node-1").orElseThrow().maxMemory().toString(), "unchanged");

            // A reader may look but not write.
            String reader = api.token("reader", Scope.CONFIG_READ);
            assertEquals(200, api.get("/api/v1/nodes", reader).statusCode());
            assertEquals(403, api.send("PUT", "/api/v1/nodes/node-1", reader, one.body()).statusCode());

            // Counter-check: the original body puts the node back as it was.
            HttpResponse<String> back = api.send("PUT", "/api/v1/nodes/node-1", api.admin, one.body());
            assertEquals(200, back.statusCode(), back.body());
            assertEquals(net.vectolyn.cloud.config.model.NodeConfig.defaults(), api.config.snapshot().nodes().find("node-1")
                    .orElseThrow());
        }
    }

    @Test
    void groupDrainTimeoutIsTakenAndChecked() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String drain = GROUP.replace("\"catalog\":[]", "\"catalog\":[],\"drain-timeout\":\"45s\"");
            HttpResponse<String> created = api.send("PUT", "/api/v1/groups/lobby", api.admin, drain);
            assertEquals(201, created.statusCode(), created.body());
            assertTrue(created.body().contains("\"drain-timeout\":\"45s\""), created.body());
            assertEquals("45s", api.config.snapshot().groups().get("lobby").drainTimeout().toString());
            HttpResponse<String> tooLong = api.send("PUT", "/api/v1/groups/lobby", api.admin, drain.replace("45s", "11m"));
            assertEquals(422, tooLong.statusCode(), tooLong.body());
            assertTrue(tooLong.body().contains("\"path\":\"drain-timeout\"") && tooLong.body().contains("out-of-range"),
                    tooLong.body());
            assertEquals(400, api.send("PUT", "/api/v1/groups/lobby", api.admin, drain.replace("45s", "45")).statusCode());
            assertEquals("45s", api.config.snapshot().groups().get("lobby").drainTimeout().toString(), "unchanged");
            // Counter-check: without the field the default applies and nothing is written.
            HttpResponse<String> plain = api.send("PUT", "/api/v1/groups/lobby", api.admin, GROUP);
            assertEquals(200, plain.statusCode(), plain.body());
            assertFalse(plain.body().contains("drain-timeout"), plain.body());
            assertEquals("30s", api.config.snapshot().groups().get("lobby").drainTimeoutOrDefault().toString());
        }
    }

    @Test
    void groupsTasksAndConfigGoThroughConfigService() throws Exception {
        try (TestApi api = new TestApi(home)) {
            assertEquals(201, api.send("PUT", "/api/v1/groups/lobby", api.admin, GROUP).statusCode());
            HttpResponse<String> group = api.get("/api/v1/groups/lobby", api.admin);
            assertEquals(200, group.statusCode());
            String etag = group.headers().firstValue("ETag").orElseThrow();
            // A second write with an old ETag fails.
            assertEquals(200, api.send(HttpRequest.newBuilder(api.uri("/api/v1/groups/lobby"))
                    .header("Authorization", "Bearer " + api.admin).header("Content-Type", "application/json")
                    .header("If-Match", etag).PUT(HttpRequest.BodyPublishers.ofString(GROUP.replace("768M", "1G"))).build())
                    .statusCode());
            assertEquals(412, api.send(HttpRequest.newBuilder(api.uri("/api/v1/groups/lobby"))
                    .header("Authorization", "Bearer " + api.admin).header("Content-Type", "application/json")
                    .header("If-Match", etag).PUT(HttpRequest.BodyPublishers.ofString(GROUP)).build()).statusCode());
            assertEquals("1G", api.config.snapshot().groups().get("lobby").heap().max().toString());

            String task = """
                    {"schema-version":2,"group":"lobby","scaling":{"static-services":1,"min-services":1,"max-services":3,
                     "players-per-service":50,"scale-up-at":75,"scale-down-below":40,"scale-down-after":"5m",
                     "cooldown":"60s"},"nodes":[]}""";
            assertEquals(201, api.send("PUT", "/api/v1/tasks/lobby", api.admin, task).statusCode());
            assertEquals(1, api.config.snapshot().tasks().get("lobby").scaling().staticServices());
            assertEquals(40, api.config.snapshot().tasks().get("lobby").scaling().scaleDownBelow());
            assertEquals(422, api.send("PUT", "/api/v1/tasks/other", api.admin, task.replace("\"lobby\"", "\"nope\""))
                    .statusCode());
            // The same validator as for the file: static above min, scale-down-below not below scale-up-at.
            HttpResponse<String> tooStatic = api.send("PUT", "/api/v1/tasks/lobby", api.admin,
                    task.replace("\"static-services\":1", "\"static-services\":2"));
            assertEquals(422, tooStatic.statusCode());
            assertTrue(tooStatic.body().contains("static-above-min"), tooStatic.body());
            HttpResponse<String> overlap = api.send("PUT", "/api/v1/tasks/lobby", api.admin,
                    task.replace("\"scale-down-below\":40", "\"scale-down-below\":75"));
            assertEquals(422, overlap.statusCode());
            assertTrue(overlap.body().contains("scale-down-not-below-up"), overlap.body());
            // Without static-services the body is refused with required at the field.
            HttpResponse<String> noStatic = api.send("PUT", "/api/v1/tasks/lobby", api.admin,
                    task.replace("\"static-services\":1,", ""));
            assertEquals(400, noStatic.statusCode(), noStatic.body());
            assertTrue(noStatic.body().contains("\"path\":\"scaling.static-services\"")
                    && noStatic.body().contains("\"code\":\"required\""), noStatic.body());
            // Counter-check: a wrong type there is invalid-value.
            HttpResponse<String> wrongStatic = api.send("PUT", "/api/v1/tasks/lobby", api.admin,
                    task.replace("\"static-services\":1", "\"static-services\":\"1\""));
            assertEquals(400, wrongStatic.statusCode(), wrongStatic.body());
            assertTrue(wrongStatic.body().contains("\"code\":\"invalid-value\""), wrongStatic.body());
            // Without scale-down-below the default applies.
            assertEquals(200, api.send("PUT", "/api/v1/tasks/lobby", api.admin,
                    task.replace(",\"scale-down-below\":40", "")).statusCode());
            assertEquals(50, api.config.snapshot().tasks().get("lobby").scaling().scaleDownBelow());
            // A body of schema-version 1 with mode is migrated like a file and stored as version 2.
            String v1 = """
                    {"schema-version":1,"group":"lobby","mode":"static","scaling":{"min-services":2,"max-services":5,
                     "players-per-service":50,"scale-up-at":50,"scale-down-after":"5m","cooldown":"60s"},"nodes":[]}""";
            HttpResponse<String> migrated = api.send("PUT", "/api/v1/tasks/lobby", api.admin, v1);
            assertEquals(200, migrated.statusCode(), migrated.body());
            assertTrue(migrated.body().contains("\"schema-version\":2") && !migrated.body().contains("\"mode\""),
                    migrated.body());
            var stored = api.config.snapshot().tasks().get("lobby").scaling();
            assertEquals(List.of(2, 2, 2, 25), List.of(stored.staticServices(), stored.minServices(), stored.maxServices(),
                    stored.scaleDownBelow()), "static = min = max, scale-down-below = min(50, 50 / 2)");
            String file = Files.readString(home.resolve("config/tasks/lobby.yml"));
            assertTrue(file.contains("schema-version: 2") && file.contains("static-services: 2") && !file.contains("mode"), file);
            HttpResponse<String> dynamic = api.send("PUT", "/api/v1/tasks/fresh", api.admin,
                    v1.replace("\"static\"", "\"dynamic\""));
            assertEquals(201, dynamic.statusCode(), dynamic.body());
            assertEquals(0, api.config.snapshot().tasks().get("fresh").scaling().staticServices());
            assertEquals(5, api.config.snapshot().tasks().get("fresh").scaling().maxServices());
            assertEquals(204, api.send("DELETE", "/api/v1/tasks/fresh?confirm=fresh", api.admin, null).statusCode());
            // Counter-check: a version-1 body with an invalid mode is refused at the field, nothing changes.
            HttpResponse<String> badMode = api.send("PUT", "/api/v1/tasks/lobby", api.admin,
                    v1.replace("\"static\"", "\"elastic\"").replace("\"min-services\":2", "\"min-services\":1"));
            assertEquals(400, badMode.statusCode(), badMode.body());
            assertTrue(badMode.body().contains("\"path\":\"mode\"") && badMode.body().contains("\"code\":\"invalid-value\""),
                    badMode.body());
            assertFalse(badMode.body().contains("elastic"), "the value is not echoed");
            assertEquals(2, api.config.snapshot().tasks().get("lobby").scaling().maxServices(), "unchanged");
            assertEquals(400, api.send("PUT", "/api/v1/tasks/lobby", api.admin, "{\"schema-version\":1,").statusCode());
            assertEquals(400, api.send("PUT", "/api/v1/tasks/lobby", api.admin, v1.replace("\"mode\"", "\"mood\""))
                    .statusCode());

            // Wanted: the group's own switch; the switch of its server kind stays as it is.
            HttpResponse<String> off = api.send("PUT", "/api/v1/groups/lobby/wanted", api.admin, "{\"wanted\":false}");
            assertEquals(200, off.statusCode());
            assertTrue(off.body().contains("\"wanted\":false") && off.body().contains("\"server-kind\":\"lobby\"")
                    && off.body().contains("\"runs\":false"), off.body());
            assertFalse(api.config.snapshot().groups().get("lobby").wantedOrDefault());
            assertTrue(api.config.snapshot().cloud().servers().isWanted(ServerKind.LOBBY), "the kind switch is untouched");
            assertEquals(200, api.send("PUT", "/api/v1/tasks/lobby/wanted", api.admin, "{\"wanted\":true}").statusCode());
            assertTrue(api.config.snapshot().groups().get("lobby").wantedOrDefault(), "a task switches its group");
            assertEquals(404, api.send("PUT", "/api/v1/groups/missing/wanted", api.admin, "{\"wanted\":true}").statusCode());
            assertTrue(api.get("/api/v1/servers", api.admin).body().contains("\"lobby\":true"));

            // Deleting needs the name repeated; the group is still used by the task.
            assertEquals(400, api.send("DELETE", "/api/v1/groups/lobby", api.admin, null).statusCode());
            assertEquals(400, api.send("DELETE", "/api/v1/groups/lobby?confirm=other", api.admin, null).statusCode());
            assertEquals(422, api.send("DELETE", "/api/v1/groups/lobby?confirm=lobby", api.admin, null).statusCode());
            assertEquals(204, api.send("DELETE", "/api/v1/tasks/lobby?confirm=lobby", api.admin, null).statusCode());
            assertEquals(204, api.send("DELETE", "/api/v1/groups/lobby?confirm=lobby", api.admin, null).statusCode());
            assertFalse(Files.exists(home.resolve("config/groups/lobby.yml")));
            assertEquals(404, api.get("/api/v1/groups/lobby", api.admin).statusCode());

            // cloud.yml: same validation as the console (setup-complete needs the EULA).
            HttpResponse<String> cloud = api.get("/api/v1/config", api.admin);
            String setupWithoutEula = cloud.body().replace("\"setup-complete\":false", "\"setup-complete\":true");
            HttpResponse<String> refused = api.send("PUT", "/api/v1/config", api.admin, setupWithoutEula);
            assertEquals(422, refused.statusCode());
            assertTrue(refused.body().contains("eula-required"), refused.body());
            String complete = setupWithoutEula.replace("\"eula-accepted\":false", "\"eula-accepted\":true");
            assertEquals(200, api.send("PUT", "/api/v1/config", api.admin, complete).statusCode());
            HttpResponse<String> setup = api.get("/api/v1/setup", api.admin);
            assertTrue(setup.body().contains("\"ready\":true"), setup.body());

            // A public bind address without TLS is refused by cloud-config as well.
            String open = complete.replace("\"enabled\":false,\"bind\":\"127.0.0.1\"", "\"enabled\":true,\"bind\":\"0.0.0.0\"");
            HttpResponse<String> tls = api.send("PUT", "/api/v1/config", api.admin, open);
            assertEquals(422, tls.statusCode(), open);
            assertTrue(tls.body().contains("api-tls-required"), tls.body());

            HttpResponse<String> reload = api.send("POST", "/api/v1/config/reload", api.admin, null);
            assertEquals(200, reload.statusCode());
            assertTrue(reload.body().contains("\"changed\":false"), reload.body());
        }
    }

    @Test
    void servicesAndBackendErrors() throws Exception {
        try (TestApi api = new TestApi(home)) {
            api.config.apply(List.of(snapshot -> snapshot.withGroup("lobby", ApiSamples.lobbyGroup())
                    .withTask("lobby", ApiSamples.lobbyTask())));
            assertTrue(api.get("/api/v1/services/lobby-1", api.admin).body().contains("\"state\":\"running\""));
            assertEquals(404, api.get("/api/v1/services/lobby-9", api.admin).statusCode());
            assertEquals(400, api.get("/api/v1/services/Lobby-1", api.admin).statusCode());
            assertEquals(201, api.send("POST", "/api/v1/services", api.admin, "{\"task\":\"lobby\"}").statusCode());
            assertEquals(404, api.send("POST", "/api/v1/services", api.admin, "{\"task\":\"other\"}").statusCode());
            assertEquals(200, api.send("POST", "/api/v1/services/lobby-1/stop", api.admin, null).statusCode());
            HttpResponse<String> conflict = api.send("POST", "/api/v1/services/lobby-1/command", api.admin,
                    "{\"command\":\"say hi\"}");
            assertEquals(409, conflict.statusCode());
            assertEquals(400, api.send("POST", "/api/v1/services/lobby-1/command", api.admin, "{\"command\":\"a\\nb\"}")
                    .statusCode());
            assertEquals(405, api.send("DELETE", "/api/v1/services", api.admin, null).statusCode());
            assertEquals(404, api.get("/api/v1/unknown", api.admin).statusCode());
            assertEquals(400, api.get("/api/v1/services/../config", api.admin).statusCode());
            assertEquals(200, api.get("/api/v1/node", api.admin).statusCode());
        }
    }

    @Test
    void startAndReloadAreRefusedDuringShutdown() throws Exception {
        try (TestApi api = new TestApi(home)) {
            api.config.apply(List.of(snapshot -> snapshot.withGroup("lobby", ApiSamples.lobbyGroup())
                    .withTask("lobby", ApiSamples.lobbyTask())));
            api.server.beginShutdown();
            HttpResponse<String> start = api.send("POST", "/api/v1/services", api.admin, "{\"task\":\"lobby\"}");
            assertEquals(503, start.statusCode());
            assertTrue(start.body().contains("shutting-down"));
            assertEquals(503, api.send("POST", "/api/v1/services/lobby-1/restart", api.admin, null).statusCode());
            assertEquals(503, api.send("POST", "/api/v1/config/reload", api.admin, null).statusCode());
            assertEquals(1, api.backend.services.size());
            // Reads and stops still work while the node goes down.
            assertEquals(200, api.get("/api/v1/services", api.admin).statusCode());
            assertEquals(200, api.send("POST", "/api/v1/services/lobby-1/stop", api.admin, null).statusCode());
        }
        // The node's own flag counts as well.
        Path other = Files.createDirectory(home.resolve("other"));
        try (TestApi api = new TestApi(other)) {
            api.backend.shuttingDown.set(true);
            assertEquals(503, api.send("POST", "/api/v1/config/reload", api.admin, null).statusCode());
        }
    }

    @Test
    void backupsUseThePreparedInterface() throws Exception {
        try (TestApi api = new TestApi(home)) {
            HttpResponse<String> missing = api.get("/api/v1/backups", api.admin);
            assertEquals(501, missing.statusCode());
            api.backend.backupsAvailable = true;
            assertTrue(api.get("/api/v1/backups", api.admin).body().contains("20261029-100000-manual"));
            HttpResponse<String> created = api.send("POST", "/api/v1/backups", api.admin,
                    "{\"services\":[\"lobby-1\"],\"database\":false,\"note\":\"before update\"}");
            assertEquals(201, created.statusCode(), created.body());
            assertEquals(List.of(new BackupOrder(List.of("lobby-1"), false, "before update")), api.backend.backupOrders);
            assertEquals(400, api.send("POST", "/api/v1/backups", api.admin, "{\"services\":[\"../x\"]}").statusCode());
            String reader = api.token("backup-reader", Scope.BACKUPS_READ);
            assertEquals(403, api.send("POST", "/api/v1/backups", reader, "{}").statusCode());
        }
    }

    @Test
    void corsIsOffUnlessConfigured() throws Exception {
        try (TestApi api = new TestApi(home)) {
            HttpResponse<String> response = api.send(HttpRequest.newBuilder(api.uri("/api/v1/services"))
                    .header("Authorization", "Bearer " + api.admin).header("Origin", "https://evil.example").build());
            assertEquals(200, response.statusCode());
            assertNull(response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        }
        Path other = Files.createDirectory(home.resolve("cors"));
        try (TestApi api = new TestApi(other, s -> new ApiSettings(s.bind(), s.port(), s.tls(),
                List.of("https://dashboard.example.org"), s.limits()))) {
            HttpResponse<String> preflight = api.send(HttpRequest.newBuilder(api.uri("/api/v1/services"))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", "https://dashboard.example.org")
                    .header("Access-Control-Request-Method", "POST").build());
            assertEquals(204, preflight.statusCode());
            assertEquals("https://dashboard.example.org",
                    preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
            HttpResponse<String> allowed = api.send(HttpRequest.newBuilder(api.uri("/api/v1/services"))
                    .header("Authorization", "Bearer " + api.admin).header("Origin", "https://dashboard.example.org").build());
            assertEquals("https://dashboard.example.org", allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
            HttpResponse<String> foreign = api.send(HttpRequest.newBuilder(api.uri("/api/v1/services"))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST").build());
            assertEquals(401, foreign.statusCode());
            assertNull(foreign.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        }
    }

    @Test
    void responsesNeverContainSecrets() throws Exception {
        try (TestApi api = new TestApi(home)) {
            var secrets = new net.vectolyn.cloud.config.secret.SecretStore(CloudHome.of(home).secretsFile());
            secrets.ensureGenerated(new net.vectolyn.cloud.config.secret.SecretGenerator());
            var env = secrets.load();
            List<String> values = env.entries().stream().map(e -> e.value().reveal()).filter(v -> !v.isEmpty()).toList();
            assertFalse(values.isEmpty());
            api.backend.filter = net.vectolyn.cloud.config.secret.RedactingFilter.of(env);
            api.backend.failureMessage = "cannot start with password " + values.getFirst();
            api.config.apply(List.of(snapshot -> snapshot.withGroup("lobby", ApiSamples.lobbyGroup())
                    .withTask("lobby", ApiSamples.lobbyTask()), new SetServerWanted(ServerKind.MOD, false)));

            String tokenFile = Files.readString(api.tokens.file());
            String hash = tokenFile.substring(tokenFile.indexOf("\"sha256\"") + 12, tokenFile.indexOf("\"sha256\"") + 76);
            StringBuilder all = new StringBuilder();
            for (String path : List.of("/api/v1/services", "/api/v1/services/lobby-1", "/api/v1/groups", "/api/v1/tasks",
                    "/api/v1/groups/lobby", "/api/v1/servers", "/api/v1/config", "/api/v1/config/warnings", "/api/v1/node",
                    "/api/v1/setup", "/api/v1/tokens/self")) {
                HttpResponse<String> response = api.get(path, api.admin);
                assertEquals(200, response.statusCode(), path);
                all.append(response.body()).append(response.headers().map());
            }
            HttpResponse<String> failed = api.send("POST", "/api/v1/services", api.admin, "{\"task\":\"lobby\"}");
            assertEquals(503, failed.statusCode());
            assertTrue(failed.body().contains("***"), failed.body());
            all.append(failed.body());
            for (String value : values) {
                assertFalse(all.toString().contains(value), "a secret reached a response");
            }
            assertFalse(all.toString().contains(api.admin), "the token reached a response");
            assertFalse(all.toString().contains(hash), "the token hash reached a response");
        }
    }

    @Test
    void oddTargetsAndMethodsNeverReachAHandler() throws Exception {
        try (TestApi api = new TestApi(home)) {
            // Sent as raw bytes, so no client library normalizes them first.
            for (String target : List.of("//api/v1/services", "/api/v1//services", "/api/v1/services/",
                    "/api/v1/%2e%2e/services", "/api/v1/services/%2e%2e", "/api/v1/services/..", "/api/v1/./services",
                    "/api/v1/services/lobby%2D1", "/api/v1/services?x=%00", "http://127.0.0.1/api/v1/services")) {
                assertEquals(400, rawStatus(api, "GET", target, api.admin), target);
            }
            assertEquals(404, rawStatus(api, "GET", "/API/v1/services", api.admin));
            assertEquals(404, rawStatus(api, "GET", "/api/v1/Services", api.admin));
            assertEquals(405, rawStatus(api, "HEAD", "/api/v1/services", api.admin));
            assertEquals(405, rawStatus(api, "OPTIONS", "/api/v1/services", api.admin));
            assertEquals(405, rawStatus(api, "get", "/api/v1/services", api.admin));
            // Without a token the same targets are 401 before any of these rules.
            assertEquals(401, rawStatus(api, "GET", "/api/v1/%2e%2e/services", null));
            assertEquals(401, rawStatus(api, "HEAD", "/api/v1/services", null));
            // Counter-check: the clean target works over the same raw connection.
            assertEquals(200, rawStatus(api, "GET", "/api/v1/services", api.admin));
        }
    }

    @Test
    void concurrentWritesWithTheSameETagLetOnlyOneThrough() throws Exception {
        try (TestApi api = new TestApi(home)) {
            assertEquals(201, api.send("PUT", "/api/v1/groups/lobby", api.admin, GROUP).statusCode());
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                for (int round = 0; round < 5; round++) {
                    String etag = api.get("/api/v1/groups/lobby", api.admin).headers().firstValue("ETag").orElseThrow();
                    CountDownLatch start = new CountDownLatch(1);
                    List<Future<Integer>> results = new ArrayList<>();
                    for (int i = 0; i < 8; i++) {
                        String body = GROUP.replace("768M", (900 + round * 10 + i) + "M");
                        results.add(pool.submit(() -> {
                            start.await();
                            return api.send(HttpRequest.newBuilder(api.uri("/api/v1/groups/lobby"))
                                    .header("Authorization", "Bearer " + api.admin)
                                    .header("Content-Type", "application/json").header("If-Match", etag)
                                    .PUT(HttpRequest.BodyPublishers.ofString(body)).build()).statusCode();
                        }));
                    }
                    start.countDown();
                    int ok = 0;
                    int failed = 0;
                    for (Future<Integer> result : results) {
                        int status = result.get();
                        ok += status == 200 ? 1 : 0;
                        failed += status == 412 ? 1 : 0;
                    }
                    assertEquals(1, ok, "round " + round);
                    assertEquals(7, failed, "round " + round);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void apiAndDatabaseSectionsNeedAdmin() throws Exception {
        try (TestApi api = new TestApi(home)) {
            String writer = api.token("writer", Scope.CONFIG_WRITE);
            String cloud = api.get("/api/v1/config", api.admin).body();
            String docker = cloud.replace("\"mode\":\"managed\"", "\"mode\":\"docker\"")
                    .replace("\"user\":\"luckperms\"", "\"user\":\"luckperms\",\"docker-image\":\"evil/mariadb:1\"");
            HttpResponse<String> refused = api.send("PUT", "/api/v1/config", writer, docker);
            assertEquals(403, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("admin"), refused.body());
            String cors = cloud.replace("\"tls\":", "\"cors-origins\":[\"https://evil.example\"],\"tls\":");
            assertEquals(403, api.send("PUT", "/api/v1/config", writer, cors).statusCode());
            assertEquals("managed", api.config.snapshot().cloud().database().mode().name().toLowerCase(java.util.Locale.ROOT));
            // Counter-checks: other sections are fine with config:write, the same change is fine with admin.
            assertEquals(200, api.send("PUT", "/api/v1/config", writer,
                    cloud.replace("\"network-name\":\"Vectolyn\"", "\"network-name\":\"Other\"")).statusCode());
            String current = api.get("/api/v1/config", api.admin).body();
            assertEquals(200, api.send("PUT", "/api/v1/config", api.admin,
                    current.replace("\"tls\":", "\"cors-origins\":[\"https://dashboard.example\"],\"tls\":")).statusCode());
        }
    }

    private static int rawStatus(TestApi api, String method, String target, String token) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", api.address.getPort())) {
            socket.setSoTimeout(10_000);
            String request = method + " " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + (token == null ? "" : "Authorization: Bearer " + token + "\r\n") + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String status = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
            return Integer.parseInt(status.substring(9, 12));
        }
    }

    @Test
    void publicAddressWithoutTlsDoesNotStart() throws Exception {
        try (var config = net.vectolyn.cloud.config.ConfigService.open(CloudHome.of(home))) {
            var tokens = net.vectolyn.cloud.api.auth.ApiTokens.open(CloudHome.of(home));
            ApiServer server = ApiServer.builder(config, new FakeBackend(), tokens)
                    .settings(new ApiSettings("0.0.0.0", 0, java.util.Optional.empty(), List.of(), ApiLimits.defaults()))
                    .build();
            ApiStartException refused = org.junit.jupiter.api.Assertions.assertThrows(ApiStartException.class, server::start);
            assertTrue(refused.getMessage().contains("TLS"));
            server.close();
            // Counter-check: loopback without TLS starts.
            ApiServer local = ApiServer.builder(config, new FakeBackend(), tokens)
                    .settings(new ApiSettings("127.0.0.1", 0, java.util.Optional.empty(), List.of(), ApiLimits.defaults()))
                    .build();
            assertNotNull(local.start());
            local.close();
        }
    }

    @Test
    void aPortInUseLeavesNoEventLoopThreadBehind() throws Exception {
        java.util.Set<Thread> before = apiThreads();
        try (var config = net.vectolyn.cloud.config.ConfigService.open(CloudHome.of(home));
             java.net.ServerSocket taken = new java.net.ServerSocket(0, 1,
                     java.net.InetAddress.getByName("127.0.0.1"))) {
            var tokens = net.vectolyn.cloud.api.auth.ApiTokens.open(CloudHome.of(home));
            ApiServer server = ApiServer.builder(config, new FakeBackend(), tokens)
                    .settings(new ApiSettings("127.0.0.1", taken.getLocalPort(), java.util.Optional.empty(), List.of(),
                            ApiLimits.defaults()))
                    .build();
            java.io.IOException failed = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    server::start);
            assertTrue(failed.getMessage().contains("cannot listen on port " + taken.getLocalPort()), failed.getMessage());
            // The groups were shut down and waited for; a thread may need a moment to end after that.
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            java.util.Set<Thread> left = leftOver(before);
            while (!left.isEmpty() && System.nanoTime() < end) {
                Thread.sleep(20);
                left = leftOver(before);
            }
            assertEquals(java.util.Set.of(), left, "no vectolyn-api thread is left");
            server.close();
        }
    }

    private static java.util.Set<Thread> leftOver(java.util.Set<Thread> before) {
        java.util.Set<Thread> now = apiThreads();
        now.removeAll(before);
        return now;
    }

    /** Live platform threads of an API server (event loops); virtual request threads do not show. */
    private static java.util.Set<Thread> apiThreads() {
        java.util.Set<Thread> threads = new java.util.HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("vectolyn-api")) {
                threads.add(thread);
            }
        }
        return threads;
    }
}
