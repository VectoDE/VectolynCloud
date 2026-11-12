// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.console;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import net.vectolyn.cloud.api.backend.NodeStatus;
import net.vectolyn.cloud.console.ConsoleOutput;
import net.vectolyn.cloud.console.Text;
import net.vectolyn.cloud.console.command.Command;

/**
 * {@code node status}: the running node as {@code GET /api/v1/node} shows it, with version, Java,
 * operating system, uptime, heap and the count of services. A subcommand of the {@code node}
 * command of cloud-console, added by the node because only it knows these values.
 */
public final class NodeStatusCommand {

    private NodeStatusCommand() {
    }

    /**
     * Builds the subcommand.
     *
     * @param status the current status, read again for every call
     * @return the subcommand
     */
    public static Command command(Supplier<NodeStatus> status) {
        Objects.requireNonNull(status, "status");
        return Command.builder("status")
                .description(Text.of("Shows version, Java, uptime, heap and services of this running node.",
                        "Zeigt Version, Java, Laufzeit, Heap und Dienste dieses laufenden Nodes."))
                .executes(context -> print(context.out(), status.get()))
                .build();
    }

    static void print(ConsoleOutput out, NodeStatus s) {
        out.heading(s.nodeId());
        out.println(Text.of("  Version:   %1$s", "  Version:   %1$s"), s.version());
        out.println(Text.of("  Java:      %1$s", "  Java:      %1$s"), s.javaVersion());
        out.println(Text.of("  System:    %1$s", "  System:    %1$s"), s.os());
        out.println(Text.of("  Uptime:    %1$s", "  Laufzeit:  %1$s"), uptime(s.uptimeSeconds()));
        out.println(Text.of("  Heap:      %1$s of %2$s", "  Heap:      %1$s von %2$s"),
                megabytes(s.heapUsedBytes()), megabytes(s.heapMaxBytes()));
        out.println(Text.of("  Services:  %1$s running, %2$s known", "  Dienste:   %1$s laufen, %2$s bekannt"),
                s.servicesRunning(), s.servicesTotal());
        if (s.shuttingDown()) {
            out.warning(Text.of("The node is shutting down.", "Der Node fährt herunter."));
        }
    }

    /** {@code 2d 3h 4m 5s}, leaving out leading zero parts. */
    static String uptime(long seconds) {
        Duration d = Duration.ofSeconds(Math.max(0, seconds));
        StringBuilder text = new StringBuilder();
        if (d.toDays() > 0) {
            text.append(d.toDays()).append("d ");
        }
        if (text.length() > 0 || d.toHoursPart() > 0) {
            text.append(d.toHoursPart()).append("h ");
        }
        if (text.length() > 0 || d.toMinutesPart() > 0) {
            text.append(d.toMinutesPart()).append("m ");
        }
        return text.append(d.toSecondsPart()).append('s').toString();
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%dM", Math.max(0, bytes) / (1024 * 1024));
    }
}
