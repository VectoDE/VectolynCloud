package net.vectora.cloud.cluster;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import net.vectora.cloud.cluster.ClusterMessages.HelloOutcome;
import net.vectora.cloud.cluster.ClusterMessages.JoinOutcome;
import net.vectora.cloud.cluster.ClusterMessages.JoinReply;
import net.vectora.cloud.cluster.ClusterMessages.JoinRequest;
import net.vectora.cloud.cluster.ClusterMessages.NodeHello;
import net.vectora.cloud.cluster.ClusterMessages.NodeWelcome;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.protocol.NodeId;
import net.vectora.protocol.message.Disconnect;

/**
 * The cluster of one node: setting it up on the head ({@link #init}), join tokens, joining from a
 * member ({@link #join}), removing a member ({@link #remove}), and at run time the cluster port
 * of the head or the link of a member to it.
 *
 * <p>Trust model in short (details in {@code docs/cloud/cluster.md}):
 * <ul>
 *   <li>The head owns the CA. A node certificate names exactly one node id, taken from the join
 *       token, never from the joining node.</li>
 *   <li>A joining node checks the CA against the pin before it sends its token. The token is
 *       one-time, expires, and is bound to the node id; the file keeps its hash only.</li>
 *   <li>A member connects with mutual TLS 1.3. The head accepts a session only for a member whose
 *       certificate fingerprint is the one in {@code cluster.yml}, that is not revoked and runs the
 *       head's version ({@link VersionPolicy}). A member accepts only the head named in its
 *       {@code cluster.yml}.</li>
 *   <li>Joining is refused while the head runs H2 (single node only) or its database is on a
 *       loopback address the joining node cannot reach.</li>
 * </ul>
 */
public final class ClusterService implements AutoCloseable, ClusterServer.Handler {

    private static final System.Logger LOG = System.getLogger(ClusterService.class.getName());
    /** Host names that mean this machine. */
    private static final Pattern LOOPBACK_NAME = Pattern.compile("(?i)(.+\\.)?localhost\\.?|ip6-localhost|ip6-loopback");
    /** An IPv4 literal in any form Java reads ({@code 127.1}) or an IPv6 literal. */
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9.]{1,15}|[0-9A-Fa-f.]{0,40}:[0-9A-Fa-f:.]{1,44}");

    /** The role of this node. */
    public enum Role {
        /** Not in a cluster. */
        STANDALONE,
        /** The head: CA, members, cluster port. */
        HEAD,
        /** A member: link to the head. */
        MEMBER
    }

    /** What the cluster tells the node; every method has a default that does nothing. */
    public interface Events {

        /** A member's session with this head started. */
        default void memberConnected(NodeId member) {
        }

        /** A member's session with this head ended. */
        default void memberDisconnected(NodeId member) {
        }

        /**
         * The head refused this member because of its version. The update system ({@code #74})
         * takes this as the version to update to; until then the member stays out of the cluster.
         *
         * @param own  this node's version
         * @param head the head's version
         */
        default void versionMismatch(String own, String head) {
        }
    }

    /**
     * A member as {@link #status()} lists it.
     *
     * @param id        node id
     * @param joinedAt  when it joined
     * @param connected whether it has a session with this head now (the head itself counts as
     *                  connected)
     */
    public record MemberStatus(NodeId id, Instant joinedAt, boolean connected) {
    }

    /**
     * The state of the cluster.
     *
     * @param role             this node's role
     * @param head             the head's node id, if in a cluster
     * @param headAddress      the head's {@code host:port}, if in a cluster
     * @param pin              the CA pin, if known
     * @param members          the members (head only)
     * @param revoked          number of revoked certificates
     * @param link             the link state (member only)
     * @param lastRefusal      why the head refused this member last (member only)
     * @param certificateUntil when this node's certificate expires
     */
    public record Status(Role role, Optional<NodeId> head, Optional<String> headAddress, Optional<CaPin> pin,
                         List<MemberStatus> members, int revoked, Optional<ClusterLink.State> link,
                         Optional<HelloOutcome> lastRefusal, Optional<Instant> certificateUntil) {
    }

    private final ClusterPaths paths;
    private final NodeId self;
    private final String version;
    private final Supplier<ConfigSnapshot> config;
    private final VersionPolicy versions;
    private final Clock clock;
    private final JoinTokens tokens;
    private final Object joinLock = new Object();
    private final java.util.concurrent.atomic.AtomicLong refusedJoins = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong refusedHellos = new java.util.concurrent.atomic.AtomicLong();
    private volatile Events events = new Events() { };
    private volatile ClusterConfig cluster;
    private volatile ClusterServer server;
    private volatile ClusterLink link;
    private volatile boolean closed;
    /** Why {@code cluster.yml} could not be read at the start, or null. */
    private volatile String broken;

    private ClusterService(ClusterPaths paths, NodeId self, String version, Supplier<ConfigSnapshot> config,
                           VersionPolicy versions, Clock clock, ClusterConfig cluster) {
        this.paths = paths;
        this.self = self;
        this.version = ClusterMessages.checkVersion(version);
        this.config = config;
        this.versions = versions;
        this.clock = clock;
        this.cluster = cluster;
        this.tokens = new JoinTokens(paths.joinTokens());
    }

    /**
     * Reads {@code config/cluster.yml}; nothing is started.
     *
     * @param paths    files of the home
     * @param self     this node ({@code node-id} of {@code cloud.yml})
     * @param version  this node's VectoraCloud version
     * @param config   the configuration (database mode, network name)
     * @param versions the version rule
     * @param clock    time source
     * @return the service
     * @throws IOException if {@code cluster.yml} exists but cannot be read or is invalid
     */
    public static ClusterService open(ClusterPaths paths, NodeId self, String version, Supplier<ConfigSnapshot> config,
                                      VersionPolicy versions, Clock clock) throws IOException {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(clock, "clock");
        ClusterConfig cluster = ClusterFile.read(paths.clusterFile()).orElse(null);
        return new ClusterService(paths, self, safeVersion(version), config, versions, clock, cluster);
    }

    /**
     * Like {@link #open}, but a {@code cluster.yml} that cannot be read does not stop the node: it
     * runs outside the cluster, the problem is logged, and every {@code cluster} command reports it
     * until the file is repaired and the node restarted.
     */
    public static ClusterService openOrStandalone(ClusterPaths paths, NodeId self, String version,
                                                  Supplier<ConfigSnapshot> config, VersionPolicy versions, Clock clock) {
        try {
            return open(paths, self, version, config, versions, clock);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Cluster: config/cluster.yml cannot be used ({0}); this node runs "
                    + "outside the cluster until it is repaired and the node restarted. See docs/cloud/cluster.md.",
                    e.getMessage());
            ClusterService service = new ClusterService(paths, self, safeVersion(version), config, versions, clock, null);
            service.broken = e.getMessage() == null ? "unreadable" : e.getMessage();
            return service;
        }
    }

    /** A launcher version outside the jar is {@code dev}; anything else that is no valid version too. */
    private static String safeVersion(String value) {
        try {
            return ClusterMessages.checkVersion(value);
        } catch (IllegalArgumentException e) {
            return "dev";
        }
    }

    /** Sets who hears about sessions and version refusals. */
    public void events(Events value) {
        this.events = Objects.requireNonNull(value, "value");
    }

    /** This node's role. */
    public Role role() {
        ClusterConfig current = cluster;
        if (current == null) {
            return Role.STANDALONE;
        }
        return current.head().id().equals(self.value()) ? Role.HEAD : Role.MEMBER;
    }

    /** This node's version. */
    public String version() {
        return version;
    }

    /** The cluster file as it is now, if in a cluster. */
    public Optional<ClusterConfig> cluster() {
        return Optional.ofNullable(cluster);
    }

    // ---------------------------------------------------------------- head: set up and manage

    /**
     * Makes this node the head of a new cluster: creates the CA and this node's certificate, writes
     * {@code cluster.yml} and opens the cluster port.
     *
     * @param host address the members connect to
     * @param port cluster port
     * @param bind address to listen on, or null for {@code host}
     * @return the CA pin for the join commands
     * @throws ClusterException if the node is in a cluster already or the address is invalid
     * @throws IOException      if keytool or the files fail; what was created is removed again
     */
    public synchronized CaPin init(String host, int port, String bind) throws ClusterException, IOException {
        requireOpen();
        if (cluster != null) {
            throw new ClusterException("This node is in a cluster already.", "Dieser Node ist schon in einem Cluster.");
        }
        ClusterConfig.Head head = new ClusterConfig.Head(self.value(), host, port, bind);
        List<String> problems = new ClusterConfig(ClusterConfig.SCHEMA_VERSION, head, List.of(), List.of()).problems();
        if (!problems.isEmpty()) {
            throw new ClusterException("Invalid address: " + String.join("; ", problems),
                    "Ungültige Adresse: " + String.join("; ", problems));
        }
        if (Files.exists(paths.caKeystore()) || Files.exists(paths.clusterFile())) {
            throw new ClusterException("secrets/cluster/ca.p12 or config/cluster.yml exists already; remove them "
                    + "only if no cluster uses them.", "secrets/cluster/ca.p12 oder config/cluster.yml gibt es schon; "
                    + "nur entfernen, wenn kein Cluster sie nutzt.");
        }
        paths.ensureDir();
        boolean done = false;
        try {
            Secret caPassword = ClusterPaths.newPassword(paths.caPassword());
            ClusterCa ca = ClusterCa.create(paths.caKeystore(), caPassword, config.get().cloud().networkName());
            NodeIdentity identity = NodeIdentity.generate(paths.nodeKeystore(), ClusterPaths.newPassword(paths.nodePassword()));
            X509Certificate certificate = ca.issue(identity.certificationRequest(paths.workDir()), self, paths.workDir());
            identity.install(certificate, ca.certificate());
            ClusterConfig created = new ClusterConfig(ClusterConfig.SCHEMA_VERSION, head,
                    List.of(new ClusterConfig.Member(self.value(), NodeCertificates.fingerprint(certificate),
                            clock.instant().toString())), List.of());
            ClusterFile.write(paths.clusterFile(), created);
            cluster = created;
            done = true;
            LOG.log(System.Logger.Level.INFO, "Cluster: this node is the head now; CA pin {0}", ca.pin());
            warnAboutDatabase();
            startServer();
            return ca.pin();
        } finally {
            if (!done) {
                for (Path file : List.of(paths.caKeystore(), paths.caPassword(), paths.nodeKeystore(),
                        paths.nodePassword(), paths.clusterFile())) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /**
     * Creates a join token for a node.
     *
     * @param node the node id the joining node has in its {@code cloud.yml}
     * @param ttl  lifetime, 1 minute to 24 hours
     * @return the token, to be shown once
     * @throws ClusterException if this node is not the head, the node is a member already, or the
     *                          database serves one node only
     * @throws IOException      if the token file cannot be written
     */
    public JoinTokens.Created createToken(NodeId node, Duration ttl) throws ClusterException, IOException {
        requireHead();
        if (cluster.member(node).isPresent()) {
            throw new ClusterException("Node " + node + " is a member already.", "Node " + node + " ist schon Mitglied.");
        }
        DatabaseConfig database = config.get().cloud().database();
        if (database == null || database.mode() == DatabaseMode.H2) {
            throw new ClusterException("The cloud runs the embedded H2 database, which serves one node only. Switch "
                    + "database.mode in cloud.yml to external, a MariaDB server the nodes reach, first.",
                    "Die Cloud nutzt die eingebettete H2-Datenbank, die nur einen Node bedient. Zuerst database.mode in "
                            + "cloud.yml auf external stellen, einen MariaDB-Server, den die Nodes erreichen.");
        }
        try {
            return tokens.create(node, ttl, clock.instant());
        } catch (IllegalArgumentException e) {
            throw new ClusterException("The lifetime must be 1m to 24h.", "Die Laufzeit muss 1m bis 24h sein.");
        } catch (IllegalStateException e) {
            throw new ClusterException("At most " + JoinTokens.MAX_TOKENS + " join tokens can be open.",
                    "Höchstens " + JoinTokens.MAX_TOKENS + " Beitritts-Tokens können offen sein.");
        }
    }

    /** The open join tokens (head only). */
    public List<JoinTokens.Info> tokens() throws ClusterException, IOException {
        requireHead();
        return tokens.list(clock.instant());
    }

    /** Deletes a join token (head only). */
    public boolean revokeToken(String id) throws ClusterException, IOException {
        requireHead();
        return tokens.revoke(id);
    }

    /**
     * Removes a member: its certificate goes on the revocation list and its session ends at once.
     * It can join again only with a new token (and gets a new certificate).
     *
     * @param node the member
     * @throws ClusterException if this node is not the head, the node is the head or no member
     * @throws IOException      if {@code cluster.yml} cannot be written
     */
    public void remove(NodeId node) throws ClusterException, IOException {
        ClusterServer running;
        synchronized (this) {
            requireHead();
            if (node.equals(self)) {
                throw new ClusterException("The head cannot remove itself.", "Der Head kann sich nicht selbst entfernen.");
            }
            ClusterConfig.Member member = cluster.member(node).orElseThrow(() -> new ClusterException(
                    "Node " + node + " is no member.", "Node " + node + " ist kein Mitglied."));
            ClusterConfig next = cluster.withRevoked(member, clock.instant());
            ClusterFile.write(paths.clusterFile(), next);
            cluster = next;
            running = server;
        }
        // Outside the lock: ending a session must never hold up the other cluster commands. A
        // session that registers after this point is checked against the new list (admitted).
        if (running != null) {
            running.disconnect(node, Disconnect.Reason.AUTH_FAILED);
        }
        LOG.log(System.Logger.Level.WARNING, "Cluster: {0} removed, its certificate is revoked", node);
    }

    // ---------------------------------------------------------------- member: join

    /**
     * Joins the cluster of a head: makes this node's key, checks the head's CA against the pin,
     * sends the token and the certification request, installs the certificate, writes
     * {@code cluster.yml} and connects.
     *
     * @param host   the head's host
     * @param port   the head's cluster port
     * @param headId the head's node id; the head's certificate must name it
     * @param token  the join token {@code <id>.<secret>}
     * @param pin    the CA pin {@code sha256:<hex>}
     * @throws ClusterException if the node is in a cluster already, the token or pin is malformed, or
     *                          the head refused (the message says why)
     * @throws IOException      if the head cannot be reached, the CA does not match the pin, the
     *                          peer is not the head, or the files cannot be written
     */
    public synchronized void join(String host, int port, NodeId headId, String token, String pin)
            throws ClusterException, IOException {
        Objects.requireNonNull(headId, "headId");
        requireOpen();
        if (cluster != null) {
            throw new ClusterException("This node is in a cluster already.", "Dieser Node ist schon in einem Cluster.");
        }
        String[] parts = JoinTokens.split(token).orElseThrow(() -> new ClusterException(
                "The token must look like abcdef.0123456789abcdefghijklmnopqrstuv.",
                "Das Token muss wie abcdef.0123456789abcdefghijklmnopqrstuv aussehen."));
        CaPin caPin = CaPin.parse(pin).orElseThrow(() -> new ClusterException(
                "The CA pin must look like sha256:<64 hex digits>.", "Der CA-Pin muss wie sha256:<64 Hex-Ziffern> aussehen."));
        ClusterConfig.Head head = new ClusterConfig.Head(self.value(), host, port, null);
        if (!new ClusterConfig(ClusterConfig.SCHEMA_VERSION, head, List.of(), List.of()).problems().isEmpty()) {
            throw new ClusterException("Invalid address of the head.", "Ungültige Adresse des Heads.");
        }
        if (Files.exists(paths.caKeystore())) {
            throw new ClusterException("This node has a cluster CA (secrets/cluster/ca.p12); a head cannot join "
                    + "another cluster.", "Dieser Node hat eine Cluster-CA (secrets/cluster/ca.p12); ein Head kann "
                    + "keinem anderen Cluster beitreten.");
        }
        paths.ensureDir();
        boolean done = false;
        try {
            NodeIdentity identity = NodeIdentity.generate(paths.nodeKeystore(), ClusterPaths.newPassword(paths.nodePassword()));
            JoinRequest request = new JoinRequest(self, parts[0], Secret.of(parts[1]), version,
                    identity.certificationRequest(paths.workDir()));
            ClusterJoin.Result result = ClusterJoin.join(new InetSocketAddress(host, port), headId, caPin, request);
            JoinReply reply = result.reply();
            if (reply.outcome() != JoinOutcome.ACCEPTED) {
                throw refusal(reply);
            }
            X509Certificate certificate = Pem.certificate(reply.certificate());
            X509Certificate ca = Pem.certificate(reply.ca());
            if (!caPin.matches(ca) || !ca.equals(result.ca())) {
                throw new IOException("the CA in the head's answer is not the pinned one");
            }
            if (!reply.head().equals(result.head()) || !reply.head().equals(headId)) {
                throw new IOException("the head's answer names another node than its certificate");
            }
            try {
                certificate.verify(ca.getPublicKey());
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException("the certificate from the head is not signed by its CA", e);
            }
            if (!NodeCertificates.nodeId(certificate).map(self::equals).orElse(false)) {
                throw new IOException("the certificate from the head is for another node");
            }
            identity.install(certificate, ca);
            ClusterConfig joined = new ClusterConfig(ClusterConfig.SCHEMA_VERSION,
                    new ClusterConfig.Head(reply.head().value(), host, port, null), List.of(), List.of());
            ClusterFile.write(paths.clusterFile(), joined);
            cluster = joined;
            done = true;
            LOG.log(System.Logger.Level.INFO, "Cluster: joined the cluster of {0}", reply.head());
            startLink();
        } finally {
            if (!done) {
                Files.deleteIfExists(paths.nodeKeystore());
                Files.deleteIfExists(paths.nodePassword());
            }
        }
    }

    private static ClusterException refusal(JoinReply reply) {
        return switch (reply.outcome()) {
            case INVALID_TOKEN -> new ClusterException("The head refused the token: unknown, expired, used, or made "
                    + "for another node id. Create a new one on the head with \"cluster token create <node-id>\".",
                    "Der Head hat das Token abgelehnt: unbekannt, abgelaufen, benutzt oder für eine andere Node-ID. "
                            + "Auf dem Head mit „cluster token create <node-id>“ ein neues erstellen.");
            case VERSION_MISMATCH -> new ClusterException("The head runs VectoraCloud " + reply.headVersion()
                    + ". Update this node to the same version, then join again (the token stays valid).",
                    "Der Head läuft mit VectoraCloud " + reply.headVersion() + ". Diesen Node auf dieselbe Version "
                            + "aktualisieren und dann erneut beitreten (das Token bleibt gültig).");
            case DATABASE_SINGLE_NODE -> new ClusterException("The head runs the embedded H2 database, which serves "
                    + "one node only. Switch it to a MariaDB server the nodes reach (database.mode external) first.",
                    "Der Head nutzt die eingebettete H2-Datenbank, die nur einen Node bedient. Zuerst auf einen "
                            + "MariaDB-Server umstellen, den die Nodes erreichen (database.mode external).");
            case DATABASE_UNREACHABLE -> new ClusterException("The head's database listens on a loopback address, "
                    + "which this node cannot reach (managed and docker MariaDB always do). Use a MariaDB server on "
                    + "an address of the cluster network: database.mode external with that host in the head's "
                    + "cloud.yml.", "Die Datenbank des Heads lauscht auf einer Loopback-Adresse, die dieser Node "
                    + "nicht erreicht (managed und docker tun das immer). Einen MariaDB-Server auf einer Adresse im "
                    + "Cluster-Netz nutzen: database.mode external mit diesem Host in cloud.yml des Heads.");
            case NODE_EXISTS -> new ClusterException("A member with this node id exists already. Remove it on the "
                    + "head (\"cluster remove <node-id>\") or give this node another node-id.",
                    "Ein Mitglied mit dieser Node-ID gibt es schon. Es auf dem Head entfernen („cluster remove "
                            + "<node-id>“) oder diesem Node eine andere node-id geben.");
            case BAD_REQUEST, FAILED, ACCEPTED -> new ClusterException("The head could not finish the join; its log "
                    + "has the reason.", "Der Head konnte den Beitritt nicht abschließen; sein Log nennt den Grund.");
        };
    }

    // ---------------------------------------------------------------- run time

    /**
     * Opens the cluster port (head) or connects to the head (member). Nothing happens outside a
     * cluster.
     *
     * @throws IOException if this node's certificate cannot be read or does not fit, or the port
     *                     cannot be bound
     */
    public synchronized void start() throws IOException {
        requireOpenIo();
        switch (role()) {
            case HEAD -> {
                warnAboutDatabase();
                startServer();
            }
            case MEMBER -> startLink();
            case STANDALONE -> {
                // nothing to do
            }
        }
    }

    private NodeIdentity identity() throws IOException {
        NodeIdentity identity = NodeIdentity.open(paths.nodeKeystore(), ClusterPaths.readPassword(paths.nodePassword()));
        Optional<NodeId> certified = identity.nodeId();
        if (certified.isEmpty() || !certified.get().equals(self) || identity.caCertificate().isEmpty()) {
            throw new IOException("secrets/cluster/node.p12 holds no certificate for node " + self
                    + " (node-id in cloud.yml changed?)");
        }
        X509Certificate certificate = identity.certificate();
        Instant until = certificate.getNotAfter().toInstant();
        if (until.isBefore(clock.instant().plus(Duration.ofDays(30)))) {
            LOG.log(System.Logger.Level.WARNING, "Cluster: the certificate of this node expires on {0}; see "
                    + "docs/cloud/cluster.md for renewing it", until);
        }
        return identity;
    }

    private void startServer() throws IOException {
        if (server != null) {
            return;
        }
        NodeIdentity identity = identity();
        X509Certificate ca = identity.caCertificate().orElseThrow();
        // The membership and revocation checks happen at the hello, where the peer gets a clear
        // answer; the TLS layer checks the chain to the CA and that it is a node certificate.
        SSLContext context = ClusterTls.context(identity.keyManagers().getKeyManagers(),
                ClusterTrust.peers(ca, (node, fingerprint) -> { }));
        ClusterConfig.Head head = cluster.head();
        ClusterServer created = new ClusterServer(ClusterServer.Settings.of(head.bindAddress(), head.port(), self, version),
                context, this);
        InetSocketAddress bound = created.start();
        server = created;
        LOG.log(System.Logger.Level.INFO, "Cluster: head, cluster port {0}:{1,number,#} (mutual TLS 1.3)",
                bound.getHostString(), bound.getPort());
    }

    private void startLink() throws IOException {
        if (link != null) {
            return;
        }
        NodeIdentity identity = identity();
        X509Certificate ca = identity.caCertificate().orElseThrow();
        ClusterConfig current = cluster;
        NodeId head = new NodeId(current.head().id());
        KeyManager[] keys = identity.keyManagers().getKeyManagers();
        SSLContext context = ClusterTls.context(keys, ClusterTrust.peers(ca, (node, fingerprint) -> {
            if (!node.equals(head)) {
                throw new CertificateException("the peer is " + node + ", not the head " + head);
            }
            if (cluster != null && cluster.isRevoked(fingerprint)) {
                throw new CertificateException("the head's certificate is revoked");
            }
        }));
        ClusterLink created = new ClusterLink(ClusterLink.Settings.of(self, version,
                new InetSocketAddress(current.head().host(), current.head().port())), context, new ClusterLink.Listener() {
                    @Override
                    public void connected(NodeId node) {
                        LOG.log(System.Logger.Level.INFO, "Cluster: connected to the head {0}", node);
                    }

                    @Override
                    public void refused(HelloOutcome outcome, String headVersion) {
                        if (outcome == HelloOutcome.VERSION_MISMATCH) {
                            LOG.log(System.Logger.Level.ERROR, "Cluster: the head runs VectoraCloud {0}, this node {1}."
                                    + " Update this node to {0}; it stays out of the cluster until then.",
                                    headVersion, version);
                            events.versionMismatch(version, headVersion);
                        } else {
                            LOG.log(System.Logger.Level.ERROR, "Cluster: the head refused this node ({0}). See "
                                    + "docs/cloud/cluster.md.", outcome);
                        }
                    }

                    @Override
                    public void disconnected() {
                        LOG.log(System.Logger.Level.DEBUG, "Cluster: session with the head ended");
                    }
                });
        created.start();
        link = created;
    }

    /** The state of the cluster. */
    public Status status() {
        ClusterConfig current = cluster;
        Role role = role();
        Optional<CaPin> pin = Optional.empty();
        Optional<Instant> until = Optional.empty();
        if (current != null) {
            try {
                NodeIdentity identity = NodeIdentity.open(paths.nodeKeystore(), ClusterPaths.readPassword(paths.nodePassword()));
                pin = identity.caCertificate().map(CaPin::of);
                until = Optional.of(identity.certificate().getNotAfter().toInstant());
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "Cluster: certificate not readable for the status: {0}", e.getMessage());
            }
        }
        List<MemberStatus> members = new ArrayList<>();
        if (role == Role.HEAD) {
            ClusterServer running = server;
            Set<NodeId> connected = running == null ? Set.of() : running.connected();
            for (ClusterConfig.Member member : current.members()) {
                NodeId id = new NodeId(member.id());
                members.add(new MemberStatus(id, Instant.parse(member.joinedAt()), id.equals(self) || connected.contains(id)));
            }
        }
        ClusterLink running = link;
        return new Status(role, current == null ? Optional.empty() : Optional.of(new NodeId(current.head().id())),
                current == null ? Optional.empty() : Optional.of(hostPort(current.head().host(), current.head().port())),
                pin, List.copyOf(members), current == null ? 0 : current.revoked().size(),
                running == null ? Optional.empty() : Optional.of(running.state()),
                running == null ? Optional.empty() : Optional.ofNullable(running.lastRefusal()), until);
    }

    private static String hostPort(String host, int port) {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    // ---------------------------------------------------------------- the head's decisions

    @Override
    public JoinReply join(JoinRequest request, InetAddress from, java.util.function.BooleanSupplier connected) {
        synchronized (joinLock) {
            ClusterConfig current = cluster;
            NodeId head = self;
            // A join that waited for the lock past its deadline has no one left to answer: it must
            // neither use up the token nor add a member that never gets its certificate.
            if (closed || current == null || role() != Role.HEAD || !connected.getAsBoolean()) {
                return JoinReply.refused(JoinOutcome.FAILED, version, head);
            }
            // Checks that do not touch the token come first, so a node that has to be updated
            // or a database that has to be changed does not cost the token.
            if (!versions.compatible(version, request.version())) {
                LOG.log(refusalLevel(), "Cluster: {0} from {1} wants to join with version {2}, the head "
                        + "runs {3}", request.node(), from.getHostAddress(), request.version(), version);
                return JoinReply.refused(JoinOutcome.VERSION_MISMATCH, version, head);
            }
            Optional<JoinOutcome> database = databaseRefusal(config.get().cloud().database(), from);
            if (database.isPresent()) {
                LOG.log(refusalLevel(), "Cluster: join of {0} refused: {1}", request.node(), database.get());
                return JoinReply.refused(database.get(), version, head);
            }
            JoinTokens.Redemption redemption;
            try {
                redemption = tokens.redeem(request.tokenId(), request.secret(), request.node(), clock.instant());
            } catch (IOException e) {
                LOG.log(System.Logger.Level.ERROR, "Cluster: the join tokens cannot be read: {0}", e.getMessage());
                return JoinReply.refused(JoinOutcome.FAILED, version, head);
            }
            if (redemption != JoinTokens.Redemption.VALID) {
                LOG.log(refusalLevel(), "Cluster: join of {0} from {1} refused: invalid token",
                        request.node(), from.getHostAddress());
                return JoinReply.refused(JoinOutcome.INVALID_TOKEN, version, head);
            }
            if (request.node().equals(self) || current.member(request.node()).isPresent()) {
                return JoinReply.refused(JoinOutcome.NODE_EXISTS, version, head);
            }
            if (current.members().size() >= ClusterConfig.MAX_MEMBERS) {
                return JoinReply.refused(JoinOutcome.FAILED, version, head);
            }
            X509Certificate certificate;
            X509Certificate caCertificate;
            try {
                ClusterCa ca = ClusterCa.open(paths.caKeystore(), ClusterPaths.readPassword(paths.caPassword()));
                caCertificate = ca.certificate();
                certificate = ca.issue(request.request(), request.node(), paths.workDir());
                if (!NodeCertificates.acceptableKey(certificate.getPublicKey())) {
                    return JoinReply.refused(JoinOutcome.BAD_REQUEST, version, head);
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Cluster: no certificate for {0}: {1}", request.node(), e.getMessage());
                return JoinReply.refused(JoinOutcome.BAD_REQUEST, version, head);
            }
            try {
                // Against the file as it is now: a "cluster remove" may have run while keytool signed.
                synchronized (this) {
                    ClusterConfig latest = cluster;
                    if (closed || latest == null || latest.member(request.node()).isPresent()) {
                        return JoinReply.refused(JoinOutcome.NODE_EXISTS, version, head);
                    }
                    if (!connected.getAsBoolean()) {
                        LOG.log(System.Logger.Level.WARNING, "Cluster: join of {0} not saved: the connection "
                                + "ended before the certificate was ready; it needs a new token", request.node());
                        return JoinReply.refused(JoinOutcome.FAILED, version, head);
                    }
                    ClusterConfig next = latest.withMember(new ClusterConfig.Member(request.node().value(),
                            NodeCertificates.fingerprint(certificate), clock.instant().toString()));
                    ClusterFile.write(paths.clusterFile(), next);
                    cluster = next;
                }
                LOG.log(System.Logger.Level.INFO, "Cluster: {0} joined from {1}", request.node(), from.getHostAddress());
                return new JoinReply(JoinOutcome.ACCEPTED, version, head, certificate.getEncoded(),
                        caCertificate.getEncoded());
            } catch (IOException | CertificateEncodingException | IllegalArgumentException e) {
                LOG.log(System.Logger.Level.ERROR, "Cluster: join of {0} not saved: {1}", request.node(), e.getMessage());
                return JoinReply.refused(JoinOutcome.FAILED, version, head);
            }
        }
    }

    @Override
    public NodeWelcome hello(NodeHello hello, String fingerprint, InetAddress from) {
        ClusterConfig current = cluster;
        if (closed || current == null || role() != Role.HEAD) {
            return new NodeWelcome(HelloOutcome.NOT_HEAD, self, version);
        }
        if (current.isRevoked(fingerprint)) {
            LOG.log(helloRefusalLevel(), "Cluster: {0} tried to connect with a revoked certificate", hello.node());
            return new NodeWelcome(HelloOutcome.REVOKED, self, version);
        }
        Optional<ClusterConfig.Member> member = current.member(hello.node());
        if (hello.node().equals(self) || member.isEmpty() || !member.get().fingerprint().equals(fingerprint)) {
            LOG.log(helloRefusalLevel(), "Cluster: {0} is no member with this certificate", hello.node());
            return new NodeWelcome(HelloOutcome.UNKNOWN_NODE, self, version);
        }
        if (!versions.compatible(version, hello.version())) {
            LOG.log(helloRefusalLevel(), "Cluster: {0} runs version {1}, the head {2}; it stays out until "
                    + "it is updated", hello.node(), hello.version(), version);
            return new NodeWelcome(HelloOutcome.VERSION_MISMATCH, self, version);
        }
        // The database rule holds for sessions too: a head switched back to H2 (or to a database
        // on loopback) serves no member on another machine, not only no new one.
        if (databaseRefusal(config.get().cloud().database(), from).isPresent()) {
            LOG.log(helloRefusalLevel(), "Cluster: {0} stays out: the head's database serves this node only",
                    hello.node());
            return new NodeWelcome(HelloOutcome.DATABASE_SINGLE_NODE, self, version);
        }
        return new NodeWelcome(HelloOutcome.ACCEPTED, self, version);
    }

    /** Refused joins are warnings for the first ten and every 1000th, so a flood cannot fill the log. */
    private System.Logger.Level refusalLevel() {
        return level(refusedJoins.incrementAndGet());
    }

    /** The same for refused hellos: a member that reconnects in a loop must not fill the log. */
    private System.Logger.Level helloRefusalLevel() {
        return level(refusedHellos.incrementAndGet());
    }

    private static System.Logger.Level level(long count) {
        return count <= 10 || count % 1000 == 0 ? System.Logger.Level.WARNING : System.Logger.Level.DEBUG;
    }

    @Override
    public boolean admitted(NodeId node, String fingerprint) {
        ClusterConfig current = cluster;
        return !closed && current != null && role() == Role.HEAD && !current.isRevoked(fingerprint)
                && current.member(node).map(m -> m.fingerprint().equals(fingerprint)).orElse(false);
    }

    @Override
    public void connected(NodeId node) {
        LOG.log(System.Logger.Level.INFO, "Cluster: {0} connected", node);
        events.memberConnected(node);
    }

    @Override
    public void disconnected(NodeId node) {
        LOG.log(System.Logger.Level.INFO, "Cluster: {0} disconnected", node);
        events.memberDisconnected(node);
    }

    /**
     * Why the database rules out a join from {@code from}: H2 serves one node, and a database on a
     * loopback address is out of reach of a node on another machine. Managed and Docker MariaDB
     * listen on 127.0.0.1 whatever host the config names, so only an external server serves nodes
     * on other machines until the head binds its own MariaDB to the cluster network.
     */
    static Optional<JoinOutcome> databaseRefusal(DatabaseConfig database, InetAddress from) {
        if (database == null || database.mode() == DatabaseMode.H2) {
            return Optional.of(JoinOutcome.DATABASE_SINGLE_NODE);
        }
        if (from.isLoopbackAddress()) {
            return Optional.empty();
        }
        boolean loopbackOnly = database.mode() != DatabaseMode.EXTERNAL
                || database.host() == null || isLocalHost(database.host());
        return loopbackOnly ? Optional.of(JoinOutcome.DATABASE_UNREACHABLE) : Optional.empty();
    }

    /**
     * Whether a configured host means this machine only: a loopback or wildcard address in any
     * literal form, or a name such as {@code localhost}. Names are not resolved; this is a check
     * of the obvious mistakes, not a proof that another node reaches the host.
     */
    static boolean isLocalHost(String host) {
        String name = host.strip();
        if (name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1);
        }
        if (name.isEmpty() || LOOPBACK_NAME.matcher(name).matches()) {
            return true;
        }
        if (!IP_LITERAL.matcher(name).matches()) {
            return false;
        }
        try {
            // A literal: getByName parses it without a lookup.
            InetAddress address = InetAddress.getByName(name);
            return address.isLoopbackAddress() || address.isAnyLocalAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    private void warnAboutDatabase() {
        DatabaseConfig database = config.get().cloud().database();
        if (database == null || database.mode() == DatabaseMode.H2) {
            LOG.log(System.Logger.Level.WARNING, "Cluster: the cloud runs the embedded H2 database, which serves one "
                    + "node only; no node can join until database.mode is external, a MariaDB server the nodes "
                    + "reach.");
        } else if (database.mode() != DatabaseMode.EXTERNAL) {
            LOG.log(System.Logger.Level.WARNING, "Cluster: the {0} MariaDB listens on 127.0.0.1, so only nodes on "
                    + "this machine can join; for nodes on other machines use database.mode external with an address "
                    + "of the cluster network.", database.mode().name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private void requireHead() throws ClusterException {
        requireOpen();
        if (role() != Role.HEAD) {
            throw new ClusterException("Only the head of a cluster can do that (\"cluster init\" makes one).",
                    "Das kann nur der Head eines Clusters („cluster init“ macht einen).");
        }
    }

    private void requireOpen() throws ClusterException {
        if (closed) {
            throw new ClusterException("The node is shutting down.", "Der Node fährt herunter.");
        }
        String problem = broken;
        if (problem != null) {
            throw new ClusterException("config/cluster.yml cannot be used (" + problem + "). Repair it and restart "
                    + "the node.", "config/cluster.yml ist nicht nutzbar (" + problem + "). Reparieren und den Node "
                    + "neu starten.");
        }
    }

    /** Why {@code cluster.yml} could not be read at the start, if it could not. */
    public Optional<String> problem() {
        return Optional.ofNullable(broken);
    }

    private void requireOpenIo() throws IOException {
        if (closed) {
            throw new IOException("the node is shutting down");
        }
    }

    /** Closes the cluster port or the link. */
    @Override
    public synchronized void close() {
        closed = true;
        ClusterServer runningServer = server;
        if (runningServer != null) {
            runningServer.close();
        }
        ClusterLink runningLink = link;
        if (runningLink != null) {
            runningLink.close();
        }
    }
}
