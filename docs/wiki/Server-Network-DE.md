# Server-Netzwerk

[English](Server-Network)

Ein Vectora-Netzwerk besteht aus sieben Servern und Minigame-Servern, die die Cloud skalieren wird (geplant, Etappe 6). Die Ports entsprechen dem aktuellen lokalen Aufbau.

| Server | Software | Port | Rolle |
|---|---|---|---|
| Proxy | Velocity + Geyser + Floodgate + Via-Plugins | 25565 (Java), 19132/UDP (Bedrock) | Einstieg für Java und Bedrock, MOTD, Tablist, netzwerkweiter Chat |
| Lobby | Paper | 25566 | Hub mit Navigator und Portalen zu den Modi |
| Survival | Paper | 25567 | Klassisches Survival |
| Mod-Server | Fabric | 25568 | CraftAttack-artiger Server, nur serverseitige Mods |
| Creative | Paper | 25569 | Plotwelt und Freebuild-Welt |
| End | Paper | 25570 | Die einzige End-Welt des Netzwerks |
| Database | MariaDB | 3307 | Gemeinsame Datenbank |
| Minigames (geplant) | Paper, dynamisch skaliert | vergibt die Cloud | BedWars, SkyWars und mehr |

Von außen sollten nur die Proxy-Ports erreichbar sein. Die Backends binden an `127.0.0.1` (`VECTORA_SERVER_HOST`) und bekommen die Spieler über das Modern Forwarding von Velocity.

## Welche Server laufen (geplant, Etappe 2)

Du musst nicht alle betreiben. Im Einrichtungsassistenten von VectoraCloud oder in der Cloud-Konfiguration markierst du jeden Server und jede Minigame-Gruppe als gewollt oder nicht. Die Cloud legt nur gewollte Server aus `Server-Network/<Server>/template/` in `runtime/` an, lädt die Software, setzt die Secrets ein und startet sie. Nicht gewollte Server entstehen gar nicht, und weder Proxy noch Lobby verweisen auf sie.

Neustarts übernimmt allein die Cloud. Die Paper- und PandaSpigot-Templates schalten `restart-on-crash` und `restart-script` von Spigot ab, und die Cloud setzt das auch bei eigenen Templates durch. `/restart` hält den Server deshalb nur an, die Cloud startet ihn wieder.

Bis Etappe 2 laufen die vorhandenen Server lokal aus `runtime/` über die Windows-Startskripte.

## Geplante Inhalte je Server

**Proxy.** Einziger Einstieg. MOTD, Tablist und netzwerkweiter Chat kommen aus dem Vectora-Plugin (Etappe 3).

**Lobby** (Etappe 4). Eine echte Hub-Lobby, per Code im Vectora-Stil generiert: Kompass-Navigator, NPCs und Portale zu den Modi, Hologramm-Bestenlisten, Gadgets und Cosmetics, Profilmenü, Schutz, Doppelsprung, Parkour.

**Survival** (Etappe 5). Claims; Homes, TPA, Spawn und Back; Spieler-Shops und Markt; Jobs und Quests; eigene Economy.

**Creative** (Etappe 5). Eigenes Plot-System mit einem Bauwerkzeug ähnlich WorldEdit, dazu eine Freebuild-Welt.

**Mod-Server** (Etappe 5). Fabric mit ausschließlich serverseitigen Mods, damit Bedrock-Spieler beitreten können. Die Modliste wird vorgeschlagen und vor dem Einbau freigegeben. Performance-Mods: Lithium, FerriteCore, ServerCore, FabricProxy-Lite.

**End** (Etappe 5). Die einzige End-Welt im Netzwerk. End-Portale auf Survival und dem Mod-Server führen hierher. Inventare bleiben pro Herkunftsserver getrennt. Ausgangsportal und Tod schicken Spieler zurück zu ihrem Herkunftsserver. Auf allen anderen Servern ist das End deaktiviert.

**Minigames** (Etappe 6). BedWars, SkyWars, Parkour/JumpLeague, SurvivalGames, TNTRun/Spleef, BuildBattle, MurderMystery/TTT. Maps per Code generiert, Kampfsystem (1.8 oder modern) pro Modus einstellbar.

**Database.** MariaDB, ab Etappe 2 von der Cloud verwaltet.

Survival und Mod-Server haben getrennte Inventare und getrennte Economy.

## Netzwerkweite Funktionen (geplant)

- Ränge: Spieler, kosmetische Supporter-Ränge, Content Creator, Builder, Supporter, Moderator, Developer, Admin, Owner
- Soziales: Freunde, Party, Clans, Privatnachrichten, Chat-Kanäle
- Moderation: Bans, Mutes, Kicks, Reports, Chatfilter, Verlauf
- Statistiken pro Modus und Bestenlisten
- Coins, Gadgets, Partikel
- Store: EULA-konform und rein kosmetisch
