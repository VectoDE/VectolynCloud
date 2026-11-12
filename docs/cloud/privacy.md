# Data protection (cloud-privacy)

This page describes what the code does with personal data. It is technical documentation. It is **not legal advice and has not been reviewed by a lawyer**; every statement about a legal basis below is a draft that a lawyer has to check before the first public release (open questions at the end).

## IP ban list

**What is stored.** One entry per banned IP address in `state/ip-bans.json`: the address, a short free-text reason (at most 200 characters, no personal data of the person behind the address), the creation time and an optional end time. Nothing else: no player name, no UUID, no connection log.

**Purpose.** Protecting the network and its players from abuse that is tied to an address: login floods, spam, attacks on servers, repeated ban evasion.

**Draft of the legitimate interest (Art. 6 (1) (f) GDPR).** The operator has a legitimate interest in keeping the network available and safe. A ban by address is the least intrusive means that works against anonymous abuse. The balancing is kept small by design:

- only the address is stored, no content of communication;
- an entry is created only by a deliberate ban (console or later the panel), never automatically for every visitor;
- the entry is deleted automatically (see below), so a ban does not become a permanent record;
- a person who is affected can object; the operator then removes the entry (`unban`).

An IP address is personal data. Whether the balancing holds for a given operator is for the operator and their lawyer to decide.

**Retention.** `config/privacy.yml`:

```yaml
schema-version: 1
ip-bans:
  retention-days: 90   # 1 to 3650, default 90
```

Without the file the default of 90 days applies. An entry is deleted `retention-days` days after it was created, or earlier if the ban was written with its own end time. A ban that is still needed is renewed by banning the address again, which starts the period again; so every extension is a deliberate decision.

**Automatic deletion.** The list is cleaned when the node starts and every hour after that. Entries that are due are removed from memory and from the file (the file is rewritten atomically). Between two runs an entry that is due already counts as gone: `IpBanStore.isBanned` never reports it.

**Code.** Module `cloud/privacy`: `StatisticsCollector` and `Telemetry` (see below), `PrivacyConfig` and `PrivacyConfigLoader` (strict YAML like the other configuration files), `IpBanStore` (list, purge, atomic writes), `PrivacyRuntime` (hourly purge, started and closed by the node). The ban file is read strictly and has a Jazzer fuzz test.

**Not part of this module yet.** The proxy bridge that refuses a connection from a banned address, console commands and the panel view are separate steps; this module is the store and the deletion they will use.

## Statistics per game mode

**What is counted.** For each game mode (the group of the server the player is on) three sums: logins (a player joins the network there or switches to it), playtime in seconds (summed over all players) and the peak number of players online at the same time. Stored in `state/statistics.json`, saved every five minutes and when the node stops.

**What is not stored.** No name, UUID, address or time of an individual player. To compute playtime the collector keeps, in memory only, the mode and start time of each player who is online; the entry is dropped when the player leaves and never written to disk. With only sums on disk, the file does not identify anyone. Whether such totals are anonymous data in the legal sense is a question for a lawyer (see below).

**Switch.** `statistics.enabled` in `config/privacy.yml`, default `true`. The numbers stay on the machine of the operator.

## Telemetry

Telemetry is **off** and sends nothing unless the operator switches it on with a record of consent. `privacy.yml`:

```yaml
schema-version: 1
telemetry:
  enabled: true
  endpoint: https://telemetry.example.org/v1   # no default; HTTPS only
  consent:
    given-by: Jane Admin          # who agreed
    given-at: 2026-11-01T10:00:00Z
    text-version: 1               # version of this description
```

The cloud refuses to start with `telemetry.enabled: true` if the consent record, a past `given-at`, the current `text-version` or an HTTPS endpoint is missing. There is no built-in endpoint: without one nothing can be sent. Removing `enabled` (or setting it to `false`) stops it at once; the consent block may stay as a record.

**Text version 1: what is sent.** Once a day, one JSON document by HTTPS POST, without redirects, cookies or credentials:

```json
{"report-version":1,"cloud-version":"0.1.0","os":"Linux",
 "modes":[{"mode":"lobby","logins":3,"playtime-seconds":120,"peak-players":2}]}
```

That is the whole report: report format, cloud version, name of the operating system, and the three sums per game mode (empty if `statistics.enabled` is false). No IP address of the sender is added by the cloud, but the receiving server sees the address of the connection; the operator who chooses the endpoint is responsible for what it logs. `Telemetry.report()` returns the exact bytes before anything is sent.

## Questions for a lawyer

1. Is the balancing of interests for IP bans by address sufficient under Art. 6 (1) (f) GDPR, and does it need a written record beyond this page?
2. Is a default of 90 days appropriate for the ban list, or should the default be shorter?
3. What must the privacy notice say about the ban list (purpose, legal basis, retention, right to object)?
4. Is a record of processing activities (Art. 30 GDPR) required for this processing, and what must it contain?
5. Are the per-mode sums anonymous data, or does the short-lived in-memory session map make the statistics personal data processing?
6. Is the consent record in `privacy.yml` (name, time, text version) sufficient as a proof of consent of the operator, and is the operator the right person to consent, or does the data protection law apply at all to a report without personal data?
7. Which duties come with a telemetry endpoint run by someone else (processor agreement, transfer to third countries, notice in the privacy policy)?
