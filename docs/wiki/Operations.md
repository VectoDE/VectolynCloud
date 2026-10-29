# Operations

[Deutsch](Operations-DE)

How to run VectoraCloud on your own machine. We do not host anything; every release only contains files you run yourself. The full page with every detail is [docs/cloud/operations.md](https://github.com/VectoDE/VectoraCloud/blob/main/docs/cloud/operations.md).

## Downloads

Each [release](https://github.com/VectoDE/VectoraCloud/releases) contains:

- `VectoraCloud.jar`
- `VectoraCloud-<version>-windows.zip` with `start.bat` and `start.ps1`
- `VectoraCloud-<version>-linux-macos.tar.gz` with `start.sh`
- `SHA256SUMS`, a cosign signature for every file and SLSA provenance

Nothing else: the plugin jars are not part of a release.

The container image is `ghcr.io/vectode/vectora-cloud:<version>`; `latest` points to the newest stable release only. Dockerfile, `compose.yml`, the systemd unit and the Pterodactyl egg are in [`cloud/dist/`](https://github.com/VectoDE/VectoraCloud/tree/main/cloud/dist).

## Where it runs

| Platform | How |
|---|---|
| Windows | unpack the zip, run `start.bat` in a normal (not elevated) console |
| Linux, VPS | `start.sh`, permanently with the systemd unit `vectora-cloud.service` under a user `vectora` |
| macOS | unpack the tar, run `./start.sh`; the managed database falls back to H2 |
| Docker | `docker run --init -v vectora:/data ghcr.io/vectode/vectora-cloud:<version>`, or `compose.yml` with MariaDB |
| Pterodactyl | import the egg, one allocation per port, raise the Wings PID limit of 512 |

You need Java 21 or later; an older Java gets a clear message instead of a crash. The cloud never runs as root or administrator unless you start it with `--allow-root`.

## First start

Without a terminal (systemd, Docker, Pterodactyl) the cloud needs a setup file: `--non-interactive --setup-file setup.yml`. The egg and `compose.yml` already pass it.

## Checking a download

```sh
sha256sum --check --ignore-missing SHA256SUMS
cosign verify-blob VectoraCloud.jar --bundle VectoraCloud.jar.sigstore.json \
  --certificate-identity "https://github.com/VectoDE/VectoraCloud/.github/workflows/release.yml@refs/tags/<tag>" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify VectoraCloud.jar --repo VectoDE/VectoraCloud
gh attestation verify oci://ghcr.io/vectode/vectora-cloud:<version> --repo VectoDE/VectoraCloud
```

This applies to every release after v0.1.0. v0.1.0 was signed before the repository was renamed from `VectoDE/Vectora-Network`; for it, use `VectoDE/Vectora-Network` in the certificate identity and in `--repo`. Details: [docs/cloud/operations.md](https://github.com/VectoDE/VectoraCloud/blob/main/docs/cloud/operations.md#verifying-a-release).
