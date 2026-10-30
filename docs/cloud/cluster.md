# Cluster: several nodes

Module `cloud/cluster`, package `net.vectolyn.cloud.cluster`. A cluster is one **head** and any number of **members**. The head runs `cluster init`, owns the cluster CA, the member list and (later) the configuration and placement. Members join with a one-time token and keep a mutual TLS 1.3 connection to the head on a dedicated cluster port.

State of [#73](https://github.com/VectoDE/VectolynCloud/issues/73), built in steps:

| Step | Content | State |
|---|---|---|
| 1 | CA, join tokens, join, mutual TLS cluster port, revocation, version and database checks | this page |
| 2 | config mirror (members read `config/` of the head), placement of tasks on nodes, template sync, services and players across nodes | next |
| 3 | manual promotion of a member to head (failover) | later |
| 4 | moving static services between nodes with their world data | later |

Until step 2 a member runs its own configuration and services as before; the cluster only connects the nodes and decides who belongs.

## Quick start

On the head (a node whose database is a MariaDB server the other nodes reach, see [Database](#database)):

```
cluster init 10.0.0.1                 # host the members reach; port 7011 by default
cluster token create node-2           # valid 1 hour by default, once
```

`token create` prints one line, shown only this once:

```
cluster join 10.0.0.1:7011 node-1 k3x9qa.<32 characters> sha256:<64 hex digits>
```

On the new node (its `cloud.yml` has `node-id: node-2`, same VectolynCloud version as the head), paste that line into the console. `cluster status` shows the role, the head, the CA pin, the members and whether they are connected.

| Command | Where | What it does |
|---|---|---|
| `cluster init <host> [port] [bind]` | future head | creates the CA and the head's certificate, writes `config/cluster.yml`, opens the cluster port (default 7011). `bind` is the address to listen on, default `host`. |
| `cluster token create <node-id> [lifetime]` | head | a join token for exactly this node id, 1m to 24h (default 1h) |
| `cluster token list` / `revoke <id>` | head | open tokens (id, node, expiry, never the secret); delete one |
| `cluster join <host:port> <head-id> <token> <ca-pin>` | new node | joins, see [Join](#join) |
| `cluster status` (`nodes`, `info`) | any | role, head, version, CA pin, certificate expiry, members and their connection, link state |
| `cluster remove <node-id>` | head | revokes the member's certificate and ends its session at once |

The whole `cluster` command is sensitive: none of its lines reaches the console history. `cluster token create` still prints the join line on the console once; when the node runs under Docker or systemd that output also lands in `docker logs` or the journal. Treat those logs like `secrets/` for the token's lifetime, or give the token a short one (`cluster token create <node-id> 10m`).

## Trust model

| Decision | Rule | Where |
|---|---|---|
| Who signs node certificates | The cluster CA on the head: EC P-256, self-signed, `CA:true, pathlen:0`, 10 years, made with the JDK's `keytool` in `secrets/cluster/ca.p12` (owner only, random password in `secrets/cluster/ca.password`, passed to keytool through an environment variable). No library besides the JDK. | `ClusterCa` |
| What a node certificate says | Exactly one subject alternative name, the URI `vectolyn-node:<node-id>`. The CA writes subject and name itself from the **token's** node id; keytool does not copy extensions out of the request, and every issued certificate is parsed and checked again (signed by the CA, no CA itself, exactly that name, EC key of at least 256 bits). EKU `serverAuth, clientAuth`, valid 825 days. | `ClusterCa.issue`, `NodeCertificates` |
| Where the node key lives | Made on the node itself (keytool, EC P-256) in `secrets/cluster/node.p12`. It never leaves the node: the node sends a PKCS#10 request, keytool on the head checks its signature. | `NodeIdentity` |
| How a joining node trusts the head | Before it sends the token, the TLS chain of the head must end in a self-signed CA whose public key has the pin of the join line (`sha256:` over the DER `SubjectPublicKeyInfo`, as in `kubeadm join --discovery-token-ca-cert-hash`), the head's certificate must chain to it by PKIX and be the node certificate of the head id in the join line. Every member holds a certificate of the same CA, so without the head id a member that sits in the path could play the head and take the token. A wrong pin or head id ends the connection in the handshake: the token never leaves the node. The answer must name the node id of the head's TLS certificate, carry the pinned CA and a certificate for this node's key and node id. | `ClusterTrust.pinned`, `ClusterService.join` |
| Join token | `<id>.<secret>`, 6 and 32 characters of `[a-z0-9]` (165 bits). For one node id, expires (1m to 24h). The head keeps only id, node, expiry and SHA-256 of the secret (`secrets/cluster/join-tokens.yml`, owner only). The first request with the right secret uses it up, also for the wrong node id; three wrong secrets use it up too. Every token problem gets the same answer (`INVALID_TOKEN`), and an unknown id costs the same hash comparison as a known one. Whether an id exists can still show in the answer time (a known id is written back to the file); against 165 random bits of secret that does not help. | `JoinTokens` |
| Transport | TLS 1.3 only, on both ends (`SSLContext` and `SSLParameters` of the JDK); TLS 1.2 or older ends in the handshake. Endpoint identification is on, but by node id instead of host name: JSSE leaves it to the cluster's trust manager, which checks the node id in the peer's certificate, signed by a CA that exists only in this cluster. A node certificate carries no host name. | `ClusterTls` |
| Who gets a session | A connection with a client certificate from the cluster CA (PKIX, validity, EKU checked by the JDK) whose first frame is a hello naming the node id of that certificate (`NODE_MISMATCH` otherwise), whose fingerprint is not revoked (`REVOKED`), that is a member of `cluster.yml` with exactly this certificate's fingerprint (`UNKNOWN_NODE`; also for the head's own id) that runs the head's version (`VERSION_MISMATCH`) and whose head is not back on a single-node database (`DATABASE_SINGLE_NODE`, the same rule as for a join, see [Database](#database)). Refused hellos are logged as warnings for the first ten and every 1000th, so a node that reconnects in a loop cannot fill the log. The check uses the certificate of the TLS session, so a resumed session is checked the same way. Right after a session is registered, membership is checked once more, so a removal that ran between the hello and the registration still ends it. | `ClusterServer`, `ClusterService.hello` |
| A connection without a certificate | May send exactly one join request and is closed after the answer. A hello without certificate gets `Disconnect(AUTH_FAILED)`, a join with certificate or any other frame `PROTOCOL_ERROR`. | `ClusterServer` |
| Who a member talks to | Only the head named in its `cluster.yml`: the TLS certificate of the peer must chain to the cluster CA, be a node certificate of the head's node id and not be on the member's revocation list. That list is empty on a member today: the head's certificate cannot be revoked toward the members yet; replacing it comes with promotion (step 3). | `ClusterService.startLink` |
| Revocation | `cluster remove` moves the member's certificate fingerprint from the member list to `revoked` in `cluster.yml` and ends its session at once (`Disconnect(AUTH_FAILED)`). The member's next hello gets `REVOKED`. The list survives restarts. A removed node can come back only with a new token, and gets a new certificate. | `ClusterService.remove` |
| One version | A node with another VectolynCloud version than the head does not join (the token stays valid) and gets no session (`VERSION_MISMATCH` with the head's version) until it is updated. The update system ([#74](https://github.com/VectoDE/VectolynCloud/issues/74)) plugs in at `VersionPolicy` and `ClusterService.Events.versionMismatch(own, head)`. The version is the one the node reports: the rule keeps honest nodes in step and is no security boundary (a node with a member certificate is trusted anyway). Builds outside a release jar report `dev`, so two such builds count as the same version. | `VersionPolicy` |

### Limits on the cluster port

| What | Limit |
|---|---|
| Connections | 128 in total, 16 per address; before authentication 64 in total, 8 per address. A connection leaves that pool only once its hello is accepted and the member admitted: a removed node keeps its old certificate, and that alone must not let it fill the larger pool. An address is an IPv4 address or an IPv6 /64, the same keys as the [service channel](network.md#security-model). |
| Handshake | TLS plus the first request within 10 s from the accept, no matter how slowly the peer sends; then the socket is cut (on a thread of its own, without waiting for the peer). A join then has 60 s for its answer (keytool gets 30 s per run); the joining node waits 90 s. A join whose connection was cut in the meantime is not saved: the head adds no member that never got its certificate. |
| Frames | 4 KiB until the hello is accepted, then 1 MiB. |
| Idle | 30 s without a frame ends a session; both sides ping every 10 s. More than 32 pings from a member per 10 s end its session. A send that cannot get to the connection within a second, because a write is stuck on a peer that does not read, ends that session; closing waits at most a second for that write (`SO_LINGER`), then cuts the socket. |
| Members | 64 per cluster; 32 open join tokens. |

## Join

`cluster join` on the new node:

1. checks that the node is in no cluster and has no CA of its own;
2. makes its key and a certification request with keytool;
3. connects to the head with TLS 1.3 and checks the head's CA against the pin and its certificate against the head id;
4. sends node id, token, its version and the request;
5. installs the certificate in front of its key, writes `config/cluster.yml` (head id, host, port) and connects.

The head answers, in this order: `VERSION_MISMATCH` and the database refusals without touching the token, then `INVALID_TOKEN`, `NODE_EXISTS`, `BAD_REQUEST` (the request did not check out), `ACCEPTED`. A join that fails on the new node removes its key again. If the answer to an accepted join is lost on the way, the member exists on the head but not on the node: `cluster remove <node-id>` on the head, a new token, join again.

## Database

Multi-node needs MariaDB that every node reaches over the cluster network. The head refuses `cluster token create` and every join while `database.mode` is `h2`: H2 is embedded and serves one node. The same rule holds for sessions: if the head is switched back to `h2` (or to a database on loopback), its members get `DATABASE_SINGLE_NODE` at their next hello and stay out until it is changed again. A join from another machine is refused while the head's database listens on loopback only: `managed` and `docker` always bind MariaDB to 127.0.0.1 today, and an `external` server with `database.host` on a loopback or wildcard address (`127.x.x.x` in any form, `::1`, `0.0.0.0`, `localhost`, `*.localhost`) is out of reach as well. This is a check of the obvious mistakes, not proof that the new node reaches the database: names are not resolved, and a join that arrives over loopback (a tunnel, for example) is not checked. So for nodes on several machines use `external` with a MariaDB server on an address of the cluster network. Binding the head's own managed MariaDB to the cluster network follows with step 2. Use TLS for the database connection where the server offers it ([database.md](database.md)).

## Firewall

Open the cluster port (TCP 7011 by default) on the head **for the other nodes only**. The port does not trust the network: every peer is checked by certificate, and without one only a join request with a valid token changes anything. But anyone who reaches the port learns the head's node id and version from a refused join, and a flood of connections that never authenticate fills the 64 slots before authentication, so members cannot reconnect until it stops. The firewall is what keeps both out. From step 2 on the proxies reach backends on other nodes: then the service ports of each node (`service-ports` in `nodes.yml`) have to be open for the proxy nodes, and the database port for every node; that part is described with step 2.

## Files

```
config/cluster.yml                head, members (with certificate fingerprint), revoked certificates
secrets/cluster/                  owner only
  ca.p12, ca.password             cluster CA (head only)
  node.p12, node.password         this node's key and certificate
  join-tokens.yml                 open join tokens, SHA-256 of the secret only (head only)
  work/                           certification requests while keytool signs them, emptied right after
```

`config/cluster.yml` is written by the `cluster` commands; a hand edit takes effect at the next start. A file that cannot be read does not stop the node: it runs outside the cluster, the error is logged, and the `cluster` commands report it until the file is repaired.

```yaml
schema-version: 1
head:
  id: node-1
  host: 10.0.0.1
  port: 7011
  # bind: 0.0.0.0              optional, default host
members:
  - id: node-1
    fingerprint: 5d0c...       # SHA-256 of the certificate, 64 hex digits
    joined-at: 2026-10-30T12:00:00Z
  - id: node-2
    fingerprint: a91e...
    joined-at: 2026-10-30T12:05:00Z
revoked:
  - id: node-3
    fingerprint: 77b2...
    revoked-at: 2026-10-30T13:00:00Z
```

Back up `secrets/cluster/` of the head together with the rest of `secrets/`: without `ca.p12` no node can join any more. The keystore passwords lie next to the keystores; what protects the keys is that `secrets/cluster/` is readable by the cloud's user only. keytool gets the passwords in its environment, without the cloud's own JVM options (`_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`, `CLASSPATH`) and without input. A revoked entry is dropped once its certificate would have expired anyway (825 days after the removal). Certificates are valid 825 days; the node warns from 30 days before the end. Renewal and promoting a member to head follow with steps 2 and 3.

## Wire format

The cluster port carries the frames of the [cloud channel](protocol.md) over TLS. The handshake is a `Request` on the channel `cluster.join` or `cluster.hello` and its `Response`; the session uses `Ping`, `Pong` and `Disconnect`. Payloads (`ClusterMessages`):

| Payload | Fields |
|---|---|
| `JoinRequest` | node id (string ≤ 32), token id (string 6), secret (string 32), version (string ≤ 64, `[0-9A-Za-z.+_-]`), certification request (bytes ≤ 4096) |
| `JoinReply` | outcome (VarInt: 0 `ACCEPTED`, 1 `INVALID_TOKEN`, 2 `VERSION_MISMATCH`, 3 `DATABASE_SINGLE_NODE`, 4 `DATABASE_UNREACHABLE`, 5 `NODE_EXISTS`, 6 `BAD_REQUEST`, 7 `FAILED`), head version, head node id, certificate (bytes ≤ 4096, only with `ACCEPTED`), CA certificate (same) |
| `NodeHello` | node id, version |
| `NodeWelcome` | outcome (0 `ACCEPTED`, 1 `VERSION_MISMATCH`, 2 `UNKNOWN_NODE`, 3 `NODE_MISMATCH`, 4 `REVOKED`, 5 `NOT_HEAD`), head node id, head version |

Every reader throws only `ProtocolException` and checks each length before it allocates; `JoinRequest.toString()` does not show the secret.

## Tests

| Test | What it covers |
|---|---|
| `ClusterJoinTest` | Two or three homes on loopback with a real CA: join and session; wrong CA pin and wrong head id (token never sent, key removed); used token; expired token; token for another node id (used up); wrong secret (kept); other version at join (token kept, join after the update) and at the hello (refused, update hook called); H2 (join and token refused); removed member (session ends, `REVOKED`, also after a restart of the head); reconnect after a restart of head and member; a head cannot join another cluster; no secret in any file of either home. |
| `ClusterPortTest` | Hand-made peers: TLS 1.2 refused, session on TLS 1.3, hello with another node id than the certificate, hello without certificate, join with certificate, certificate of another CA refused in the handshake, a member refuses a peer that is not its head, a joining node refuses a member that plays the head, a member that floods pings loses its session, removing a member that does not read does not wait, a silent TCP connection closed after the handshake timeout, garbage. |
| `ClusterCaTest` | keytool CA and node certificates: exactly the token's node id, names and CA rights asked for in the request ignored, tampered or oversized request refused, certificate not installed over another key, database rules for joins. |
| `JoinTokensTest` | One use, expiry, the right secret for the wrong node, three wrong secrets, unknown id, no secret in the file, lifetime and count limits. |
| `ClusterMessagesTest`, `ClusterConfigTest` | Round trips, limits, the redacted secret, `cluster.yml` validation (also a member with a revoked certificate), expired revocations leave the list, local database hosts, pins. |
| `ClusterMessagesFuzzTest` | Jazzer: arbitrary bytes as every payload (decoded values encode back to the same bytes) and through the frame reader. |
