# Betrieb

[English](Operations)

So betreibst du VectoraCloud auf deinem eigenen Rechner. Wir hosten nichts, jedes Release enthält nur Dateien, die du selbst startest. Die vollständige Seite mit allen Details ist [docs/cloud/operations.de.md](https://github.com/VectoDE/Vectora-Network/blob/main/docs/cloud/operations.de.md).

## Downloads

Jedes [Release](https://github.com/VectoDE/Vectora-Network/releases) enthält:

- `VectoraCloud.jar`
- `VectoraCloud-<version>-windows.zip` mit `start.bat` und `start.ps1`
- `VectoraCloud-<version>-linux-macos.tar.gz` mit `start.sh`
- `SHA256SUMS`, eine cosign-Signatur für jede Datei und SLSA-Provenance

Das Container-Image heißt `ghcr.io/vectode/vectora-cloud:<version>`. `latest` zeigt nur auf das neueste stabile Release. Dockerfile, `compose.yml`, die systemd-Unit und das Pterodactyl-Egg liegen in [`VectoraCloud/dist/`](https://github.com/VectoDE/Vectora-Network/tree/main/VectoraCloud/dist).

## Wo es läuft

| Plattform | So geht's |
|---|---|
| Windows | zip entpacken, `start.bat` in einer normalen Konsole starten (nicht als Administrator) |
| Linux, VPS | `start.sh`, dauerhaft mit der systemd-Unit `vectora-cloud.service` unter einem Benutzer `vectora` |
| macOS | tar entpacken, `./start.sh` starten; die verwaltete Datenbank fällt auf H2 zurück |
| Docker | `docker run --init -v vectora:/data ghcr.io/vectode/vectora-cloud:<version>` oder `compose.yml` mit MariaDB |
| Pterodactyl | Egg importieren, eine Allocation pro Port, das PID-Limit von 512 in Wings erhöhen |

Du brauchst Java 21 oder neuer. Ein älteres Java bekommt eine klare Meldung statt eines Absturzes. Als root oder Administrator läuft die Cloud nur, wenn du sie mit `--allow-root` startest.

## Erster Start

Ohne Terminal (systemd, Docker, Pterodactyl) braucht die Cloud eine Setup-Datei: `--non-interactive --setup-file setup.yml`. Egg und `compose.yml` geben sie bereits mit.

## Einen Download prüfen

```sh
sha256sum --check --ignore-missing SHA256SUMS
cosign verify-blob VectoraCloud.jar --bundle VectoraCloud.jar.sigstore.json \
  --certificate-identity "https://github.com/VectoDE/Vectora-Network/.github/workflows/release.yml@refs/tags/<tag>" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify VectoraCloud.jar --repo VectoDE/Vectora-Network
gh attestation verify oci://ghcr.io/vectode/vectora-cloud:<version> --repo VectoDE/Vectora-Network
```
