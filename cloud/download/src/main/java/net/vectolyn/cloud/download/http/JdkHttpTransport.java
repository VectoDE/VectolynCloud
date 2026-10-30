package net.vectolyn.cloud.download.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * {@link HttpTransport} on the JDK {@link HttpClient}. Redirects are never followed here;
 * {@link DownloadClient} does that after checking each target.
 */
public final class JdkHttpTransport implements HttpTransport {

    private final HttpClient client;

    /**
     * Creates the transport.
     *
     * @param connectTimeout time allowed for the TCP and TLS handshake
     */
    public JdkHttpTransport(Duration connectTimeout) {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .build());
    }

    /**
     * Wraps an existing client. It must be configured with {@link HttpClient.Redirect#NEVER}.
     *
     * @param client the client
     * @throws IllegalArgumentException if the client follows redirects on its own
     */
    public JdkHttpTransport(HttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("the client must not follow redirects itself");
        }
    }

    @Override
    public TransportResponse get(URI uri, Map<String, String> headers, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).GET();
        headers.forEach(request::header);
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new TransportResponse(response.statusCode(), response.headers().map(), response.body());
    }
}
