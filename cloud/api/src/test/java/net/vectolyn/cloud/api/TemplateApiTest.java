// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.template.TemplateFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The template endpoints for the panel's editor, against a real server. */
@Timeout(120)
class TemplateApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FILE = "/api/v1/templates/Lobby-Server/files/default/plugins/LuckPerms/config.yml";

    @TempDir
    Path home;

    private void write(String path, byte[] content) throws Exception {
        Path file = CloudHome.of(home).templatesDir().resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    private HttpResponse<String> put(TestApi api, String path, String token, String json, String ifMatch)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(api.uri(path))
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token);
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        return api.send(b.build());
    }

    private static String body(String content) {
        return JSON.writeValueAsString(java.util.Map.of("content", content));
    }

    @Test
    void readAndEditAFileWithETags() throws Exception {
        write("Lobby-Server/default/plugins/LuckPerms/config.yml", "storage-method: h2\n".getBytes(StandardCharsets.UTF_8));
        write("Lobby-Server/default/forwarding.secret", "s3cret".getBytes(StandardCharsets.UTF_8));
        write("Lobby-Server/default/world/level.dat", new byte[] {0x1f, (byte) 0x8b, 0, 1});
        try (TestApi api = new TestApi(home)) {
            String reader = api.token("reader", Scope.TEMPLATES_READ);
            String writer = api.token("writer", Scope.TEMPLATES_WRITE);

            HttpResponse<String> list = api.get("/api/v1/templates", reader);
            assertEquals(200, list.statusCode(), list.body());
            assertEquals("Lobby-Server", JSON.readTree(list.body()).get(0).get("name").asString());

            HttpResponse<String> files = api.get("/api/v1/templates/Lobby-Server/files", reader);
            assertEquals(200, files.statusCode(), files.body());
            assertFalse(files.body().contains("forwarding.secret"), "secrets are never listed");
            assertTrue(files.body().contains("default/plugins/LuckPerms/config.yml"), files.body());

            HttpResponse<String> file = api.get(FILE, reader);
            assertEquals(200, file.statusCode(), file.body());
            JsonNode doc = JSON.readTree(file.body());
            assertEquals("utf-8", doc.get("encoding").asString());
            assertEquals("storage-method: h2\n", doc.get("content").asString());
            String etag = file.headers().firstValue("ETag").orElseThrow();
            assertEquals(TemplateFiles.etag("storage-method: h2\n".getBytes(StandardCharsets.UTF_8)), etag);

            HttpResponse<String> binary = api.get("/api/v1/templates/Lobby-Server/files/default/world/level.dat", reader);
            JsonNode bin = JSON.readTree(binary.body());
            assertEquals("base64", bin.get("encoding").asString());
            assertEquals(Base64.getEncoder().encodeToString(new byte[] {0x1f, (byte) 0x8b, 0, 1}), bin.get("content").asString());

            assertEquals(428, put(api, FILE, writer, body("storage-method: mariadb\n"), null).statusCode());
            assertEquals(412, put(api, FILE, writer, body("storage-method: mariadb\n"), "\"00\"").statusCode());
            HttpResponse<String> replaced = put(api, FILE, writer, body("storage-method: mariadb\n"), etag);
            assertEquals(200, replaced.statusCode(), replaced.body());
            String next = replaced.headers().firstValue("ETag").orElseThrow();
            assertEquals(412, put(api, FILE, writer, body("storage-method: h2\n"), etag).statusCode(), "stale ETag");
            assertEquals("storage-method: mariadb\n", Files.readString(
                    CloudHome.of(home).templatesDir().resolve("Lobby-Server/default/plugins/LuckPerms/config.yml")));

            HttpResponse<String> created = put(api, "/api/v1/templates/Lobby-Server/files/default/new/file.yml", writer,
                    JSON.writeValueAsString(java.util.Map.of("content", Base64.getEncoder().encodeToString(new byte[] {1, 2}),
                            "encoding", "base64")), null);
            assertEquals(201, created.statusCode(), created.body());
            assertFalse(next.isEmpty());
        }
    }

    @Test
    void secretsTraversalAndLimitsAreRefused() throws Exception {
        write("Lobby-Server/default/forwarding.secret", "s3cret".getBytes(StandardCharsets.UTF_8));
        try (TestApi api = new TestApi(home)) {
            String admin = api.admin;
            HttpResponse<String> secret = api.get("/api/v1/templates/Lobby-Server/files/default/forwarding.secret", admin);
            assertEquals(400, secret.statusCode(), secret.body());
            assertFalse(secret.body().contains("s3cret"));
            assertEquals(400, put(api, "/api/v1/templates/Lobby-Server/files/default/key.pem", admin, body("x"), null)
                    .statusCode());
            for (String target : new String[] {"/api/v1/templates/Lobby-Server/files/../secrets/vectolyn.env",
                    "/api/v1/templates/Lobby-Server/files/default/%2e%2e/x", "/api/v1/templates/..%2Fsecrets/files/x",
                    "/api/v1/templates/Lobby-Server/files/default/NUL"}) {
                HttpResponse<String> r = api.get(target, admin);
                assertEquals(400, r.statusCode(), target + " " + r.body());
            }
            assertEquals(404, api.get("/api/v1/templates/Missing/files", admin).statusCode());
            assertEquals(404, api.get("/api/v1/templates/Lobby-Server/files/default/missing.yml", admin).statusCode());

            String big = "x".repeat(TemplateFiles.MAX_WRITE_BYTES + 1);
            HttpResponse<String> tooLarge = put(api, "/api/v1/templates/Lobby-Server/files/default/big.txt", admin,
                    body(big), null);
            assertEquals(413, tooLarge.statusCode(), tooLarge.body());
            assertEquals(400, put(api, "/api/v1/templates/Lobby-Server/files/default/a.txt", admin,
                    "{\"content\":\"x\",\"encoding\":\"latin1\"}", null).statusCode());
        }
    }

    @Test
    void aClusterMemberRefusesWrites() throws Exception {
        write("Lobby-Server/default/server.properties", "motd=x\n".getBytes(StandardCharsets.UTF_8));
        try (TestApi api = new TestApi(home)) {
            api.config.mirrorMode(true);
            HttpResponse<String> refused = put(api, "/api/v1/templates/Lobby-Server/files/default/new.txt", api.admin,
                    body("x"), null);
            assertEquals(409, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("managed-by-head"), refused.body());
            assertFalse(Files.exists(CloudHome.of(home).templatesDir().resolve("Lobby-Server/default/new.txt")));
            assertEquals(200, api.get("/api/v1/templates/Lobby-Server/files/default/server.properties", api.admin)
                    .statusCode(), "reading stays possible");
        }
    }
}
