package net.vectora.cloud.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.vectora.cloud.api.backend.ApiBackend;
import net.vectora.cloud.api.backend.ApiBackendException;
import net.vectora.cloud.api.backend.BackupAccess;
import net.vectora.cloud.api.backend.BackupOrder;
import net.vectora.cloud.api.backend.BackupView;
import net.vectora.cloud.api.backend.NodeAccess;
import net.vectora.cloud.api.backend.NodeStatus;
import net.vectora.cloud.api.backend.ServiceAccess;
import net.vectora.cloud.api.backend.ServiceView;
import net.vectora.cloud.config.secret.RedactingFilter;

/** A node in memory: services in a map, commands recorded, optional backups. */
final class FakeBackend implements ApiBackend, ServiceAccess, NodeAccess {

    final Map<String, ServiceView> services = new ConcurrentHashMap<>();
    final List<String> commands = new CopyOnWriteArrayList<>();
    final List<BackupOrder> backupOrders = new CopyOnWriteArrayList<>();
    final AtomicBoolean shuttingDown = new AtomicBoolean();
    volatile boolean backupsAvailable;
    volatile RedactingFilter filter = RedactingFilter.none();
    volatile String failureMessage;

    FakeBackend() {
        services.put("lobby-1", view("lobby-1", "running"));
    }

    static ServiceView view(String id, String state) {
        return new ServiceView(id, "lobby", "lobby", 1, "static", state, 30000, 1234L, "2026-10-29T10:00:00Z", null, 0,
                3, 50);
    }

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
        if (!backupsAvailable) {
            return Optional.empty();
        }
        return Optional.of(new BackupAccess() {
            @Override
            public List<BackupView> list() {
                return List.of(new BackupView("20261029-100000-manual", "2026-10-29T10:00:00Z", "manual",
                        List.of("lobby-1"), true, 1024, true, null, List.of()));
            }

            @Override
            public BackupView create(BackupOrder order) {
                backupOrders.add(order);
                return new BackupView("20261029-110000-manual", "2026-10-29T11:00:00Z", "manual",
                        order.services() == null ? List.of("lobby-1") : order.services(), order.database(), 2048,
                        true, null, List.of());
            }
        });
    }

    @Override
    public String redact(String text) {
        return filter.redact(text);
    }

    @Override
    public List<ServiceView> list() {
        return new ArrayList<>(services.values());
    }

    @Override
    public Optional<ServiceView> find(String id) {
        return Optional.ofNullable(services.get(id));
    }

    @Override
    public ServiceView start(String task) throws ApiBackendException {
        if (failureMessage != null) {
            throw new ApiBackendException(ApiBackendException.Reason.UNAVAILABLE, failureMessage);
        }
        ServiceView started = view(task + "-" + (services.size() + 1), "starting");
        services.put(started.id(), started);
        return started;
    }

    @Override
    public ServiceView stop(String id) {
        ServiceView stopped = view(id, "stopped");
        services.put(id, stopped);
        return stopped;
    }

    @Override
    public ServiceView restart(String id) {
        ServiceView started = view(id, "starting");
        services.put(id, started);
        return started;
    }

    @Override
    public void command(String id, String line) throws ApiBackendException {
        if (!"running".equals(services.get(id).state())) {
            throw new ApiBackendException(ApiBackendException.Reason.CONFLICT, "Service " + id + " is not running.");
        }
        commands.add(id + ": " + line);
    }

    @Override
    public NodeStatus status() {
        return NodeStatus.ofThisJvm("node-1", null, java.time.Instant.now().minusSeconds(5), shuttingDown.get(),
                services.size(), 1);
    }

    @Override
    public boolean shuttingDown() {
        return shuttingDown.get();
    }
}
