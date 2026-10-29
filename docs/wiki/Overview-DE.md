# Übersicht

[English](Overview)

Vectora ist ein Open-Source-Baukasten unter der GPL-3.0, mit dem du dein eigenes Minecraft-Netzwerk betreibst. Auf einem Vectora-Netzwerk kommen Java-Spieler von 1.8 bis 26.3 und Bedrock-Spieler über denselben Proxy herein und spielen zusammen: Lobby, Survival, Creative, ein Fabric-Mod-Server, ein gemeinsamer End-Server und Minigames. Alles, was das Netzwerk braucht, liegt in diesem Repository.

Wir betreiben kein Netzwerk, keine Server und keine Domain und hosten weder Website noch Discord-Bot noch Store. Website, Discord-Bot und Store entstehen als Code in diesem Repository. Als Betreiber konfigurierst du sie mit deinem eigenen Bot-Token und deinem eigenen Stripe-Konto und hostest sie selbst.

## Stand

Das Projekt steht bei **Etappe 1 von 8** (Fundament). Die vorhandenen Server ziehen ins Repository um, Secrets verlassen den Quellbaum, und der Gradle-Build entsteht. Ein Release gibt es noch nicht. Siehe [Roadmap](Roadmap-DE).

## Ziel

Du startest `java -jar VectoraCloud.jar` unter Windows, Linux, macOS, in Docker oder auf Pterodactyl. Ein Einrichtungsassistent stellt ein paar Fragen, unter anderem, welche Server und Minigame-Gruppen du willst. Danach läuft das Netzwerk mit Datenbank, Proxy, Lobby, Spielmodi und Minigames ohne weitere Handarbeit. Ein Release wird `VectoraCloud.jar`, Startskripte für Windows, Linux und macOS und ein Docker-Image auf GHCR enthalten; Dockerfile, compose-Datei und Pterodactyl-Egg liegen im Repository. Das ist das Ziel ab Etappe 2, nicht der heutige Stand.

## Grundsätze

- **Bedrock ist vollwertig.** Kein Spielmodus, den Bedrock-Spieler nicht betreten können. Menüs sind Inventar-Menüs, die auf beiden Editionen funktionieren.
- **Wenig Fremd-Plugins.** Nur das Nötigste (Server-Software, Crossplay- und Versionsbrücken, ein paar Performance-Mods, MariaDB). Rechte, Tablist, Bauwerkzeug, Regionsschutz, Platzhalter, Portale und Pregen entstehen in diesem Repository.
- **Keine Secrets in Git.** Forwarding-Secrets, Schlüssel, Passwörter und Tokens werden lokal erzeugt und bleiben außerhalb des Repositorys.
- **Zweisprachig.** Ingame-Texte folgen der Client-Sprache (Deutsch oder Englisch). README, Wiki und Discussions gibt es in beiden Sprachen, den Website-Code ebenfalls.
- **Bytecode für Java 21.** Code, der Java 25 braucht, ist gekennzeichnet und hat immer einen Java-21-Weg. Die heutigen Server laufen auf Java 25, weil Paper 26.3 es verlangt.
- **Fairer Store.** Der geplante Store verkauft nur Kosmetik.

Weiter: [Architektur](Architecture-DE) · [Server-Netzwerk](Server-Network-DE)
