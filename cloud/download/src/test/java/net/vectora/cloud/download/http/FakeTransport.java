package net.vectora.cloud.download.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Canned HTTP responses by URL, so tests never touch the network. */
public final class FakeTransport implements HttpTransport {

    /** One canned answer: a response or an I/O error. */
    public sealed interface Answer permits Response, Failure {
    }

    /**
     * A canned response.
     *
     * @param status the status
     * @param headers the headers
     * @param body the body
     */
    public record Response(int status, Map<String, String> headers, byte[] body) implements Answer {
    }

    /**
     * A canned network error.
     *
     * @param message the message
     */
    public record Failure(String message) implements Answer {
    }

    private final Map<URI, Deque<Answer>> answers = new HashMap<>();
    private final List<URI> requests = new ArrayList<>();
    private final List<Map<String, String>> requestHeaders = new ArrayList<>();

    /**
     * Adds a 200 response. The last answer for a URL repeats forever.
     *
     * @param uri the URL
     * @param body the body
     * @return this
     */
    public FakeTransport ok(String uri, byte[] body) {
        return answer(uri, new Response(200, Map.of("Content-Length", Integer.toString(body.length)), body));
    }

    /**
     * Adds a 200 response with a text body.
     *
     * @param uri the URL
     * @param body the body
     * @return this
     */
    public FakeTransport ok(String uri, String body) {
        return ok(uri, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Adds a 200 response with the content of a test resource.
     *
     * @param uri the URL
     * @param resource the resource name below net/vectora/cloud/download/provider/
     * @return this
     */
    public FakeTransport okResource(String uri, String resource) {
        return ok(uri, resource(resource));
    }

    /**
     * Adds a status-only response.
     *
     * @param uri the URL
     * @param status the status
     * @return this
     */
    public FakeTransport status(String uri, int status) {
        return answer(uri, new Response(status, Map.of(), new byte[0]));
    }

    /**
     * Adds a redirect.
     *
     * @param uri the URL
     * @param status the redirect status
     * @param location the Location header
     * @return this
     */
    public FakeTransport redirect(String uri, int status, String location) {
        return answer(uri, new Response(status, Map.of("Location", location), new byte[0]));
    }

    /**
     * Adds a network error.
     *
     * @param uri the URL
     * @return this
     */
    public FakeTransport fail(String uri) {
        return answer(uri, new Failure("connection reset"));
    }

    /**
     * Adds any answer.
     *
     * @param uri the URL
     * @param answer the answer
     * @return this
     */
    public FakeTransport answer(String uri, Answer answer) {
        answers.computeIfAbsent(URI.create(uri), key -> new ArrayDeque<>()).add(answer);
        return this;
    }

    /**
     * Returns the requested URLs in order.
     *
     * @return the URLs
     */
    public List<URI> requests() {
        return List.copyOf(requests);
    }

    /**
     * Returns the request headers in order.
     *
     * @return the headers per request
     */
    public List<Map<String, String>> requestHeaders() {
        return List.copyOf(requestHeaders);
    }

    @Override
    public TransportResponse get(URI uri, Map<String, String> headers, Duration timeout) throws IOException {
        requests.add(uri);
        requestHeaders.add(Map.copyOf(headers));
        Deque<Answer> queue = answers.get(uri);
        if (queue == null || queue.isEmpty()) {
            throw new AssertionError("unexpected request " + uri);
        }
        Answer answer = queue.size() > 1 ? queue.poll() : queue.peek();
        if (answer instanceof Failure failure) {
            throw new IOException(failure.message());
        }
        Response response = (Response) answer;
        Map<String, List<String>> map = new HashMap<>();
        response.headers().forEach((key, value) -> map.put(key, List.of(value)));
        InputStream body = new ByteArrayInputStream(response.body());
        return new TransportResponse(response.status(), map, body);
    }

    /**
     * Loads a provider fixture.
     *
     * @param name the file name below net/vectora/cloud/download/provider/
     * @return the bytes
     */
    public static byte[] resource(String name) {
        try (InputStream in = FakeTransport.class.getResourceAsStream("/net/vectora/cloud/download/provider/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates a client on a fake transport that never sleeps.
     *
     * @param transport the transport
     * @return the client
     */
    public static DownloadClient client(FakeTransport transport) {
        return new DownloadClient(transport, DownloadSettings.defaults(), duration -> { });
    }
}
