// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.vectolyn.cloud.api.backend.ApiBackendException;
import net.vectolyn.cloud.api.backend.PrivacyAccess;
import net.vectolyn.cloud.privacy.TrackedPlayer;
import net.vectolyn.cloud.privacy.TrackingStore;

/** {@link PrivacyAccess} on the node's {@link TrackingStore}: the panel's access and deletion. */
public final class TrackingAccess implements PrivacyAccess {

    private final TrackingStore store;

    /**
     * Creates the access.
     *
     * @param store the tracking store
     */
    public TrackingAccess(TrackingStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Optional<Map<String, Object>> find(UUID player) {
        return store.find(player).map(TrackingAccess::describe);
    }

    @Override
    public boolean delete(UUID player) throws ApiBackendException {
        try {
            return store.revoke(player);
        } catch (IOException e) {
            throw new ApiBackendException(ApiBackendException.Reason.UNAVAILABLE,
                    "The tracking data could not be changed.");
        }
    }

    static Map<String, Object> describe(TrackedPlayer p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uuid", p.uuid().toString());
        map.put("name", p.name());
        map.put("consent-at", p.consentAt().toString());
        map.put("notice-version", p.noticeVersion());
        map.put("first-seen", p.firstSeen().toString());
        map.put("last-seen", p.lastSeen().toString());
        map.put("logins", p.logins());
        map.put("playtime-seconds", p.playtimeSeconds());
        List<Map<String, Object>> modes = new ArrayList<>();
        for (TrackedPlayer.ModeTotals m : p.modes()) {
            Map<String, Object> mode = new LinkedHashMap<>();
            mode.put("mode", m.mode());
            mode.put("logins", m.logins());
            mode.put("playtime-seconds", m.playtimeSeconds());
            modes.add(mode);
        }
        map.put("modes", modes);
        return map;
    }
}
