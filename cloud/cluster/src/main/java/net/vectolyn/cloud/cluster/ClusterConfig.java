package net.vectolyn.cloud.cluster;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.vectolyn.protocol.NodeId;

/**
 * {@code config/cluster.yml}: which node is the head and where it listens, the members with the
 * fingerprint of their certificate, and the revoked certificates. The head writes it; a member has
 * a copy with the head's address. Without the file the node is not in a cluster.
 *
 * <pre>
 * schema-version: 1
 * head:
 *   id: node-1
 *   host: 10.0.0.1       # address the members connect to
 *   port: 7011           # cluster port (mutual TLS 1.3)
 *   bind: 0.0.0.0        # optional, address the head listens on; default host
 * members:
 *   - id: node-2
 *     fingerprint: 3f...  # SHA-256 of its certificate
 *     joined-at: 2026-10-30T12:00:00Z
 * revoked:
 *   - id: node-3
 *     fingerprint: 9a...
 *     revoked-at: 2026-10-30T13:00:00Z
 * </pre>
 *
 * @param schemaVersion format version, {@value #SCHEMA_VERSION}
 * @param head          the head
 * @param members       every member including the head
 * @param revoked       revoked certificates
 */
@JsonPropertyOrder({"schema-version", "head", "members", "revoked"})
public record ClusterConfig(@JsonProperty(required = true) int schemaVersion, Head head, List<Member> members,
                            List<Revoked> revoked) {

    /** Current format version. */
    public static final int SCHEMA_VERSION = 1;
    /** Most members a cluster may have. */
    public static final int MAX_MEMBERS = 64;
    /** Most entries of the revocation list. */
    public static final int MAX_REVOKED = 4096;
    /** A host: a DNS name, an IPv4 address or an IPv6 literal without brackets. */
    static final Pattern HOST = Pattern.compile(
            "([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*"
                    + "|[0-9A-Fa-f:.]{2,45}");

    /** Fills in empty lists. */
    public ClusterConfig {
        members = members == null ? List.of() : List.copyOf(members);
        revoked = revoked == null ? List.of() : List.copyOf(revoked);
    }

    /**
     * The head.
     *
     * @param id   its node id
     * @param host the address members connect to
     * @param port the cluster port
     * @param bind the address it listens on; null means {@code host}
     */
    @JsonPropertyOrder({"id", "host", "port", "bind"})
    public record Head(String id, String host, int port, String bind) {

        /** The address the head listens on. */
        public String bindAddress() {
            return bind == null ? host : bind;
        }
    }

    /**
     * A member.
     *
     * @param id          node id
     * @param fingerprint SHA-256 of its current certificate
     * @param joinedAt    when it joined (ISO-8601 instant)
     */
    @JsonPropertyOrder({"id", "fingerprint", "joined-at"})
    public record Member(String id, String fingerprint, String joinedAt) {
    }

    /**
     * A revoked certificate.
     *
     * @param id          node id it was issued for
     * @param fingerprint SHA-256 of the certificate
     * @param revokedAt   when (ISO-8601 instant)
     */
    @JsonPropertyOrder({"id", "fingerprint", "revoked-at"})
    public record Revoked(String id, String fingerprint, String revokedAt) {
    }

    /** The member with this id. */
    public Optional<Member> member(NodeId id) {
        return members.stream().filter(m -> m.id().equals(id.value())).findFirst();
    }

    /** Whether a certificate with this fingerprint was revoked. */
    public boolean isRevoked(String fingerprint) {
        return revoked.stream().anyMatch(r -> r.fingerprint().equals(fingerprint));
    }

    /** A copy with one more member (or the member replaced). */
    public ClusterConfig withMember(Member member) {
        List<Member> list = new ArrayList<>(members.stream().filter(m -> !m.id().equals(member.id())).toList());
        list.add(member);
        return new ClusterConfig(schemaVersion, head, list, revoked);
    }

    /** A copy without the member, its certificate on the revocation list. */
    public ClusterConfig withRevoked(Member member, Instant at) {
        List<Member> list = members.stream().filter(m -> !m.id().equals(member.id())).toList();
        // A certificate revoked longer ago than a certificate lives has expired by now: its entry
        // can go, so the list stays below its limit however many nodes are removed over time.
        Instant expired = at.minus(ClusterCa.NODE_VALIDITY);
        List<Revoked> gone = new ArrayList<>(revoked.stream()
                .filter(r -> !Instant.parse(r.revokedAt()).isBefore(expired)).toList());
        if (!isRevoked(member.fingerprint())) {
            gone.add(new Revoked(member.id(), member.fingerprint(), at.toString()));
        }
        return new ClusterConfig(schemaVersion, head, list, gone);
    }

    /**
     * Every problem of the file, empty if it is valid.
     *
     * @return the problems, as short English sentences with the field path
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (head == null) {
            problems.add("head is missing");
        } else {
            if (!validNodeId(head.id())) {
                problems.add("head.id is not a valid node id");
            }
            if (head.host() == null || !HOST.matcher(head.host()).matches()) {
                problems.add("head.host is not a host name or IP address");
            }
            if (head.port() < 1 || head.port() > 65535) {
                problems.add("head.port must be 1 to 65535");
            }
            if (head.bind() != null && !HOST.matcher(head.bind()).matches()) {
                problems.add("head.bind is not a host name or IP address");
            }
        }
        if (members.size() > MAX_MEMBERS) {
            problems.add("members: at most " + MAX_MEMBERS);
        }
        if (revoked.size() > MAX_REVOKED) {
            problems.add("revoked: at most " + MAX_REVOKED);
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < members.size(); i++) {
            Member member = members.get(i);
            if (member == null || !validNodeId(member.id())) {
                problems.add("members[" + i + "].id is not a valid node id");
                continue;
            }
            if (!ids.add(member.id())) {
                problems.add("members[" + i + "].id appears twice");
            }
            if (!NodeCertificates.isFingerprint(member.fingerprint())) {
                problems.add("members[" + i + "].fingerprint must be 64 lower-case hex digits");
            }
            if (!validInstant(member.joinedAt())) {
                problems.add("members[" + i + "].joined-at is not an ISO-8601 instant");
            }
        }
        for (int i = 0; i < revoked.size(); i++) {
            Revoked entry = revoked.get(i);
            if (entry == null || !validNodeId(entry.id())) {
                problems.add("revoked[" + i + "].id is not a valid node id");
                continue;
            }
            if (!NodeCertificates.isFingerprint(entry.fingerprint())) {
                problems.add("revoked[" + i + "].fingerprint must be 64 lower-case hex digits");
            }
            if (!validInstant(entry.revokedAt())) {
                problems.add("revoked[" + i + "].revoked-at is not an ISO-8601 instant");
            }
        }
        for (Member member : members) {
            if (member != null && member.fingerprint() != null && isRevoked(member.fingerprint())) {
                problems.add("member " + member.id() + " has a revoked certificate");
            }
        }
        return problems;
    }

    private static boolean validNodeId(String value) {
        try {
            new NodeId(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private static boolean validInstant(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException | NullPointerException e) {
            return false;
        }
    }
}
