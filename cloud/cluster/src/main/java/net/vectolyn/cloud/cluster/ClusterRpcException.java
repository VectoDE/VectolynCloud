package net.vectolyn.cloud.cluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * A request on a cluster session failed: the peer answered with an error code, the request timed
 * out, too many were open, or the session ended. A handler throws it to answer with its code.
 *
 * <p>The code is all that crosses the wire: 1 to 32 characters of {@code [a-z0-9-]}, never an
 * exception text or a stack trace.
 */
public final class ClusterRpcException extends IOException {

    private static final long serialVersionUID = 1L;

    /** The request was not valid. */
    public static final String BAD_REQUEST = "bad-request";
    /** What the request names does not exist (any more). */
    public static final String NOT_FOUND = "not-found";
    /** What the request names changed since the peer asked for it; start over. */
    public static final String CHANGED = "changed";
    /** The handler failed; its node's log has the reason. */
    public static final String FAILED = "failed";
    /** No answer within the timeout. */
    public static final String TIMEOUT = "timeout";
    /** Too many requests are open on the session. */
    public static final String BUSY = "busy";
    /** The session ended before the answer came. */
    public static final String CLOSED = "closed";
    /** An error answer whose code is not valid. */
    public static final String UNKNOWN = "error";

    private static final Pattern CODE = Pattern.compile("[a-z0-9-]{1,32}");

    private final String code;

    /**
     * Creates the exception.
     *
     * @param code the code, 1 to 32 characters of {@code [a-z0-9-]}
     * @throws IllegalArgumentException if the code is not valid
     */
    public ClusterRpcException(String code) {
        super(checkCode(code));
        this.code = code;
    }

    /** The code. */
    public String code() {
        return code;
    }

    private static String checkCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("invalid error code");
        }
        return code;
    }

    /** The payload of an error answer with this code. */
    byte[] payload() {
        return code.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Reads the code from the payload of an error answer; anything that is no valid code becomes
     * {@link #UNKNOWN}.
     *
     * @param payload the payload, from the network
     * @return the code
     */
    static String code(byte[] payload) {
        if (payload.length == 0 || payload.length > 32) {
            return UNKNOWN;
        }
        for (byte b : payload) {
            boolean valid = (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') || b == '-';
            if (!valid) {
                return UNKNOWN;
            }
        }
        return new String(payload, StandardCharsets.US_ASCII);
    }
}
