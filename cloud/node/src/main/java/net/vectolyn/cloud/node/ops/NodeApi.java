package net.vectolyn.cloud.node.ops;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import net.vectolyn.cloud.api.ApiServer;
import net.vectolyn.cloud.api.backend.ApiBackend;
import net.vectolyn.cloud.api.backend.ApiBackendException;
import net.vectolyn.cloud.api.backend.BackupAccess;
import net.vectolyn.cloud.api.backend.NodeAccess;
import net.vectolyn.cloud.api.backend.NodeStatus;
import net.vectolyn.cloud.api.backend.ServiceAccess;
import net.vectolyn.cloud.api.backend.ServiceView;
import net.vectolyn.cloud.api.event.ApiEvent;
import net.vectolyn.cloud.node.channel.ChannelBridge;
import net.vectolyn.cloud.node.channel.NetworkPlayers;
import net.vectolyn.cloud.node.service.ServiceException;
import net.vectolyn.cloud.node.service.ServiceInfo;
import net.vectolyn.cloud.node.service.ServiceListener;
import net.vectolyn.cloud.node.service.ServiceManager;
import net.vectolyn.cloud.node.service.ServiceState;
import net.vectolyn.protocol.ServiceId;
import net.vectolyn.protocol.message.ServiceStatus;

/**
 * The node behind the API ({@code docs/cloud/api.md#wiring-in-cloud-node}): services over
 * {@link ServiceManager}, the node's status, the backups, and the events for the event stream.
 *
 * <p>Error texts reach the client. The service core's own refusals (unknown task, group not wanted,
 * setup not complete, already running) are passed on; anything else may carry a path or a tool's
 * output, so the client gets a short text and the node log the detail.
 */
public final class NodeApi implements ApiBackend, ServiceAccess, NodeAccess, ServiceListener, ChannelBridge.PlayerListener {

    private static final System.Logger LOG = System.getLogger(NodeApi.class.getName());

    private final ServiceManager services;
    private final ChannelBridge channel;
    private final String nodeId;
    private final String version;
    private final Instant startedAt;
    private final UnaryOperator<String> redactor;
    private final Optional<BackupAccess> backups;
    private volatile ApiServer server;
    private volatile boolean shuttingDown;

    /**
     * Creates the backend.
     *
     * @param services  the service core
     * @param channel   the cloud channel bridge, for the last status reports
     * @param nodeId    this node
     * @param version   the cloud's version
     * @param startedAt when the node started
     * @param redactor  the node's log filter for secrets
     * @param backups   the backups, if the node has them
     */
    public NodeApi(ServiceManager services, ChannelBridge channel, String nodeId, String version, Instant startedAt,
                   UnaryOperator<String> redactor, Optional<BackupAccess> backups) {
        this.services = Objects.requireNonNull(services, "services");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.version = Objects.requireNonNull(version, "version");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.backups = Objects.requireNonNull(backups, "backups");
    }

    /**
     * Sets the server the events go to, once it is built.
     *
     * @param value the API server
     */
    public void attach(ApiServer value) {
        this.server = Objects.requireNonNull(value, "value");
    }

    /** The node began to shut down: {@link #shuttingDown()} is true from now on. */
    public void beginShutdown() {
        shuttingDown = true;
    }

    // ------------------------------------------------------------------ ApiBackend

    @Override
    public ServiceAccess services() {
        return this;
    }

    @Override
    public NodeAccess node() {
        return this;
    }

    @Override
    public Optional<BackupAccess> backups() {
        return backups;
    }

    @Override
    public String redact(String text) {
        return redactor.apply(text);
    }

    // ------------------------------------------------------------------ ServiceAccess

    @Override
    public List<ServiceView> list() {
        return services.services().stream().map(this::view).toList();
    }

    @Override
    public Optional<ServiceView> find(String id) {
        return id(id).flatMap(services::find).map(this::view);
    }

    @Override
    public ServiceView start(String task) throws ApiBackendException {
        try {
            return view(services.start(task));
        } catch (ServiceException e) {
            throw translate("start " + task, e);
        }
    }

    @Override
    public ServiceView stop(String id) throws ApiBackendException {
        ServiceId service = known(id);
        try {
            return view(services.stop(service));
        } catch (ServiceException e) {
            throw translate("stop " + service, e);
        }
    }

    @Override
    public ServiceView restart(String id) throws ApiBackendException {
        ServiceId service = known(id);
        try {
            return view(services.restart(service));
        } catch (ServiceException e) {
            throw translate("restart " + service, e);
        }
    }

    @Override
    public void command(String id, String line) throws ApiBackendException {
        ServiceId service = known(id);
        try {
            services.sendCommand(service, line);
        } catch (ServiceException e) {
            throw translate("send a command to " + service, e);
        }
    }

    // ------------------------------------------------------------------ NodeAccess

    @Override
    public NodeStatus status() {
        List<ServiceInfo> all = services.services();
        int running = (int) all.stream().filter(info -> info.state() == ServiceState.RUNNING).count();
        return NodeStatus.ofThisJvm(nodeId, version, startedAt, shuttingDown, all.size(), running);
    }

    @Override
    public boolean shuttingDown() {
        return shuttingDown;
    }

    // ------------------------------------------------------------------ events

    @Override
    public void stateChanged(ServiceInfo service, ServiceState previous) {
        ApiServer target = server;
        if (target != null) {
            target.publish(new ApiEvent.ServiceState(view(service), previous == null ? null : lower(previous.name())));
        }
    }

    @Override
    public void output(ServiceId service, String line) {
        ApiServer target = server;
        if (target != null) {
            target.publish(new ApiEvent.ServiceOutput(service.value(), line));
        }
    }

    @Override
    public void changed(String kind, NetworkPlayers.Player player) {
        ApiServer target = server;
        if (target != null) {
            target.publish(new ApiEvent.Player(kind, player.uuid().toString(), player.name(), player.service().value()));
        }
    }

    // ------------------------------------------------------------------ helpers

    ServiceView view(ServiceInfo info) {
        Optional<ServiceStatus> status = channel.status(info.id());
        return new ServiceView(info.id().value(), info.group(), info.task(), info.index(), lower(info.mode().name()),
                lower(info.state().name()), info.port(),
                info.pid().isPresent() ? info.pid().getAsLong() : null,
                info.startedAt().map(Instant::toString).orElse(null),
                info.exitCode().isPresent() ? info.exitCode().getAsInt() : null,
                info.restartAttempts(),
                status.map(ServiceStatus::playersOnline).orElse(null),
                status.map(ServiceStatus::maxPlayers).orElse(null));
    }

    private ServiceId known(String id) throws ApiBackendException {
        Optional<ServiceId> service = id(id).filter(value -> services.find(value).isPresent());
        if (service.isEmpty()) {
            throw new ApiBackendException(ApiBackendException.Reason.NOT_FOUND, "No such service.");
        }
        return service.get();
    }

    private static Optional<ServiceId> id(String value) {
        try {
            return Optional.of(new ServiceId(value));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    /**
     * Maps a refusal of the service core. Its own short reasons go to the client; a message with
     * anything that looks like a path, or one of an unknown kind, stays in the node log.
     */
    static ApiBackendException translate(String what, ServiceException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        ApiBackendException.Reason reason = reasonOf(message);
        boolean safe = reason != null && message.indexOf('/') < 0 && message.indexOf('\\') < 0
                && message.length() <= 300 && message.chars().noneMatch(c -> c < 0x20);
        if (safe) {
            return new ApiBackendException(reason, message);
        }
        LOG.log(System.Logger.Level.WARNING, "API: cannot {0}: {1}", what, message);
        return new ApiBackendException(reason == null ? ApiBackendException.Reason.UNAVAILABLE : reason,
                "The node could not " + what + ". The node log has the details.");
    }

    private static ApiBackendException.Reason reasonOf(String message) {
        if (message.startsWith("unknown service") || message.contains("does not exist")) {
            return ApiBackendException.Reason.NOT_FOUND;
        }
        if (message.contains("setup is not complete") || message.contains("is stopping")
                || message.contains("shutting down")) {
            return ApiBackendException.Reason.UNAVAILABLE;
        }
        if (message.contains("is not wanted") || message.contains(" is already ") || message.contains("max-services")
                || message.contains("held by a backup") || message.contains("does not run on node")
                || message.contains("is not running") || message.contains("only one ")) {
            return ApiBackendException.Reason.CONFLICT;
        }
        return null;
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
