// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the panel needs from the node beyond the REST API: the players online, a console line for a
 * running service, and the configuration archive. The node implements it; tests use a fake.
 */
public interface PanelNode {

    /**
     * A player on the network.
     *
     * @param uuid    the player's UUID
     * @param name    the name
     * @param proxy   the proxy service the player is on, or null if unknown
     * @param service the backend service, or null while between servers
     * @param bedrock whether the player joined through Geyser
     */
    record Player(String uuid, String name, @Nullable String proxy, @Nullable String service, boolean bedrock) {
    }

    /**
     * A checked import.
     *
     * @param files the files it changes, each with {@code added}, {@code changed} or {@code removed}
     * @param diff  a unified diff of every change
     */
    record ImportPlan(List<FileChange> files, String diff) {
    }

    /**
     * One file of an import.
     *
     * @param file the name in {@code config/}
     * @param kind {@code added}, {@code changed} or {@code removed}
     */
    record FileChange(String file, String kind) {
    }

    /** An archive breaks a rule; the message lists what is wrong and is meant for the user. */
    final class InvalidImport extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the failure.
         *
         * @param message what is wrong
         */
        public InvalidImport(String message) {
            super(message);
        }

        /**
         * Creates the failure with its cause.
         *
         * @param message what is wrong
         * @param cause   the error behind it
         */
        public InvalidImport(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The figures of one game mode (a group), without anything that names a player.
     *
     * @param mode            the group
     * @param logins          joins counted
     * @param playtimeSeconds play time in seconds
     * @param peakPlayers     most players at the same time
     */
    record ModeStatistics(String mode, long logins, long playtimeSeconds, int peakPlayers) {
    }

    /**
     * A line of the node's log that matches a search.
     *
     * @param file the log file, for example {@code cloud-2026-11-12.log}
     * @param line its line number, from 1
     * @param text the line, at most 2000 characters
     */
    record LogHit(String file, int line, String text) {
    }

    /**
     * The result of a search.
     *
     * @param hits      the lines, newest file first
     * @param truncated whether the search stopped at its limit of hits or bytes
     */
    record LogSearch(List<LogHit> hits, boolean truncated) {
    }

    /** Returns the statistics per game mode, by mode. */
    default List<ModeStatistics> statistics() {
        return List.of();
    }

    /**
     * Searches the node's own log ({@code logs/cloud.log} and its rotated files), which is written
     * with secrets already replaced.
     *
     * @param text  the text to find, case-insensitive
     * @param limit most hits
     * @return the hits
     * @throws IOException if a file cannot be read
     */
    default LogSearch searchLog(String text, int limit) throws IOException {
        return new LogSearch(List.of(), false);
    }

    /** Returns every player online, by name. */
    List<Player> players();

    /**
     * Sends a line to the console of a running service.
     *
     * @param serviceId the service
     * @param line      the command, already checked
     * @return false if the service does not run on this node
     */
    boolean command(String serviceId, String line);

    /**
     * Exports the configuration (never secrets).
     *
     * @return the zip archive
     * @throws IOException if the files cannot be read
     */
    byte[] exportConfig() throws IOException;

    /**
     * Checks an archive without writing anything.
     *
     * @param archive the zip archive
     * @return the plan
     * @throws InvalidImport if the archive or the configuration it holds is invalid
     * @throws IOException   if the current files cannot be read
     */
    ImportPlan planImport(byte[] archive) throws InvalidImport, IOException;

    /**
     * Checks and writes an archive.
     *
     * @param archive the zip archive
     * @return the files written or removed
     * @throws InvalidImport if the archive or the configuration it holds is invalid
     * @throws IOException   if writing fails
     */
    Set<String> applyImport(byte[] archive) throws InvalidImport, IOException;
}
