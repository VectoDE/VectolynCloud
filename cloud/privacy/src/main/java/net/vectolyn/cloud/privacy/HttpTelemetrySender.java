// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Posts the report as {@code application/json} to the configured endpoint. No redirects are
 * followed, no cookies and no credentials are sent, and the request carries only the report.
 */
public final class HttpTelemetrySender implements Telemetry.Sender {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final URI endpoint;
    private final HttpClient client;

    /**
     * Creates a sender.
     *
     * @param endpoint the HTTPS endpoint from {@code privacy.yml} (checked by the loader)
     */
    public HttpTelemetrySender(URI endpoint) {
        this(endpoint, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT).build());
    }

    HttpTelemetrySender(URI endpoint, HttpClient client) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void post(byte[] body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status < 200 || status > 299) {
            throw new IOException("telemetry endpoint answered with status " + status);
        }
    }
}
