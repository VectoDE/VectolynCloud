package net.vectora.cloud.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import net.vectora.cloud.api.auth.ApiTokens;
import net.vectora.cloud.api.auth.Scope;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.error.ConfigException;

/** A started API server on 127.0.0.1 with a temporary home, a fake node and an admin token. */
final class TestApi implements AutoCloseable {

    final Path home;
    final ConfigService config;
    final ApiTokens tokens;
    final FakeBackend backend = new FakeBackend();
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    final ApiServer server;
    final InetSocketAddress address;
    final String admin;
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    TestApi(Path home) throws IOException, ConfigException {
        this(home, s -> s);
    }

    TestApi(Path home, UnaryOperator<ApiSettings> settings) throws IOException, ConfigException {
        this.home = home;
        this.config = ConfigService.open(CloudHome.of(home));
        this.tokens = ApiTokens.open(CloudHome.of(home));
        this.admin = tokens.create("admin", EnumSet.of(Scope.ADMIN)).token().reveal();
        ApiSettings base = new ApiSettings("127.0.0.1", 0, Optional.empty(), List.of(), ApiLimits.defaults());
        this.server = ApiServer.builder(config, backend, tokens).settings(settings.apply(base)).nanoTime(nanos::get).build();
        this.address = server.start();
    }

    String token(String name, Scope... scopes) throws IOException {
        return tokens.create(name, Set.of(scopes)).token().reveal();
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + address.getPort() + path);
    }

    HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> send(String method, String path, String token, String json) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() throws IOException {
        server.close();
        config.close();
        http.close();
    }
}
