package net.vectora.cloud.monitor.notify;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import net.vectora.cloud.monitor.alert.Alert;
import net.vectora.cloud.monitor.alert.AlertEvent;
import net.vectora.cloud.monitor.config.MonitorConfig.WebhookFormat;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Posts alerts as JSON to a webhook URL the operator enters in {@code secrets/vectora.env}, for
 * example a Discord channel webhook. Off by default.
 *
 * <ul>
 *   <li>HTTPS only (checked by {@link WebhookUrl}); the JDK's trust store decides about the
 *       certificate.</li>
 *   <li>Redirects are never followed, so a post cannot be sent on to another host. A 3xx answer
 *       counts as a failure.</li>
 *   <li>Connect and response time are limited by the configured timeout.</li>
 *   <li>The URL never appears in logs or error messages; failures name only the HTTP status or
 *       the kind of network error.</li>
 *   <li>The text contains rule names, service ids and numbers only, which the configuration and the
 *       protocol have validated. Discord posts set {@code allowed_mentions} to none, so nothing can
 *       ping {@code @everyone}.</li>
 * </ul>
 */
public final class WebhookNotifier implements Notifier {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WebhookUrl url;
    private final WebhookFormat format;
    private final Duration timeout;
    private final String origin;
    private final HttpClient client;

    /**
     * Creates the notifier with the JDK's default TLS settings.
     *
     * @param url     where to post
     * @param format  body format
     * @param timeout connect and response limit
     * @param origin  who sends, shown in the text, for example {@code VectoraCloud node-1}
     */
    public WebhookNotifier(WebhookUrl url, WebhookFormat format, Duration timeout, String origin) {
        this(url, format, timeout, origin, null);
    }

    /** Same, with a custom TLS context (tests trust a self-signed certificate). */
    WebhookNotifier(WebhookUrl url, WebhookFormat format, Duration timeout, String origin, SSLContext tls) {
        this.url = Objects.requireNonNull(url, "url");
        this.format = Objects.requireNonNull(format, "format");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.origin = Objects.requireNonNull(origin, "origin");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1);
        if (tls != null) {
            builder.sslContext(tls);
        }
        this.client = builder.build();
    }

    @Override
    public String name() {
        return "webhook (" + format.name().toLowerCase(java.util.Locale.ROOT) + ")";
    }

    @Override
    public void send(AlertEvent event) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(url.uri())
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "VectoraCloud-Monitor")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body(event)))
                .build();
        HttpResponse<Void> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (HttpConnectTimeoutException e) {
            throw new IOException("webhook: no connection within " + timeout.toSeconds() + " s");
        } catch (HttpTimeoutException e) {
            throw new IOException("webhook: no answer within " + timeout.toSeconds() + " s");
        } catch (SSLException e) {
            throw new IOException("webhook: TLS handshake failed (certificate not trusted or protocol mismatch)");
        } catch (ConnectException e) {
            throw new IOException("webhook: connection refused or host unreachable");
        } catch (IOException e) {
            // The JDK's message may contain the host or the URL; only the type is passed on.
            throw new IOException("webhook: network error (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("webhook: interrupted");
        }
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            throw new IOException("webhook: answered with a redirect (HTTP " + status + "); redirects are not followed");
        }
        if (status < 200 || status >= 300) {
            throw new IOException("webhook: answered with HTTP " + status);
        }
    }

    /** The JSON body for an event in the configured format. */
    byte[] body(AlertEvent event) {
        Alert alert = event.alert();
        ObjectNode root = JSON.createObjectNode();
        switch (format) {
            case DISCORD -> {
                root.put("content", text(event));
                root.putObject("allowed_mentions").putArray("parse");
            }
            case SLACK -> root.put("text", text(event));
            case JSON -> {
                root.put("event", kind(event));
                root.put("id", alert.id());
                root.put("rule", alert.rule());
                root.put("subject", alert.subject());
                root.put("severity", alert.severity().id());
                root.put("metric", alert.condition().metric().id());
                root.put("value", alert.value());
                root.put("condition", alert.condition().canonical());
                root.put("fired-at", alert.firedAt().toString());
                alert.resolvedAt().ifPresent(at -> root.put("resolved-at", at.toString()));
                alert.resolution().ifPresent(r -> root.put("resolution", r.name().toLowerCase(java.util.Locale.ROOT)));
                root.put("origin", origin);
                root.put("text", text(event));
            }
            default -> throw new IllegalStateException();
        }
        return JSON.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    }

    private String text(AlertEvent event) {
        Alert alert = event.alert();
        String what = switch (event.kind()) {
            case FIRED -> "Alert";
            case RESOLVED -> "Resolved";
            case ACKNOWLEDGED -> "Acknowledged";
        };
        return "[" + alert.severity().id().toUpperCase(java.util.Locale.ROOT) + "] " + what + " " + alert.summary()
                + " - " + origin;
    }

    private static String kind(AlertEvent event) {
        return event.kind().name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public void close() {
        client.close();
    }

    @Override
    public String toString() {
        return "WebhookNotifier[" + name() + ", url=***]";
    }
}
