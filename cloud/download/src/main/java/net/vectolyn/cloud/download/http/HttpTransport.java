package net.vectolyn.cloud.download.http;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * The raw HTTP GET underneath {@link DownloadClient}. It must not follow redirects itself:
 * the client checks every redirect target against the allowlist before it follows it.
 *
 * <p>Production code uses {@link JdkHttpTransport}. Tests replace it with canned responses, so
 * they run without a network.
 */
@FunctionalInterface
public interface HttpTransport {

    /**
     * Sends one GET request.
     *
     * @param uri the URL, already checked against the allowlist
     * @param headers request headers (User-Agent, Accept)
     * @param timeout time until the response headers must have arrived
     * @return the response; the caller closes it
     * @throws IOException on a network error or timeout
     * @throws InterruptedException if the thread was interrupted while waiting
     */
    TransportResponse get(URI uri, Map<String, String> headers, Duration timeout)
            throws IOException, InterruptedException;
}
