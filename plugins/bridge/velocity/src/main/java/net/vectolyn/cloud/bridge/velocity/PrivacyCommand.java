// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.bridge.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.vectolyn.cloud.bridge.BridgeSender;

/**
 * {@code /privacy}: the opt-in of the per-player tracking, in game (docs/cloud/privacy.md).
 *
 * <table>
 *   <caption>Subcommands</caption>
 *   <tr><td>{@code /privacy}</td><td>the notice: who is responsible, purpose, what is stored, how long</td></tr>
 *   <tr><td>{@code /privacy on}</td><td>shows the notice and asks for {@code /privacy on confirm}</td></tr>
 *   <tr><td>{@code /privacy on confirm}</td><td>records the consent</td></tr>
 *   <tr><td>{@code /privacy off}</td><td>withdraws and deletes everything about the player at once</td></tr>
 *   <tr><td>{@code /privacy data}</td><td>access: everything stored about the player</td></tr>
 *   <tr><td>{@code /privacy status}</td><td>whether the player agreed</td></tr>
 * </table>
 *
 * The text of the notice comes from the cloud; the cloud checks that the player is online through
 * this proxy. Nothing is recorded unless the player runs {@code /privacy on confirm}.
 */
final class PrivacyCommand implements SimpleCommand {

    private static final String CHANNEL = "vectolyn.privacy";

    private final Supplier<BridgeSender> sender;

    PrivacyCommand(Supplier<BridgeSender> sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("/privacy is for players."));
            return;
        }
        boolean german = player.getEffectiveLocale() != null
                && player.getEffectiveLocale().getLanguage().equals(Locale.GERMAN.getLanguage());
        String[] args = invocation.arguments();
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean confirm = args.length == 2 && args[1].equalsIgnoreCase("confirm");
        String request;
        Runnable after = null;
        switch (first) {
            case "", "info", "on" -> {
                // "on" without "confirm" only shows the notice, so nobody agrees without reading it.
                if (first.equals("on") && confirm) {
                    request = "grant";
                } else {
                    request = "notice " + (german ? "de" : "en");
                    if (first.equals("on")) {
                        after = () -> player.sendMessage(Component.text(german
                                ? "Zum Zustimmen: /privacy on confirm" : "To agree: /privacy on confirm"));
                    }
                }
            }
            case "off" -> request = "revoke";
            case "data" -> request = "data";
            case "status" -> request = "status";
            default -> {
                player.sendMessage(Component.text(german
                        ? "Benutzung: /privacy [on|off|data|status]" : "Usage: /privacy [on|off|data|status]"));
                return;
            }
        }
        String payload = player.getUniqueId() + " " + request;
        Runnable then = after;
        sender.get().request(CHANNEL, payload.getBytes(StandardCharsets.UTF_8)).whenComplete((answer, error) -> {
            if (error != null) {
                player.sendMessage(Component.text(german
                        ? "Die Datenschutzfunktion ist gerade nicht erreichbar."
                        : "The privacy service is not reachable right now."));
                return;
            }
            for (Component line : PrivacyMessages.lines(new String(answer, StandardCharsets.UTF_8), request, german)) {
                player.sendMessage(line);
            }
            if (then != null) {
                then.run();
            }
        });
    }
}
