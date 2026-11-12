// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.backend;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Access and deletion of the per-player tracking for the panel (docs/cloud/privacy.md). Both calls
 * need the {@code admin} scope: the answer is personal data.
 */
public interface PrivacyAccess {

    /**
     * Everything stored about a player who agreed to the tracking.
     *
     * @param player the player's UUID
     * @return the record as a JSON object, or empty if the player did not agree
     */
    Optional<Map<String, Object>> find(UUID player);

    /**
     * Deletes everything stored about the player, the consent included.
     *
     * @param player the player's UUID
     * @return whether there was a record
     * @throws ApiBackendException if the deletion could not be written
     */
    boolean delete(UUID player) throws ApiBackendException;
}
