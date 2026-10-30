# Operations

[Deutsch](Operations-DE)

How to run VectolynCloud on your own machine. We do not host anything; every release only contains files you run yourself. The full page with every detail is [docs/cloud/operations.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/operations.md).

## Downloads

Each [release](https://github.com/VectoDE/VectolynCloud/releases) contains:

- `VectolynCloud.jar`
- `VectolynCloud-<version>-windows.zip` with `start.bat` and `start.ps1`
- `VectolynCloud-<version>-linux-macos.tar.gz` with `start.sh`
- `VectolynCloud-<version>-banner.png` and `VectolynCloud-<version>-banner-2x.png`, the banner at the top of the release notes (1280 x 320 and 2560 x 640)
- `SHA256SUMS`, a cosign signature for every file and SLSA provenance

Nothing else: the plugin jars are not part of a release.

The container image is `ghcr.io/vectode/vectolyn-cloud:<version>`; `latest` points to the newest stable release only. Dockerfile, `compose.yml`, the systemd unit and the Pterodactyl egg are in [`cloud/dist/`](https://github.com/VectoDE/VectolynCloud/tree/main/cloud/dist).

## Where it runs

| Platform | How |
|---|---|
| Windows | unpack the zip, run `start.bat` in a normal (not elevated) console |
| Linux, VPS | `start.sh`, permanently with the systemd unit `vectolyn-cloud.service` under a user `vectolyn` |
| macOS | unpack the tar, run `./start.sh`; the managed database falls back to H2 |
| Docker | `docker run --init -v vectolyn:/data ghcr.io/vectode/vectolyn-cloud:<version>`, or `compose.yml` with MariaDB |
| Pterodactyl | import the egg, one allocation per port, raise the Wings PID limit of 512 |

Give the cloud its own folder as cloud home, outside any checkout of the repository. The `run/` folder that `./gradlew :cloud:node:runLocal` uses is for development only.

You need Java 21 or later; an older Java gets a clear message instead of a crash. The cloud never runs as root or administrator unless you start it with `--allow-root`.

## First start

Without a terminal (systemd, Docker, Pterodactyl) the cloud needs a setup file: `--non-interactive --setup-file setup.yml`. The egg and `compose.yml` already pass it.

## Where the files are

Everything is below the cloud home: `config/` (configuration), `local/` (templates and the worlds of the static services), `database/`, `storage/` (downloads and Java runtimes), `secrets/`, `backups/`, `logs/` and `state/`. Back up `config/`, `local/` and `secrets/`; `storage/`, `temp/` and `logs/` come back on their own. A home from an older version is moved to this layout at the first start of a new version, after a copy of the configuration into `backups/`. Stop the old cloud and its servers first. Details: [upgrading](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/operations.md#what-is-where-and-upgrading-from-an-older-home).

## Several nodes

A cluster is one head and its members; the details, the trust model and the commands are in [cluster.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/cluster.md). What operations has to know:

- **Database:** every node needs a MariaDB server that all nodes reach: `database.mode: external` with an address of the cluster network. With `h2` the head refuses tokens and joins. `managed` and `docker` bind MariaDB to 127.0.0.1, so with them only nodes on the same machine can join; their binding to the cluster network follows with the next step of the cluster.
- **Same version:** every node runs the head's VectolynCloud version. A node on another version does not join, or gets no session, until it is updated.
- **Firewall:** the head's cluster port (TCP 7011 by default) open for the other nodes only. In Docker publish it as well (`-p 7011:7011`) and give `cluster init` the host's address. The ports for proxies and backends across nodes come with the next step of the cluster.
- **Backup:** `secrets/cluster/` of the head holds the cluster CA; it is part of the backup of `secrets/`. Without it no node can join any more.
- **Removing a node:** `cluster remove <node-id>` on the head revokes its certificate at once. Joining again needs a new token.
- **Pterodactyl:** not tested as a cluster; the cluster port would need an allocation of its own.

## Checking a download

```sh
sha256sum --check --ignore-missing SHA256SUMS
cosign verify-blob VectolynCloud.jar --bundle VectolynCloud.jar.sigstore.json \
  --certificate-identity "https://github.com/VectoDE/VectolynCloud/.github/workflows/release.yml@refs/tags/<tag>" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify VectolynCloud.jar --repo VectoDE/VectolynCloud
gh attestation verify oci://ghcr.io/vectode/vectolyn-cloud:<version> --repo VectoDE/VectolynCloud
```

Details: [docs/cloud/operations.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/operations.md#verifying-a-release).
