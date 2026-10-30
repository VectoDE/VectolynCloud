package net.vectolyn.cloud.download.http;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One HTTP response as {@link HttpTransport} returns it.
 *
 * @param status the status code
 * @param headers response headers; names are matched case-insensitively
 * @param body the body stream; closing the response closes it
 */
public record TransportResponse(int status, Map<String, List<String>> headers, InputStream body) implements Closeable {

    /**
     * Checks the components.
     *
     * @param status the status code
     * @param headers response headers
     * @param body the body stream
     */
    public TransportResponse {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        headers = Map.copyOf(headers);
    }

    /**
     * Returns the first value of a header.
     *
     * @param name the header name, any case
     * @return the value, or empty
     */
    public Optional<String> header(String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return Optional.ofNullable(entry.getValue().get(0));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the announced body length.
     *
     * @return the Content-Length, or empty if missing or not a number
     */
    public OptionalLong contentLength() {
        Optional<String> value = header("Content-Length");
        if (value.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            long length = Long.parseLong(value.get().trim());
            return length >= 0 ? OptionalLong.of(length) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public void close() throws IOException {
        body.close();
    }
}
