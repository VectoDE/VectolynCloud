package net.vectolyn.cloud.bridge.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.vectolyn.cloud.bridge.PoweredBy;

/**
 * {@code /version} on the proxy while {@code branding.powered-by} is on: the cloud's version and
 * the address of its source code ({@link PoweredBy}, {@link VersionMessage}). Every player and the
 * console may run it; arguments are ignored.
 */
final class VersionCommand implements SimpleCommand {

    private final String version;

    /**
     * Creates the command.
     *
     * @param version the cloud's version, see {@link PoweredBy#version}
     */
    VersionCommand(String version) {
        this.version = Objects.requireNonNull(version, "version");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        Locale locale = source instanceof Player player ? player.getEffectiveLocale() : null;
        for (Component line : VersionMessage.of(version, locale)) {
            source.sendMessage(line);
        }
    }
}
