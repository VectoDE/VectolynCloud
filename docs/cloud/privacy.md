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

## Per-player tracking

**What it is.** Activity data of a single player: name and UUID, time of the first and last visit, logins and playtime, in total and per game mode (the group of the server). It exists only for players who agreed, in game, and only from that moment on. The statistics above are different: they are sums without a player.

**Switch.** Off by default. The operator names themselves as the responsible party first (`config/privacy.yml`); the cloud refuses to start with `tracking.enabled: true` and no `controller`:

```yaml
schema-version: 1
tracking:
  enabled: true
  retention-days: 90                      # 1 to 3650, counted from the last visit
  controller: Example Network, privacy@example.org
```

**Opt-in in game.** On the Velocity proxy the bridge adds `/privacy`:

| Command | Effect |
| --- | --- |
| `/privacy` | shows the notice: responsible party, purpose, what is stored, how long, how to withdraw |
| `/privacy on` | shows the notice and asks for `/privacy on confirm` |
| `/privacy on confirm` | records the consent (time and notice version) and starts counting from then on |
| `/privacy off` | withdraws and deletes everything about the player at once |
| `/privacy data` | access: the full record as JSON |
| `/privacy status` | whether the player agreed |

The text is in German or English by the player's language. It comes from the cloud (`TrackingNotice`, version `1`) so every place that shows it says the same. Nothing is recorded without `/privacy on confirm`: the store ignores the events of everyone else. The proxy sends the request over the cloud channel `vectolyn.privacy`; the node answers only a proxy and only for a player who is online through exactly that proxy.

**Purpose (as shown to the player).** To analyse how the network and its game modes are used, so they can be improved.

**Retention.** `retention-days` after the last visit; the node deletes due records when it starts and every hour. A player who is online is never due.

**Access and deletion in the panel.** `GET /api/v1/admin/privacy/players/{uuid}` returns the record, `DELETE` removes it (token with `admin`, see [api.md](api.md)). The panel calls these two endpoints. Deleting is the same as `/privacy off`.

**Code.** `TrackingStore` (`state/tracking.json`, atomic writes, strict reader with a fuzz test), `TrackingNotice`, `PrivacyRequests` (the request logic), `NodePrivacy` (checks the sender), `PrivacyCommand` in `plugins/bridge/velocity`, `PrivacyAccess` in `cloud/api`. Only the Velocity proxy has the command so far; the legacy proxy has none.

**Legal basis, draft.** Consent of the player (Art. 6 (1) (a) GDPR), asked for in game, voluntary, withdrawable at any time with `/privacy off`. Whether consent is the right basis for players under 16, whether § 25 TDDDG applies to a server-side record at all, and whether the notice is enough are questions for a lawyer (below).

## Questions for a lawyer

1. Is the balancing of interests for IP bans by address sufficient under Art. 6 (1) (f) GDPR, and does it need a written record beyond this page?
2. Is a default of 90 days appropriate for the ban list, or should the default be shorter?
3. What must the privacy notice say about the ban list (purpose, legal basis, retention, right to object)?
4. Is a record of processing activities (Art. 30 GDPR) required for this processing, and what must it contain?
5. Are the per-mode sums anonymous data, or does the short-lived in-memory session map make the statistics personal data processing?
6. Is the consent record in `privacy.yml` (name, time, text version) sufficient as a proof of consent of the operator, and is the operator the right person to consent, or does the data protection law apply at all to a report without personal data?
7. Which duties come with a telemetry endpoint run by someone else (processor agreement, transfer to third countries, notice in the privacy policy)?
8. Is consent in game by command (`/privacy on confirm`, after showing the notice) a valid, informed and demonstrable consent under Art. 6 (1) (a), 7 GDPR? Is the stored proof (time, notice version) enough?
9. What applies to minors (age limit for consent, parental consent)? Must the opt-in check the age?
10. Does § 25 TDDDG apply to the tracking at all, since nothing is read from or stored on the player's device?
11. Is the notice text (version 1) complete under Art. 13 GDPR (responsible party, purpose, legal basis, retention, rights, right to withdraw, supervisory authority), and what is missing?
12. Is 90 days after the last visit an acceptable default retention? Is "deleted at once on withdrawal" enough for Art. 17 GDPR?
13. Is an administrator with the `admin` API scope a permitted recipient of the access and deletion data, and is access logging needed?

Questions 14 to 20 (legal texts, payments, licences) and the German drafts of the privacy policy, imprint and terms are in [docs/legal](../legal/README.md). All of them are drafts and not checked by a lawyer.
