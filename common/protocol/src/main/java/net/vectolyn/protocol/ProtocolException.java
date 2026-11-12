// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.protocol;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when received bytes do not form a valid frame or message. It is the only exception the
 * decoders throw for bad input; the connection that sent the bytes should then be closed.
 */
public class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates an exception with a message that describes what was wrong with the input. */
    public ProtocolException(@Nullable String message) {
        super(message);
    }

    /** Creates an exception with a message and the underlying cause. */
    public ProtocolException(@Nullable String message, Throwable cause) {
        super(message, cause);
    }
}
