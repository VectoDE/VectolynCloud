package net.vectora.cloud.cluster;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.protocol.NodeId;

/** A node home in a temporary folder with its cluster service, for the tests. */
final class TestNode implements AutoCloseable {

    final Path home;
    final ClusterPaths paths;
    final NodeId id;
    final AtomicReference<ConfigSnapshot> config = new AtomicReference<>(ConfigSnapshot.defaults());
    final List<String> versionMismatches = Collections.synchronizedList(new ArrayList<>());
    final List<NodeId> connected = Collections.synchronizedList(new ArrayList<>());
    final List<NodeId> disconnected = Collections.synchronizedList(new ArrayList<>());
    ClusterService cluster;

    TestNode(Path home, String id, String version) throws IOException {
        this(home, id, version, Clock.systemUTC());
    }

    TestNode(Path home, String id, String version, Clock clock) throws IOException {
        this.home = home;
        this.paths = new ClusterPaths(CloudHome.of(home));
        this.id = new NodeId(id);
        open(version, clock);
    }

    /** (Re)opens the service, as a node start does. */
    void open(String version, Clock clock) throws IOException {
        if (cluster != null) {
            cluster.close();
        }
        cluster = ClusterService.open(paths, id, version, config::get, VersionPolicy.sameVersion(), clock);
        cluster.events(new ClusterService.Events() {
            @Override
            public void memberConnected(NodeId member) {
                connected.add(member);
            }

            @Override
            public void memberDisconnected(NodeId member) {
                disconnected.add(member);
            }

            @Override
            public void versionMismatch(String own, String head) {
                versionMismatches.add(head);
            }
        });
    }

    /** A port that was free a moment ago. */
    static int freePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    void database(DatabaseMode mode, String host) {
        ConfigSnapshot current = config.get();
        DatabaseConfig db = current.cloud().database();
        config.set(current.withCloud(current.cloud().withDatabase(
                new DatabaseConfig(mode, host, db.port(), db.name(), db.user(), null))));
    }

    @Override
    public void close() {
        if (cluster != null) {
            cluster.close();
        }
    }
}
