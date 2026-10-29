# FAQ

[English](FAQ)

**Gibt es einen offiziellen Vectora-Server?**
Nein, und es wird auch keinen geben. Vectora ist ein Baukasten: Wir veröffentlichen Code und Releases, jeder Betreiber betreibt sein eigenes Netzwerk. Das Projekt steht bei Etappe 2 von 8 (siehe [Roadmap](Roadmap-DE)); Releases kündigen wir unter [Discussions → Announcements](https://github.com/VectoDE/Vectora-Network/discussions/categories/announcements) an.

**Welche Minecraft-Versionen können spielen?**
Ziel sind Java 1.8 bis 26.3 und Bedrock. Was heute schon geht, steht unter [Crossplay](Crossplay-DE).

**Bekommen Bedrock-Spieler das volle Spiel?**
Das ist eine Projektregel: Kein Spielmodus erscheint, den Bedrock-Spieler nicht betreten können. Menüs sind Inventar-Menüs, die auf beiden Editionen funktionieren.

**Muss ich alle sieben Server betreiben?**
Sobald es VectoraCloud gibt (geplant, Etappe 2), nicht mehr. Im Einrichtungsassistenten oder in der Konfiguration wählst du die Server und Minigame-Gruppen, die du willst; nur die legt die Cloud an. Bis dahin startet der Übergangsaufbau die vorhandenen Server.

**Wie wird VectoraCloud konfiguriert?**
Wie CloudNet: Assistent in der Konsole beim ersten Start, danach Konfigurationsdateien und Konsolenbefehle (Etappe 2). Später soll das Web-Dashboard das auch können (Etappe 8).

**Welche Java-Version brauche ich?**
Für den Betrieb der Server Java 25, weil Paper 26.3 es verlangt. Zum Bauen startet Gradle ab Java 17 und kompiliert mit einer Java-25-Toolchain, die es bei Bedarf herunterlädt. Der Bytecode der Plugins zielt auf Java 21, und alles, was Java 25 braucht, behält einen Java-21-Weg.

**Warum nicht einfach LuckPerms, WorldEdit, TAB und Co.?**
Vectora soll ein Plugin pro Plattform haben, das auf jedem Server gleich funktioniert und Bedrock kennt. Der Plan ersetzt LuckPerms, TAB, WorldEdit, WorldGuard, PlaceholderAPI, ProxyPortal und Chunky durch eigene Module. Bis es die gibt, nutzen die aktuellen Server noch einige davon.

**Darf ich den Code für mein eigenes Netzwerk nutzen?**
Ja, genau dafür ist er da, im Rahmen der GPL-3.0. Gibst du eine geänderte Version weiter, musst du den Quellcode deiner Änderungen unter derselben Lizenz veröffentlichen.

**Wird es Pay-to-Win geben?**
Nein. Der geplante Store verkauft nur Kosmetik und hält sich an die Minecraft-EULA. Wir entwickeln ihn gegen den Stripe-Testmodus. Jeder Betreiber bindet sein eigenes Stripe-Konto an und ist selbst dafür verantwortlich, echte Zahlungen rechtlich sauber abzuwickeln.

**Wo melde ich eine Sicherheitslücke?**
Privat, siehe [SECURITY.md](https://github.com/VectoDE/Vectora-Network/blob/main/SECURITY.md).

Weitere Fragen: [Q&A in den Discussions](https://github.com/VectoDE/Vectora-Network/discussions/categories/q-a).
