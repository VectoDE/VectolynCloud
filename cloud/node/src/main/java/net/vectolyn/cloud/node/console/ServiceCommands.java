package net.vectolyn.cloud.node.console;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.console.ConsoleOutput;
import net.vectolyn.cloud.console.Text;
import net.vectolyn.cloud.console.command.Argument;
import net.vectolyn.cloud.console.command.Command;
import net.vectolyn.cloud.console.command.CommandException;
import net.vectolyn.cloud.node.service.ServiceException;
import net.vectolyn.cloud.node.service.ServiceInfo;
import net.vectolyn.cloud.node.service.ServiceListener;
import net.vectolyn.cloud.node.service.ServiceManager;
import net.vectolyn.cloud.node.service.ServiceState;
import net.vectolyn.protocol.ServiceId;
import net.vectolyn.protocol.message.ServiceStatus;

/**
 * The {@code service} command of the console, registered by the node in the same registry as the
 * built-in commands:
 *
 * <table>
 *   <caption>Subcommands</caption>
 *   <tr><td>{@code service list}</td><td>every service with state, port, pid and players</td></tr>
 *   <tr><td>{@code service info <id>}</td><td>all details of one service, with its last status report</td></tr>
 *   <tr><td>{@code service start <task>}</td><td>starts one more service of a task</td></tr>
 *   <tr><td>{@code service stop <id>}</td><td>stops a service and waits for its end</td></tr>
 *   <tr><td>{@code service restart <id>}</td><td>stops and starts it again</td></tr>
 *   <tr><td>{@code service command <id> <line>}</td><td>types a line into the server's console</td></tr>
 *   <tr><td>{@code service screen <id>}</td><td>shows the server's output in the console, or stops
 *       showing it</td></tr>
 * </table>
 *
 * <p>Output lines shown by {@code screen} have passed the log filter already (secrets and the
 * service token are {@code ***}); control characters other than tabs are replaced with {@code ?},
 * so a server cannot send escape sequences to the operator's terminal.
 */
public final class ServiceCommands implements ServiceListener {

    private final ServiceManager services;
    private final ConfigService config;
    private final Function<ServiceId, Optional<ServiceStatus>> statuses;
    private final ConsoleOutput out;
    private final Set<ServiceId> followed = ConcurrentHashMap.newKeySet();

    /**
     * Creates the commands; {@link #command()} builds them, {@link ServiceManager#addListener}
     * makes {@code screen} work.
     *
     * @param services the service core
     * @param config the configuration, for the task names
     * @param statuses the last status report of a service (from the cloud channel)
     * @param out where {@code screen} prints
     */
    public ServiceCommands(ServiceManager services, ConfigService config,
            Function<ServiceId, Optional<ServiceStatus>> statuses, ConsoleOutput out) {
        this.services = Objects.requireNonNull(services, "services");
        this.config = Objects.requireNonNull(config, "config");
        this.statuses = Objects.requireNonNull(statuses, "statuses");
        this.out = Objects.requireNonNull(out, "out");
    }

    /**
     * Builds the {@code service} command.
     *
     * @return the command
     */
    public Command command() {
        Argument id = Argument.required("service", Text.of("Service id, for example lobby-1",
                "Dienst-ID, zum Beispiel lobby-1")).suggest(previous -> ids());
        return Command.builder("service")
                .aliases("services")
                .description(Text.of("Lists, starts, stops and watches the servers.",
                        "Listet, startet, stoppt und beobachtet die Server."))
                .subcommand(Command.builder("list")
                        .description(Text.of("Shows every service.", "Zeigt alle Dienste."))
                        .executes(context -> list(context.out())))
                .subcommand(Command.builder("info")
                        .description(Text.of("Shows one service in detail.", "Zeigt einen Dienst im Detail."))
                        .argument(id)
                        .executes(context -> info(context.out(), find(context.argument("service")))))
                .subcommand(Command.builder("start")
                        .description(Text.of("Starts one more service of a task.",
                                "Startet einen weiteren Dienst eines Tasks."))
                        .argument(Argument.required("task", Text.of("Task name", "Task-Name"))
                                .suggest(previous -> List.copyOf(config.snapshot().tasks().keySet())))
                        .executes(context -> {
                            ServiceInfo started = run(() -> services.start(context.argument("task")));
                            context.out().success(Text.of("Starting %1$s on port %2$s.", "Starte %1$s auf Port %2$s."),
                                    started.id().value(), started.port());
                        }))
                .subcommand(Command.builder("stop")
                        .description(Text.of("Stops a service and waits until it has ended.",
                                "Stoppt einen Dienst und wartet, bis er beendet ist."))
                        .argument(id)
                        .executes(context -> {
                            ServiceId target = find(context.argument("service")).id();
                            context.out().println(Text.of("Stopping %1$s ...", "Stoppe %1$s ..."), target.value());
                            ServiceInfo stopped = run(() -> services.stop(target));
                            context.out().success(Text.of("%1$s is %2$s.", "%1$s ist %2$s."), target.value(),
                                    state(stopped.state()));
                        }))
                .subcommand(Command.builder("restart")
                        .description(Text.of("Stops a service and starts it again.",
                                "Stoppt einen Dienst und startet ihn neu."))
                        .argument(id)
                        .executes(context -> {
                            ServiceId target = find(context.argument("service")).id();
                            ServiceInfo started = run(() -> services.restart(target));
                            context.out().success(Text.of("Restarted as %1$s.", "Neu gestartet als %1$s."),
                                    started.id().value());
                        }))
                .subcommand(Command.builder("command")
                        .aliases("cmd")
                        .description(Text.of("Types a line into the server's console.",
                                "Gibt eine Zeile in die Konsole des Servers ein."))
                        .argument(id)
                        .argument(Argument.required("line", Text.of("The command for the server, for example say hi",
                                "Der Befehl für den Server, zum Beispiel say hi")).takingRest())
                        .executes(context -> {
                            ServiceId target = find(context.argument("service")).id();
                            String line = context.argument("line");
                            run(() -> {
                                services.sendCommand(target, line);
                                return null;
                            });
                            context.out().println(Text.of("Sent to %1$s.", "An %1$s gesendet."), target.value());
                        }))
                .subcommand(Command.builder("screen")
                        .aliases("logs")
                        .description(Text.of("Shows the output of a service here, or stops showing it.",
                                "Zeigt die Ausgabe eines Dienstes hier an oder blendet sie wieder aus."))
                        .argument(id)
                        .executes(context -> {
                            ServiceId target = find(context.argument("service")).id();
                            if (followed.add(target)) {
                                context.out().success(Text.of(
                                        "Showing the output of %1$s. \"service screen %1$s\" again hides it.",
                                        "Zeige die Ausgabe von %1$s. Erneut „service screen %1$s“ blendet sie aus."),
                                        target.value());
                            } else {
                                followed.remove(target);
                                context.out().println(Text.of("Output of %1$s hidden.", "Ausgabe von %1$s ausgeblendet."),
                                        target.value());
                            }
                        }))
                .build();
    }

    /**
     * Returns the services whose output is shown right now.
     *
     * @return the followed ids
     */
    public Set<ServiceId> followed() {
        return Set.copyOf(followed);
    }

    private void list(ConsoleOutput target) {
        List<ServiceInfo> all = services.services();
        if (all.isEmpty()) {
            target.println(Text.of("No services. \"service start <task>\" starts one.",
                    "Keine Dienste. „service start <task>“ startet einen."));
            return;
        }
        int width = all.stream().mapToInt(s -> s.id().value().length()).max().orElse(0);
        target.heading(Text.of("Services", "Dienste").get(target.language()));
        for (ServiceInfo service : all) {
            String players = statuses.apply(service.id())
                    .map(s -> s.playersOnline() + "/" + s.maxPlayers())
                    .orElse("-");
            target.println("  " + pad(service.id().value(), width) + "  " + pad(state(service.state()), 8)
                    + "  port " + service.port()
                    + "  pid " + (service.pid().isPresent() ? Long.toString(service.pid().getAsLong()) : "-")
                    + "  " + Text.of("players", "Spieler").get(target.language()) + " " + players);
        }
    }

    private void info(ConsoleOutput target, ServiceInfo service) {
        target.heading(service.id().value());
        target.println("  group     " + service.group());
        target.println("  task      " + service.task() + " (" + service.mode().name().toLowerCase(Locale.ROOT) + ")");
        target.println("  state     " + state(service.state()));
        target.println("  port      " + service.port());
        target.println("  folder    " + service.workDirectory());
        service.pid().ifPresent(pid -> target.println("  pid       " + pid));
        service.startedAt().ifPresent(at -> target.println("  started   " + at));
        service.exitCode().ifPresent(code -> target.println("  exit code " + code));
        if (service.restartAttempts() > 0) {
            target.println("  restarts  " + service.restartAttempts());
        }
        statuses.apply(service.id()).ifPresent(status -> target.println(String.format(Locale.ROOT,
                "  status    %d/%d players, %.1f TPS, %.1f ms per tick, heap %d MiB",
                status.playersOnline(), status.maxPlayers(), status.tps(), status.mspt(), status.heapUsed() >> 20)));
    }

    private ServiceInfo find(String text) throws CommandException {
        ServiceId id;
        try {
            id = new ServiceId(text);
        } catch (IllegalArgumentException e) {
            throw new CommandException(Text.of("Not a service id. \"service list\" shows them.",
                    "Keine Dienst-ID. „service list“ zeigt sie."));
        }
        return services.find(id).orElseThrow(() -> new CommandException(Text.of(
                "Unknown service. \"service list\" shows every service.",
                "Unbekannter Dienst. „service list“ zeigt alle Dienste.")));
    }

    private List<String> ids() {
        return services.services().stream().map(s -> s.id().value()).toList();
    }

    @FunctionalInterface
    private interface Call {
        ServiceInfo call() throws ServiceException;
    }

    private static ServiceInfo run(Call call) throws CommandException {
        try {
            return call.call();
        } catch (ServiceException e) {
            throw new CommandException(Text.of("%1$s", "%1$s"), e.getMessage());
        }
    }

    private static String state(ServiceState state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    @Override
    public void stateChanged(ServiceInfo service, ServiceState previous) {
        if (previous != null && followed.contains(service.id())) {
            out.println("[" + service.id().value() + "] " + state(previous) + " -> " + state(service.state()));
        }
    }

    @Override
    public void output(ServiceId service, String line) {
        if (followed.contains(service)) {
            out.println("[" + service.value() + "] " + printable(line));
        }
    }

    /** Replaces control characters other than tabs with {@code ?}; the line has no line break. */
    static String printable(String line) {
        StringBuilder text = new StringBuilder(line.length());
        line.codePoints().forEach(c -> {
            if (c == '\t' || !(Character.isISOControl(c) || c == 0x2028 || c == 0x2029 || (c >= 0x202A && c <= 0x202E)
                    || (c >= 0x2066 && c <= 0x2069))) {
                text.appendCodePoint(c);
            } else {
                text.append('?');
            }
        });
        return text.toString();
    }
}
