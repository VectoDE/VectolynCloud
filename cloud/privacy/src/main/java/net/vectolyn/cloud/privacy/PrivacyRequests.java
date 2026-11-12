// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a proxy can ask the cloud on behalf of a player who typed {@code /privacy}. The request is
 * one line of text, the answer is text: the first line is a status word, the rest is the content.
 *
 * <table>
 *   <caption>Requests</caption>
 *   <tr><td>{@code notice de|en}</td><td>{@code ok} and the notice text</td></tr>
 *   <tr><td>{@code status}</td><td>{@code ok} and {@code consented} or {@code not-consented}</td></tr>
 *   <tr><td>{@code grant}</td><td>the player agrees; {@code ok}</td></tr>
 *   <tr><td>{@code revoke}</td><td>deletes everything about the player; {@code ok}</td></tr>
 *   <tr><td>{@code data}</td><td>{@code ok} and the record as JSON, or {@code not-consented}</td></tr>
 * </table>
 *
 * Failures answer {@code error disabled} (tracking is off), {@code error bad-request} or
 * {@code error unavailable} (the file could not be written). The caller (the node) has already
 * checked that the request comes from the proxy the player is connected through.
 */
public final class PrivacyRequests {

    /** Name of the request channel. */
    public static final String CHANNEL = "vectolyn.privacy";

    /** Longest request line accepted. */
    public static final int MAX_REQUEST = 32;

    private final TrackingStore store;
    private final PrivacyConfig.Tracking config;

    /**
     * Creates the handler.
     *
     * @param store  the tracking store
     * @param config the tracking settings
     */
    public PrivacyRequests(TrackingStore store, PrivacyConfig.Tracking config) {
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Answers one request.
     *
     * @param request the request line as sent
     * @param player  the player who asked, verified by the caller
     * @param name    the player's name
     * @param mode    the game mode the player is on, or null
     * @return the answer, UTF-8
     */
    public byte[] handle(byte[] request, UUID player, String name, @Nullable String mode) {
        return answer(request, player, name, mode).getBytes(StandardCharsets.UTF_8);
    }

    private String answer(byte[] request, UUID player, String name, @Nullable String mode) {
        if (!config.enabled()) {
            return "error disabled";
        }
        if (request.length == 0 || request.length > MAX_REQUEST) {
            return "error bad-request";
        }
        String[] words = new String(request, StandardCharsets.UTF_8).strip().toLowerCase(Locale.ROOT).split(" ", -1);
        try {
            return switch (words[0]) {
                case "notice" -> {
                    boolean german = words.length == 2 && words[1].equals("de");
                    if (words.length != 2 || !(german || words[1].equals("en"))) {
                        yield "error bad-request";
                    }
                    yield "ok\n" + TrackingNotice.text(german, config.controller(), config.retentionDays());
                }
                case "status" -> "ok\n" + (store.consented(player) ? "consented" : "not-consented");
                case "grant" -> {
                    store.grant(player, name, mode);
                    yield "ok";
                }
                case "revoke" -> {
                    store.revoke(player);
                    yield "ok";
                }
                case "data" -> {
                    Optional<TrackedPlayer> record = store.find(player);
                    yield record.isPresent()
                            ? "ok\n" + new String(TrackingStore.encodeOne(record.get()), StandardCharsets.UTF_8)
                            : "ok\nnot-consented";
                }
                default -> "error bad-request";
            };
        } catch (IOException | TrackingStore.FullException e) {
            return "error unavailable";
        } catch (IllegalArgumentException e) {
            return "error bad-request";
        }
    }
}
