# Betrieb

[English](Operations)

So betreibst du VectolynCloud auf deinem eigenen Rechner. Wir hosten nichts, jedes Release enthält nur Dateien, die du selbst startest. Die vollständige Seite mit allen Details ist [docs/cloud/operations.de.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/operations.de.md).

## Downloads

Jedes [Release](https://github.com/VectoDE/VectolynCloud/releases) enthält:

- `VectolynCloud.jar`
- `VectolynCloud-<version>-windows.zip` mit `start.bat` und `start.ps1`
- `VectolynCloud-<version>-linux-macos.tar.gz` mit `start.sh`
- `SHA256SUMS`, eine cosign-Signatur für jede Datei und SLSA-Provenance

Mehr nicht: Die Plugin-Jars gehören nicht zum Release.

Das Container-Image heißt `ghcr.io/vectode/vectolyn-cloud:<version>`. `latest` zeigt nur auf das neueste stabile Release. Dockerfile, `compose.yml`, die systemd-Unit und das Pterodactyl-Egg liegen in [`cloud/dist/`](https://github.com/VectoDE/VectolynCloud/tree/main/cloud/dist).

## Wo es läuft

| Plattform | So geht's |
|---|---|
| Windows | zip entpacken, `start.bat` in einer normalen Konsole starten (nicht als Administrator) |
| Linux, VPS | `start.sh`, dauerhaft mit der systemd-Unit `vectolyn-cloud.service` unter einem Benutzer `vectolyn` |
| macOS | tar entpacken, `./start.sh` starten; die verwaltete Datenbank fällt auf H2 zurück |
| Docker | `docker run --init -v vectolyn:/data ghcr.io/vectode/vectolyn-cloud:<version>` oder `compose.yml` mit MariaDB |
| Pterodactyl | Egg importieren, eine Allocation pro Port, das PID-Limit von 512 in Wings erhöhen |

Gib der Cloud einen eigenen Ordner als Cloud-Home, außerhalb jedes Checkouts des Repositorys. Der Ordner `run/`, den `./gradlew :cloud:node:runLocal` nutzt, ist nur zum Entwickeln da.

Du brauchst Java 21 oder neuer. Ein älteres Java bekommt eine klare Meldung statt eines Absturzes. Als root oder Administrator läuft die Cloud nur, wenn du sie mit `--allow-root` startest.

## Erster Start

Ohne Terminal (systemd, Docker, Pterodactyl) braucht die Cloud eine Setup-Datei: `--non-interactive --setup-file setup.yml`. Egg und `compose.yml` geben sie bereits mit.

## Wo die Dateien liegen

Alles liegt unter dem Cloud-Home: `config/` (Konfiguration), `local/` (Vorlagen und Welten der statischen Dienste), `database/`, `storage/` (Downloads und Java-Runtimes), `secrets/`, `backups/`, `logs/` und `state/`. Sichere `config/`, `local/` und `secrets/`; `storage/`, `temp/` und `logs/` entstehen von selbst neu. Ein Home aus einer älteren Version wird beim ersten Start einer neuen Version in diesen Aufbau umgezogen, nach einer Kopie der Konfiguration in `backups/`. Beende vorher die alte Cloud samt Servern. Details: [Update](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/operations.md#what-is-where-and-upgrading-from-an-older-home) (Englisch).

## Mehrere Nodes

Ein Cluster besteht aus einem Head und seinen Mitgliedern; Ablauf, Vertrauensmodell und Befehle stehen in [cluster.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/cluster.md) (Englisch). Für den Betrieb wichtig:

- **Datenbank:** Jeder Node braucht einen MariaDB-Server, den alle Nodes erreichen: `database.mode: external` mit einer Adresse im Cluster-Netz. Mit `h2` lehnt der Head Tokens und Beitritte ab. `managed` und `docker` binden MariaDB an 127.0.0.1, damit können nur Nodes auf derselben Maschine beitreten; die Bindung ans Cluster-Netz kommt mit dem nächsten Schritt des Clusters.
- **Gleiche Version:** Jeder Node läuft mit der VectolynCloud-Version des Heads. Ein Node mit anderer Version tritt nicht bei bzw. bekommt keine Sitzung, bis er aktualisiert ist.
- **Firewall:** Den Cluster-Port des Heads (standardmäßig TCP 7011) nur für die anderen Nodes öffnen. In Docker auch veröffentlichen (`-p 7011:7011`) und `cluster init` die Adresse des Hosts geben. Die Ports für Proxys und Backends über Nodes hinweg kommen mit dem nächsten Schritt des Clusters.
- **Backup:** `secrets/cluster/` des Heads enthält die Cluster-CA und gehört zum Backup von `secrets/`. Ohne sie kann kein Node mehr beitreten.
- **Node entfernen:** `cluster remove <node-id>` auf dem Head widerruft sein Zertifikat sofort. Ein neuer Beitritt braucht ein neues Token.
- **Pterodactyl:** als Cluster nicht getestet; der Cluster-Port bräuchte eine eigene Allocation.

## Einen Download prüfen

```sh
sha256sum --check --ignore-missing SHA256SUMS
cosign verify-blob VectolynCloud.jar --bundle VectolynCloud.jar.sigstore.json \
  --certificate-identity "https://github.com/VectoDE/VectolynCloud/.github/workflows/release.yml@refs/tags/<tag>" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify VectolynCloud.jar --repo VectoDE/VectolynCloud
gh attestation verify oci://ghcr.io/vectode/vectolyn-cloud:<version> --repo VectoDE/VectolynCloud
```

Das gilt für alle Releases nach v0.1.0. v0.1.0 gegen den Owner prüfen (`gh attestation verify ... --owner VectoDE`). Details: [docs/cloud/operations.de.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/operations.de.md#ein-release-prüfen).
