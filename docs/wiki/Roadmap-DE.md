# Roadmap

[English](Roadmap)

Vectora entsteht in acht Etappen. Jede Etappe bekommt einen eigenen Plan und endet lauffähig, getestet und gepusht. Aktueller Stand: [Project „Vectora Roadmap“](https://github.com/users/VectoDE/projects/11) und die [Meilensteine](https://github.com/VectoDE/Vectora-Network/milestones).

**Aktuelle Etappe: 1.** Alles ab Etappe 2 ist geplant.

## 1. Fundament ([#1](https://github.com/VectoDE/Vectora-Network/issues/1))

Server stoppen, Welten und Stand lokal sichern; Server-Ordner nach `Server-Network/` umziehen, getrennt in Template und Runtime; Startskripte und ini-Pfade anpassen; `.gitignore`; Secrets auslagern und neu erzeugen; bekannte Fehler beheben (Floodgate auf Fabric, ViaBackwards/ViaRewind, DB-Pools, ini-Pfade); Gradle-Build für die vorhandenen Plugins; Grundausstattung des Repos; GitHub-Einrichtung; erster Push nach gitleaks-Prüfung.

## 2. VectoraCloud-Kern ([#11](https://github.com/VectoDE/Vectora-Network/issues/11))

Node mit Konsole, Einrichtungsassistent beim ersten Start wie bei CloudNet, Konfiguration über Dateien und Konsolenbefehle und eine gemeinsame Konfigurationsschicht, die das Dashboard in Etappe 8 weiterverwendet. Auswahl der gewollten Server und Minigame-Gruppen; nur gewollte entstehen aus ihren Templates. Gruppen und Tasks, Autoskalierung, Multi-Node über TLS, REST-/WebSocket-API, Plattform-Downloader mit Prüfsummen und Rollback, JRE-Verwaltung, Datenbank-Assistent, Secret-Erzeugung, eigener Messaging-Kanal, Backups und Monitoring, Docker-Image, Pterodactyl-Egg, systemd-Unit. Recherche eines 1.8.8-Forks für Java 21. Geplante Schutzregel: VectoraCloud verweigert den Start als Administrator oder root, außer mit einem ausdrücklichen Schalter (etwa `--allow-root`). Die Meldung erklärt dann, wie man ohne Admin-Rechte startet.

## 3. Vectora-Plugin-Basis ([#12](https://github.com/VectoDE/Vectora-Network/issues/12))

Core, Rechte und Ränge, Messaging, Portale und End, Sync zwischen Servern, Versionsadapter für 1.8.8 bis 26.3. Eine Jar pro Plattform.

## 4. Lobby ([#13](https://github.com/VectoDE/Vectora-Network/issues/13))

Per Code generierte Hub-Lobby mit Navigator, NPCs und Portalen, Hologramm-Bestenlisten, Gadgets, Profilmenü, Schutz, Doppelsprung, Parkour.

## 5. Survival, Creative, Mod-Server ([#14](https://github.com/VectoDE/Vectora-Network/issues/14))

Survival mit Claims, Homes, Shops, Jobs und Economy; Creative mit Plots, Bauwerkzeug und Freebuild; Fabric-Mod-Server mit freigegebener Modliste; der gemeinsame End-Server.

## 6. Minigames und Anti-Cheat ([#15](https://github.com/VectoDE/Vectora-Network/issues/15))

BedWars, SkyWars, Parkour/JumpLeague, SurvivalGames, TNTRun/Spleef, BuildBattle, MurderMystery/TTT mit generierten Maps; eigener Anti-Cheat, der Bedrock-Bewegungen berücksichtigt.

## 7. Marke und Resourcepack ([#16](https://github.com/VectoDE/Vectora-Network/issues/16))

Neue Marke (Logo, Palette, Schrift, Tonalität), vor der Umsetzung freigegeben. Resourcepack in Schritten, mit einem Build-Tool für jedes Java-`pack_format` von 1.8 bis 26.3 und einem Bedrock-Pack.

## 8. Dashboard, Discord-Bot, Website, Store ([#17](https://github.com/VectoDE/Vectora-Network/issues/17))

Web-Dashboard, das alles kann, was die Konsole kann, inklusive Ersteinrichtung und Server-Auswahl; Discord-Bot; öffentliche Website auf Cloudflare; rein kosmetischer Store, Stripe im Testmodus, bis das Rechtliche geprüft ist.

## Offene Fragen

- Welcher 1.8.8-Fork unter Java 21 läuft (Etappe 2).
- Modliste für den Mod-Server (Vorschlag und Freigabe).
- Markenentwurf (Freigabe).

Feedback: [Diskussion zur Roadmap](https://github.com/VectoDE/Vectora-Network/discussions/20).
