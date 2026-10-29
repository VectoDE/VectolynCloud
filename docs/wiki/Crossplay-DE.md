# Crossplay

[English](Crossplay)

Ziel: Java-Clients von 1.8 bis 26.3 und Bedrock-Clients spielen auf denselben Servern.

## Wie Spieler hereinkommen

| Client | Weg |
|---|---|
| Java, neueste Version | Velocity → direkt zum Backend |
| Java, andere Versionen | ViaVersion übersetzt neuere Clients; ViaBackwards und ViaRewind decken ältere Clients bis 1.8 ab |
| Bedrock | Geyser übersetzt das Protokoll, Floodgate meldet Bedrock-Spieler ohne Java-Account an |

All das läuft auf dem Proxy. Die Backends laufen auf der neuesten Minecraft-Version und müssen nicht wissen, mit welchem Client jemand spielt.

## Aktueller Stand

- Auf dem Proxy laufen Velocity, Geyser, Floodgate, ViaVersion 5.12.2, ViaBackwards 5.12.2 und ViaRewind 4.2.0. ViaBackwards und ViaRewind übersetzen für ältere Java-Clients bis 1.8 ([#5](https://github.com/VectoDE/VectoraCloud/issues/5)).
- Floodgate läuft auf jedem Backend, auch auf dem Fabric-Mod-Server (Floodgate-Fabric 2.2.7-b69, [#4](https://github.com/VectoDE/VectoraCloud/issues/4)). Alle Server nutzen denselben Floodgate-Schlüssel.
- Geyser lauscht auf Port 19132 und nutzt in der aktuellen Konfiguration den NetherNet-Transport.

## Regeln für Spielinhalte

- Jeder Spielmodus muss auf Bedrock spielbar sein. Ein Modus, den Bedrock-Spieler nicht betreten können, wird nicht veröffentlicht.
- Menüs sind Inventar-Menüs, die Geyser auch auf Bedrock anzeigt. Bedrock-Formulare nutzen wir nicht, damit beide Editionen dasselbe Menü sehen.
- Der Mod-Server nutzt nur serverseitige Mods, weil Bedrock-Clients keine Fabric-Mods installieren können.
- Der geplante Anti-Cheat (Etappe 6) behandelt Bedrock-Bewegungen gesondert, damit Bedrock-Spieler für normales Verhalten nicht auffallen.
- Das geplante Resourcepack (Etappe 7) gibt es als Java-Pack für jedes `pack_format` von 1.8 bis 26.3 und als Bedrock-Pack für Geyser.

## Plugin-Versionen (geplant, Etappe 3)

Das Vectora-Plugin deckt Minecraft 1.8.8 bis 26.3 über Versionsadapter ab. Für 1.8.8-Server-Gruppen wählen wir in Etappe 2 einen modernen Fork, der unter Java 21 läuft.
