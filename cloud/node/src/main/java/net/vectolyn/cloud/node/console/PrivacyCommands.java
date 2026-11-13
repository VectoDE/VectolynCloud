// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.console;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.vectolyn.cloud.api.backend.ApiBackendException;
import net.vectolyn.cloud.api.backend.PrivacyAccess;
import net.vectolyn.cloud.api.http.ApiJson;
import net.vectolyn.cloud.console.Text;
import net.vectolyn.cloud.console.command.Argument;
import net.vectolyn.cloud.console.command.Command;
import net.vectolyn.cloud.console.command.CommandContext;
import net.vectolyn.cloud.console.command.CommandException;
import net.vectolyn.cloud.console.prompt.PromptCancelledException;
import net.vectolyn.cloud.console.prompt.Question;

/**
 * {@code privacy show|delete <uuid>}: what the per-player tracking holds about a player who agreed
 * to it, and its deletion, the same as {@code GET} and {@code DELETE /api/v1/admin/privacy/players/{uuid}}
 * (docs/cloud/privacy.md).
 */
public final class PrivacyCommands {

    private static final List<String> YES_FLAGS = List.of("--yes", "-y");

    private PrivacyCommands() {
    }

    /**
     * Builds {@code privacy}.
     *
     * @param access the tracking access, read again for every call; empty while cloud-privacy is not wired in
     * @return the command
     */
    public static Command command(Supplier<Optional<PrivacyAccess>> access) {
        Objects.requireNonNull(access, "access");
        Argument player = Argument.required("uuid", Text.of("UUID of the player", "UUID des Spielers"));
        return Command.builder("privacy")
                .description(Text.of("Shows or deletes what the tracking stored about a player.",
                        "Zeigt oder löscht, was das Tracking über einen Spieler gespeichert hat."))
                .subcommand(Command.builder("show")
                        .description(Text.of("Shows the stored data of a player who agreed to the tracking.",
                                "Zeigt die gespeicherten Daten eines Spielers, der dem Tracking zugestimmt hat."))
                        .argument(player)
                        .executes(context -> show(context, access(access))))
                .subcommand(Command.builder("delete")
                        .description(Text.of("Deletes everything stored about a player, the consent included.",
                                "Löscht alles, was über einen Spieler gespeichert ist, samt Zustimmung."))
                        .argument(player)
                        .argument(Argument.optional("confirm", Text.of("--yes or -y deletes without asking (for scripts)",
                                "--yes oder -y löscht ohne Rückfrage (für Skripte)")).suggest(YES_FLAGS))
                        .executes(context -> delete(context, access(access))))
                .build();
    }

    private static PrivacyAccess access(Supplier<Optional<PrivacyAccess>> access) throws CommandException {
        return access.get().orElseThrow(() -> new CommandException(Text.of(
                "The tracking is not available on this node.", "Das Tracking ist auf diesem Node nicht verfügbar.")));
    }

    private static void show(CommandContext context, PrivacyAccess access) throws CommandException {
        UUID player = uuid(context.argument("uuid"));
        Map<String, Object> data = access.find(player).orElseThrow(() -> new CommandException(Text.of(
                "No tracking data for %1$s.", "Keine Tracking-Daten zu %1$s."), player));
        context.out().println(new String(ApiJson.write(data), StandardCharsets.UTF_8));
    }

    private static void delete(CommandContext context, PrivacyAccess access)
            throws CommandException, PromptCancelledException {
        UUID player = uuid(context.argument("uuid"));
        if (access.find(player).isEmpty()) {
            throw new CommandException(Text.of("No tracking data for %1$s.", "Keine Tracking-Daten zu %1$s."), player);
        }
        if (!confirm(context, player)) {
            context.out().println(Text.of("Nothing was deleted.", "Es wurde nichts gelöscht."));
            return;
        }
        try {
            if (access.delete(player)) {
                context.out().success(Text.of("Deleted the tracking data of %1$s.",
                        "Tracking-Daten von %1$s gelöscht."), player);
            } else {
                context.out().println(Text.of("No tracking data for %1$s.", "Keine Tracking-Daten zu %1$s."), player);
            }
        } catch (ApiBackendException e) {
            throw new CommandException(e, Text.of("Could not delete: %1$s", "Löschen fehlgeschlagen: %1$s"),
                    String.valueOf(e.getMessage()));
        }
    }

    private static boolean confirm(CommandContext context, UUID player) throws CommandException, PromptCancelledException {
        Optional<String> flag = context.optional("confirm");
        if (flag.isPresent() && !YES_FLAGS.contains(flag.get())) {
            throw new CommandException(Text.of("Only --yes or -y may follow.", "Danach ist nur --yes oder -y erlaubt."));
        }
        return flag.isPresent() || context.prompter().ask(Question.yesNo(Text.of("Really delete the tracking data of " + player + "?",
                "Tracking-Daten von " + player + " wirklich löschen?")).defaultValue(false));
    }

    /** A UUID in its usual 36-character form. */
    static UUID uuid(String text) throws CommandException {
        if (text.length() == 36) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                throw new CommandException(e, Text.of("Not a UUID: %1$s", "Keine UUID: %1$s"), text);
            }
        }
        throw new CommandException(Text.of("Not a UUID: %1$s", "Keine UUID: %1$s"), text);
    }
}
