package net.vectolyn.cloud.monitor.notify;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import net.vectolyn.cloud.config.secret.Secret;

/**
 * A checked webhook URL. It is treated as a secret (a Discord or Slack webhook URL is the only
 * credential needed to post), so {@link #toString()} prints {@code ***} and errors never quote it.
 *
 * <p>Rules: absolute {@code https} URL with a host, no user info, no fragment, at most
 * {@value #MAX_LENGTH} characters, printable ASCII only.
 */
public final class WebhookUrl {

    /** Longest URL. */
    public static final int MAX_LENGTH = 2048;

    private final Secret text;
    private final URI uri;

    private WebhookUrl(Secret text, URI uri) {
        this.text = text;
        this.uri = uri;
    }

    /**
     * Checks a URL.
     *
     * @param secret the URL from {@code vectolyn.env}
     * @return the checked URL
     * @throws IllegalArgumentException with a message that names the rule but not the URL
     */
    public static WebhookUrl of(Secret secret) {
        Objects.requireNonNull(secret, "secret");
        String value = secret.reveal();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("webhook URL must be 1 to " + MAX_LENGTH + " characters long");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c >= 0x7F) {
                throw new IllegalArgumentException("webhook URL must be printable ASCII without spaces");
            }
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("webhook URL is not a valid URL");
        }
        if (!uri.isAbsolute() || uri.getScheme() == null || !uri.getScheme().toLowerCase(Locale.ROOT).equals("https")) {
            throw new IllegalArgumentException("webhook URL must start with https://");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("webhook URL must name a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("webhook URL must not contain a user name or password before the host");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("webhook URL must not contain a fragment (#...)");
        }
        return new WebhookUrl(secret, uri);
    }

    /** The URI to post to. */
    public URI uri() {
        return uri;
    }

    /** Host of the URL, lower case. */
    public String host() {
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    /** The secret, for the log filter. */
    public Secret secret() {
        return text;
    }

    @Override
    public String toString() {
        return Secret.MASK;
    }
}
