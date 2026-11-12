// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.download;

/**
 * The requested artifact does not exist at the source, or not for this platform (for example
 * a MariaDB server for Linux on ARM).
 */
public final class ArtifactNotAvailableException extends DownloadException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was requested and why it is not available
     */
    public ArtifactNotAvailableException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the failure behind it.
     *
     * @param message what could not be found
     * @param cause   the failure behind it
     */
    public ArtifactNotAvailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
