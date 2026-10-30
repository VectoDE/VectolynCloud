# Roadmap

[English](Roadmap)

Vectolyn entsteht in acht Etappen. Jede Etappe bekommt einen eigenen Plan und endet lauffähig, getestet und gepusht. Aktueller Stand: [Project „Vectolyn Roadmap“](https://github.com/users/VectoDE/projects/11) und die [Meilensteine](https://github.com/VectoDE/VectolynCloud/milestones).

**Aktuelle Etappe: 2.** Etappe 1 ist abgeschlossen, Etappe 2 läuft, alles danach ist geplant.

## 1. Fundament ([#1](https://github.com/VectoDE/VectolynCloud/issues/1))

**Stand: abgeschlossen.** Erschienen als signiertes Pre-Release [v0.1.0](https://github.com/VectoDE/VectolynCloud/releases/tag/v0.1.0).

Server stoppen, Welten und Stand lokal sichern; Server-Ordner nach `Server-Network/` umziehen, getrennt in Template und Runtime (inzwischen abgelöst durch `templates/<Server>/` im Repository und das Cloud-Home außerhalb davon); Startskripte und ini-Pfade anpassen; `.gitignore`; Secrets auslagern und neu erzeugen; bekannte Fehler beheben (Floodgate auf Fabric, ViaBackwards/ViaRewind, DB-Pools, ini-Pfade); Gradle-Build für die vorhandenen Plugins; Grundausstattung des Repos; GitHub-Einrichtung; erster Push nach gitleaks-Prüfung.

## 2. VectolynCloud-Kern ([#11](https://github.com/VectoDE/VectolynCloud/issues/11))

**Stand: in Arbeit.**

Node mit Konsole, Einrichtungsassistent beim ersten Start wie bei CloudNet, Konfiguration über Dateien und Konsolenbefehle und eine gemeinsame Konfigurationsschicht, die das Dashboard in Etappe 8 weiterverwendet. Auswahl der gewollten Server und Minigame-Gruppen; nur gewollte entstehen aus ihren Templates. Gruppen und Tasks, Autoskalierung, Multi-Node über TLS, REST-/WebSocket-API, Plattform-Downloader mit Prüfsummen und Rollback, JRE-Verwaltung, Datenbank-Assistent, Secret-Erzeugung, eigener Messaging-Kanal, Backups und Monitoring, Docker-Image auf GHCR mit Dockerfile und compose-Datei, Pterodactyl-Egg, systemd-Unit, Startskripte für Windows, Linux und macOS. Recherche eines 1.8.8-Forks für Java 21. Geplante Schutzregel: VectolynCloud verweigert den Start als Administrator oder root, außer mit einem ausdrücklichen Schalter (etwa `--allow-root`). Die Meldung erklärt dann, wie man ohne Admin-Rechte startet.

## 3. Vectolyn-Plugin-Basis ([#12](https://github.com/VectoDE/VectolynCloud/issues/12))

Core, Rechte und Ränge, Messaging, Portale und End, Sync zwischen Servern, Versionsadapter für 1.8.8 bis 26.3. Eine Jar pro Plattform.

## 4. Lobby ([#13](https://github.com/VectoDE/VectolynCloud/issues/13))

Per Code generierte Hub-Lobby mit Navigator, NPCs und Portalen, Hologramm-Bestenlisten, Gadgets, Profilmenü, Schutz, Doppelsprung, Parkour.

## 5. Survival, Creative, Mod-Server ([#14](https://github.com/VectoDE/VectolynCloud/issues/14))

Survival mit Claims, Homes, Shops, Jobs und Economy; Creative mit Plots, Bauwerkzeug und Freebuild; Fabric-Mod-Server mit freigegebener Modliste; der gemeinsame End-Server.

## 6. Minigames und Anti-Cheat ([#15](https://github.com/VectoDE/VectolynCloud/issues/15))

BedWars, SkyWars, Parkour/JumpLeague, SurvivalGames, TNTRun/Spleef, BuildBattle, MurderMystery/TTT mit generierten Maps; eigener Anti-Cheat, der Bedrock-Bewegungen berücksichtigt.

## 7. Marke und Resourcepack ([#16](https://github.com/VectoDE/VectolynCloud/issues/16))

Neue Marke (Logo, Palette, Schrift, Tonalität), vor der Umsetzung freigegeben. Resourcepack in Schritten, mit einem Build-Tool für jedes Java-`pack_format` von 1.8 bis 26.3 und einem Bedrock-Pack.

## 8. Dashboard, Discord-Bot, Website, Store ([#17](https://github.com/VectoDE/VectolynCloud/issues/17))

Web-Dashboard, das alles kann, was die Konsole kann, inklusive Ersteinrichtung und Server-Auswahl; Discord-Bot; Website; rein kosmetischer Store mit Stripe, entwickelt im Testmodus. Alle drei liegen als Code in diesem Repository: Betreiber konfigurieren sie mit eigenem Bot-Token und eigenem Stripe-Konto und hosten sie selbst. Wir hosten nichts.

## Offene Fragen

- Welcher 1.8.8-Fork unter Java 21 läuft (Etappe 2).
- Modliste für den Mod-Server (Vorschlag und Freigabe).
- Markenentwurf (Freigabe).

Feedback: [Diskussion zur Roadmap](https://github.com/VectoDE/VectolynCloud/discussions/20).
