package net.vectolyn.cloud.cluster;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.vectolyn.cloud.config.model.TimeSpan;
import net.vectolyn.cloud.console.ConsoleOutput;
import net.vectolyn.cloud.console.Text;
import net.vectolyn.cloud.console.command.Argument;
import net.vectolyn.cloud.console.command.Command;
import net.vectolyn.cloud.console.command.CommandException;
import net.vectolyn.protocol.NodeId;

/**
 * The {@code cluster} command of the console:
 *
 * <table>
 *   <caption>Subcommands</caption>
 *   <tr><td>{@code cluster init <host> [port] [bind]}</td><td>makes this node the head (CA, cluster port, default 7011)</td></tr>
 *   <tr><td>{@code cluster token create <node-id> [lifetime]}</td><td>a one-time join token, shown once with the whole join command</td></tr>
 *   <tr><td>{@code cluster token list}</td><td>open tokens without their secret</td></tr>
 *   <tr><td>{@code cluster token revoke <id>}</td><td>deletes a token</td></tr>
 *   <tr><td>{@code cluster join <host:port> <head-id> <token> <ca-pin>}</td><td>joins the cluster of a head</td></tr>
 *   <tr><td>{@code cluster status}</td><td>role, head, CA pin, members, link</td></tr>
 *   <tr><td>{@code cluster remove <node-id>}</td><td>revokes a member's certificate and ends its session</td></tr>
 * </table>
 *
 * <p>The whole command is {@link Command.Builder#sensitive() sensitive}: no line of it reaches the
 * console history, because {@code join} and {@code token create} carry a token.
 */
public final class ClusterCommands {

    /** Default cluster port. */
    public static final int DEFAULT_PORT = 7011;

    private final ClusterService cluster;

    /**
     * Creates the commands.
     *
     * @param cluster the cluster of this node
     */
    public ClusterCommands(ClusterService cluster) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
    }

    /**
     * Builds the {@code cluster} command.
     *
     * @return the command
     */
    public Command command() {
        return Command.builder("cluster")
                .description(Text.of("Sets up and manages a cluster of several nodes.",
                        "Richtet einen Cluster aus mehreren Nodes ein und verwaltet ihn."))
                .sensitive()
                .subcommand(Command.builder("init")
                        .description(Text.of("Makes this node the head of a new cluster.",
                                "Macht diesen Node zum Head eines neuen Clusters."))
                        .argument(Argument.required("host", Text.of("Address the other nodes reach this node at",
                                "Adresse, unter der die anderen Nodes diesen Node erreichen")))
                        .argument(Argument.optional("port", Text.of("Cluster port, default 7011",
                                "Cluster-Port, Standard 7011")))
                        .argument(Argument.optional("bind", Text.of("Address to listen on, default the host",
                                "Adresse zum Lauschen, Standard der Host")))
                        .executes(c -> init(c.out(), c.argument("host"), c.optional("port"), c.optional("bind"))))
                .subcommand(Command.builder("token")
                        .description(Text.of("Creates, lists and deletes join tokens.",
                                "Erstellt, listet und löscht Beitritts-Tokens."))
                        .subcommand(Command.builder("create")
                                .description(Text.of("Creates a one-time join token for a node id.",
                                        "Erstellt ein einmaliges Beitritts-Token für eine Node-ID."))
                                .argument(Argument.required("node-id", Text.of("node-id in the cloud.yml of the new node",
                                        "node-id in der cloud.yml des neuen Nodes")))
                                .argument(Argument.optional("lifetime", Text.of("1m to 24h, default 1h",
                                        "1m bis 24h, Standard 1h")))
                                .executes(c -> createToken(c.out(), c.argument("node-id"), c.optional("lifetime"))))
                        .subcommand(Command.builder("list")
                                .description(Text.of("Shows the open join tokens without their secret.",
                                        "Zeigt die offenen Beitritts-Tokens ohne ihr Geheimnis."))
                                .executes(c -> listTokens(c.out())))
                        .subcommand(Command.builder("revoke")
                                .aliases("delete", "remove")
                                .description(Text.of("Deletes a join token.", "Löscht ein Beitritts-Token."))
                                .argument(Argument.required("id", Text.of("Id of the token (the part before the dot)",
                                        "ID des Tokens (der Teil vor dem Punkt)")))
                                .executes(c -> revokeToken(c.out(), c.argument("id")))))
                .subcommand(Command.builder("join")
                        .description(Text.of("Joins the cluster of a head; the head's \"cluster token create\" "
                                + "prints the whole line.", "Tritt dem Cluster eines Heads bei; „cluster token "
                                + "create“ auf dem Head gibt die ganze Zeile aus."))
                        .argument(Argument.required("head", Text.of("host:port of the head", "host:port des Heads")))
                        .argument(Argument.required("head-id", Text.of("Node id of the head", "Node-ID des Heads")))
                        .argument(Argument.required("token", Text.of("The join token", "Das Beitritts-Token")))
                        .argument(Argument.required("ca-pin", Text.of("sha256:... pin of the cluster CA",
                                "sha256:...-Pin der Cluster-CA")))
                        .executes(c -> join(c.out(), c.argument("head"), c.argument("head-id"), c.argument("token"),
                                c.argument("ca-pin"))))
                .subcommand(Command.builder("status")
                        .aliases("nodes", "info")
                        .description(Text.of("Shows role, head, CA pin and members.",
                                "Zeigt Rolle, Head, CA-Pin und Mitglieder."))
                        .executes(c -> status(c.out())))
                .subcommand(Command.builder("remove")
                        .description(Text.of("Removes a member: revokes its certificate and ends its session.",
                                "Entfernt ein Mitglied: widerruft sein Zertifikat und beendet seine Sitzung."))
                        .argument(Argument.required("node-id", Text.of("The member", "Das Mitglied")))
                        .executes(c -> remove(c.out(), c.argument("node-id"))))
                .build();
    }

    private void init(ConsoleOutput out, String host, Optional<String> port, Optional<String> bind) throws Exception {
        int number = port.isPresent() ? port(port.get()) : DEFAULT_PORT;
        CaPin pin = call(() -> cluster.init(host, number, bind.orElse(null)));
        out.success(Text.of("This node is the head of a new cluster, cluster port %1$s.",
                "Dieser Node ist Head eines neuen Clusters, Cluster-Port %1$s."), number);
        out.println(Text.of("CA pin: %1$s", "CA-Pin: %1$s"), pin);
        out.println(Text.of("Next: \"cluster token create <node-id>\" for each node that joins. Open TCP port %1$s "
                        + "for the other nodes only (docs/cloud/cluster.md#firewall).",
                "Weiter: „cluster token create <node-id>“ für jeden Node, der beitritt. TCP-Port %1$s nur für die "
                        + "anderen Nodes öffnen (docs/cloud/cluster.md#firewall)."), number);
    }

    private void createToken(ConsoleOutput out, String nodeId, Optional<String> lifetime) throws Exception {
        NodeId node = nodeId(nodeId);
        Duration ttl = JoinTokens.DEFAULT_TTL;
        if (lifetime.isPresent()) {
            try {
                ttl = TimeSpan.parse(lifetime.get()).duration();
            } catch (IllegalArgumentException e) {
                throw new CommandException(Text.of("The lifetime must be 1m to 24h, for example 30m.",
                        "Die Laufzeit muss 1m bis 24h sein, zum Beispiel 30m."));
            }
        }
        Duration life = ttl;
        JoinTokens.Created created = call(() -> cluster.createToken(node, life));
        ClusterService.Status status = cluster.status();
        out.success(Text.of("Join token %1$s for %2$s, valid until %3$s (once).",
                "Beitritts-Token %1$s für %2$s, gültig bis %3$s (einmal)."),
                created.info().id(), node, created.info().expiresAt());
        out.println(Text.of("Run this on %1$s:", "Auf %1$s ausführen:"), node);
        // The one place the token is shown. Built from fixed alphabets, so println is safe.
        out.println("  cluster join " + status.headAddress().orElse("?") + " "
                + status.head().map(NodeId::value).orElse("?") + " " + created.token().reveal() + " "
                + status.pin().map(CaPin::toString).orElse("?"));
        out.warning(Text.of("Copy it now: the token is shown only this once and is not stored.",
                "Jetzt kopieren: Das Token wird nur dieses eine Mal angezeigt und nicht gespeichert."));
    }

    private void listTokens(ConsoleOutput out) throws Exception {
        List<JoinTokens.Info> all = call(cluster::tokens);
        if (all.isEmpty()) {
            out.println(Text.of("No open join tokens.", "Keine offenen Beitritts-Tokens."));
            return;
        }
        out.heading(Text.of("Join tokens", "Beitritts-Tokens").get(out.language()));
        for (JoinTokens.Info token : all) {
            out.println("  " + token.id() + "  " + token.node() + "  " + token.expiresAt());
        }
    }

    private void revokeToken(ConsoleOutput out, String id) throws Exception {
        if (!id.matches("[a-z0-9]{6}") || !call(() -> cluster.revokeToken(id))) {
            throw new CommandException(Text.of("No such token. \"cluster token list\" shows them.",
                    "Kein solches Token. „cluster token list“ zeigt sie."));
        }
        out.success(Text.of("Join token %1$s deleted.", "Beitritts-Token %1$s gelöscht."), id);
    }

    private void join(ConsoleOutput out, String head, String headId, String token, String pin) throws Exception {
        NodeId headNode = nodeId(headId);
        int colon = head.lastIndexOf(':');
        if (colon <= 0 || colon == head.length() - 1) {
            throw new CommandException(Text.of("The head must be given as host:port.",
                    "Der Head muss als host:port angegeben werden."));
        }
        String host = head.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port = port(head.substring(colon + 1));
        String target = host;
        out.println(Text.of("Joining %1$s ...", "Trete %1$s bei ..."), head);
        call(() -> {
            cluster.join(target, port, headNode, token, pin);
            return null;
        });
        out.success(Text.of("Joined the cluster of %1$s.", "Dem Cluster von %1$s beigetreten."),
                cluster.status().head().map(NodeId::value).orElse("?"));
    }

    private void status(ConsoleOutput out) {
        ClusterService.Status status = cluster.status();
        if (cluster.problem().isPresent()) {
            out.error(Text.of("config/cluster.yml cannot be used (%1$s). Repair it and restart the node.",
                    "config/cluster.yml ist nicht nutzbar (%1$s). Reparieren und den Node neu starten."),
                    cluster.problem().get());
            return;
        }
        if (status.role() == ClusterService.Role.STANDALONE) {
            out.println(Text.of("This node is in no cluster. \"cluster init <host>\" makes it a head, \"cluster join\" "
                    + "makes it a member.", "Dieser Node ist in keinem Cluster. „cluster init <host>“ macht ihn zum "
                    + "Head, „cluster join“ zum Mitglied."));
            return;
        }
        out.heading("Cluster");
        out.println(Text.of("  Role:        %1$s", "  Rolle:       %1$s"),
                status.role() == ClusterService.Role.HEAD ? "head" : "member");
        out.println(Text.of("  Head:        %1$s (%2$s)", "  Head:        %1$s (%2$s)"),
                status.head().map(NodeId::value).orElse("?"), status.headAddress().orElse("?"));
        out.println(Text.of("  Version:     %1$s", "  Version:     %1$s"), cluster.version());
        status.pin().ifPresent(pin -> out.println(Text.of("  CA pin:      %1$s", "  CA-Pin:      %1$s"), pin));
        status.certificateUntil().ifPresent(until -> out.println(Text.of("  Certificate: valid until %1$s",
                "  Zertifikat:  gültig bis %1$s"), until));
        status.link().ifPresent(state -> out.println(Text.of("  Link:        %1$s%2$s", "  Verbindung:  %1$s%2$s"),
                state.name().toLowerCase(java.util.Locale.ROOT),
                status.lastRefusal().map(r -> " (" + r.name().toLowerCase(java.util.Locale.ROOT) + ")").orElse("")));
        if (status.role() == ClusterService.Role.HEAD) {
            out.println(Text.of("  Revoked:     %1$s", "  Widerrufen:  %1$s"), status.revoked());
            for (ClusterService.MemberStatus member : status.members()) {
                out.println("  - " + member.id() + "  " + (member.connected() ? "connected" : "offline") + "  "
                        + member.joinedAt());
            }
        }
    }

    private void remove(ConsoleOutput out, String nodeId) throws Exception {
        NodeId node = nodeId(nodeId);
        call(() -> {
            cluster.remove(node);
            return null;
        });
        out.success(Text.of("%1$s removed; its certificate is revoked.", "%1$s entfernt; sein Zertifikat ist widerrufen."),
                node);
    }

    private static NodeId nodeId(String text) throws CommandException {
        try {
            return new NodeId(text);
        } catch (IllegalArgumentException e) {
            throw new CommandException(Text.of("Not a valid node id.", "Keine gültige Node-ID."));
        }
    }

    private static int port(String text) throws CommandException {
        try {
            int value = Integer.parseInt(text);
            if (value >= 1 && value <= 65535) {
                return value;
            }
        } catch (NumberFormatException e) {
            // below
        }
        throw new CommandException(Text.of("The port must be 1 to 65535.", "Der Port muss 1 bis 65535 sein."));
    }

    /** Runs an action and turns its failure into a console error in both languages. */
    private static <T> T call(Action<T> action) throws CommandException {
        try {
            return action.run();
        } catch (ClusterException e) {
            throw new CommandException(Text.of("%1$s", "%2$s"), e.message(net.vectolyn.cloud.config.model.Language.EN),
                    e.message(net.vectolyn.cloud.config.model.Language.DE));
        } catch (IOException e) {
            throw new CommandException(Text.of("Cluster: %1$s", "Cluster: %1$s"), String.valueOf(e.getMessage()));
        }
    }

    @FunctionalInterface
    private interface Action<T> {
        T run() throws ClusterException, IOException;
    }
}
