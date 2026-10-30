package net.vectolyn.cloud.cluster;

import java.io.IOException;

/**
 * What a member got from its head breaks the mirror's rules (a path outside the allowlist, a size
 * above a limit, a wrong SHA-256, an older revision, a config that does not validate). Nothing of
 * it was taken over; the message says why, without any of the content.
 */
final class MirrorException extends IOException {

    private static final long serialVersionUID = 1L;

    MirrorException(String message) {
        super(message);
    }
}
