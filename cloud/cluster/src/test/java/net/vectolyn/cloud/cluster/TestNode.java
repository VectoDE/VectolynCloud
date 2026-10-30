package net.vectolyn.cloud.cluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.io.ConfigFormat;
import net.vectolyn.cloud.config.io.ConfigStore;
import net.vectolyn.cloud.config.io.ConfigYaml;
import net.vectolyn.cloud.config.model.CloudConfig;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.DatabaseConfig;
import net.vectolyn.cloud.config.model.DatabaseMode;
import net.vectolyn.cloud.config.model.NodeConfig;
import net.vectolyn.cloud.config.model.NodesConfig;
import net.vectolyn.cloud.config.model.PortRange;
import net.vectolyn.cloud.config.operation.SetDatabase;
import net.vectolyn.protocol.NodeId;

/** A node home in a temporary folder with its configuration and cluster service, for the tests. */
final class TestNode implements AutoCloseable {

    final Path home;
    final ClusterPaths paths;
    final NodeId id;
    final List<String> versionMismatches = Collections.synchronizedList(new ArrayList<>());
    final List<NodeId> connected = Collections.synchronizedList(new ArrayList<>());
    final List<NodeId> disconnected = Collections.synchronizedList(new ArrayList<>());
    ConfigService config;
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

    /** (Re)opens configuration and service, as a node start does. */
    void open(String version, Clock clock) throws IOException {
        close();
        CloudHome cloudHome = CloudHome.of(home);
        if (!Files.exists(cloudHome.cloudFile())) {
            // This node's own cloud.yml and nodes.yml, as a node that was set up alone has them.
            String cloud = new String(ConfigYaml.write(CloudConfig.defaults()), StandardCharsets.UTF_8)
                    .replace("node-id: node-1", "node-id: " + id.value());
            try {
                CloudConfig own = ConfigYaml.read("cloud.yml", cloud.getBytes(StandardCharsets.UTF_8), ConfigFormat.CLOUD)
                        .value();
                NodesConfig nodes = new NodesConfig(NodesConfig.SCHEMA_VERSION,
                        List.of(new NodeConfig(id.value(), "127.0.0.1", 7010, new PortRange(30000, 30999))));
                Files.createDirectories(cloudHome.configDir());
                new ConfigStore(cloudHome).save(null, ConfigSnapshot.of(own, nodes, java.util.Map.of(), java.util.Map.of()),
                        Set.of());
            } catch (ConfigException e) {
                throw new IOException(e);
            }
        }
        try {
            config = ConfigService.open(cloudHome);
        } catch (ConfigException e) {
            throw new IOException(e.describe(net.vectolyn.cloud.config.model.Language.EN), e);
        }
        cluster = ClusterService.open(paths, id, version, config, VersionPolicy.sameVersion(), clock);
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

    void database(DatabaseMode mode, String host) throws Exception {
        DatabaseConfig db = config.snapshot().cloud().database();
        config.apply(new SetDatabase(new DatabaseConfig(mode, host, db.port(), db.name(), db.user(), null)));
    }

    @Override
    public void close() {
        if (cluster != null) {
            cluster.close();
            cluster = null;
        }
        if (config != null) {
            try {
                config.close();
            } catch (IOException e) {
                // a test home; nothing to keep
            }
            config = null;
        }
    }
}
