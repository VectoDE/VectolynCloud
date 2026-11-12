# Web panel

Module `cloud/panel`. A web interface for the cloud and the network, served by the node itself: no second process, no Node.js at runtime, no files outside the jar. German: [panel.de.md](panel.de.md).

- **Server:** an embedded Netty HTTP/1.1 server in the node (`PanelServer`), on a port of its own.
- **Frontend:** Next.js with TypeScript and Tailwind in `cloud/panel/web/`, exported to static files at build time and packed into the jar.
- **Data:** the panel runs the routes of the [REST API](api.md) in-process, so every rule of the API (validation, `ETag`/`If-Match`, errors) applies to the panel as well.
- **Languages:** German and English. The browser's language decides on the first visit; the switch in the header keeps the choice in the browser.

## Switching it on

The panel is off by default. It needs the API, because it uses the API's routes and, for any address other than loopback, the API's certificate:

```yaml
# config/cloud.yml
api:
  enabled: true
```

```yaml
# config/panel.yml
schema-version: 1
enabled: true
bind: 127.0.0.1        # loopback by default; any other address needs api.tls.enabled
port: 8090
# public-url: https://panel.example.org:8090
```

Changes take effect at the next start of the node. Then open `http://localhost:8090/` (passkeys need the host name, not the IP address).

| Field | Default | Meaning |
|---|---|---|
| `enabled` | `false` | start the panel with the node |
| `bind` | `127.0.0.1` | address to listen on |
| `port` | `8090` | TCP port, 1 to 65535 |
| `public-url` | none | the address the browser uses: `https://host[:port]`, or `http://localhost[:port]` on loopback; no path |

`panel.yml` is read with the same strict rules as the other configuration files: an unknown field, a duplicate key or a wrong type stops the panel (the node keeps running and logs every error with its field).

### Reaching it from another machine

Any bind address other than loopback needs TLS, and the panel uses the API's certificate for that: switch on `api.tls.enabled` in `cloud.yml` ([API, bind and TLS](api.md#bind-and-tls)). Without TLS the panel refuses to start on such an address and the node logs why. Set `public-url` to the address people type into the browser. A reverse proxy in front of the panel works the same way; keep TLS between the proxy and the panel.

### Host names

Every request must name the panel in its `Host` header: `localhost`, `127.0.0.1`, `[::1]`, the bind address, or the host of `public-url`. Anything else is answered with `421`. This stops DNS rebinding, where a web page on another domain makes the browser send requests to a panel on loopback. With a wildcard bind (`0.0.0.0`, `::`) and no `public-url`, every host name is accepted; set `public-url` in that case.

## What the server sends

| Path | Answer |
|---|---|
| `/`, `/<page>/` | the page's `index.html` |
| `/<page>` without slash | `308` to `/<page>/` |
| `/_next/static/...` | scripts and styles with a hash in their name |
| `/api/panel/info` | `{"name": "VectolynCloud", "version": "...", "frontend": true}` |
| anything else | `404` with the panel's not-found page |

Only `GET` and `HEAD` reach the files (`405` otherwise).

### Caching

Every file has a strong `ETag` (SHA-256 of its content). A browser that sends it back in `If-None-Match` gets `304` without the body. Files under `/_next/static/` carry their hash in the name and are cached for a year (`public, max-age=31536000, immutable`); pages and everything else are `no-cache`, so the browser always asks and gets `304` while nothing changed. Text files are sent gzip-compressed when the browser accepts it (`Vary: Accept-Encoding`). JSON answers are `no-store`.

### Security headers

On every answer: `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, `Cross-Origin-Opener-Policy: same-origin`, `Cross-Origin-Resource-Policy: same-origin`, a `Permissions-Policy` that switches off camera, microphone, location, payment, USB and the other device APIs and allows passkeys for this origin only, and with TLS `Strict-Transport-Security: max-age=31536000`.

Every page has its own `Content-Security-Policy`: scripts, styles, images, fonts and connections from the panel's own origin only, plus exactly the inline scripts of that page by their SHA-256 hash. There is no `unsafe-inline` and no `unsafe-eval`; `object-src 'none'`, `base-uri 'none'`, `frame-ancestors 'none'`. The server computes the hashes when it loads the files, so a changed build never needs a hand-edited policy.

### Limits

| Limit | Value |
|---|---|
| Requests per client address (IPv6 per /64) | 600 per minute, burst 120 (`429` with `Retry-After`) |
| Connections | 256, at most 32 per address |
| Request line / headers | 4 KiB / 8 KiB |
| Body | 256 KiB (`413`) |
| Idle connection | closed after 120 s |

Request targets are checked before anything else: printable ASCII, no `%` (nothing is ever decoded), no backslash, no empty, `.` or `..` segment, at most 2048 characters (`400`).

## Accounts and sign-in

### The first account

There is no default user and no default password. While no account exists, the panel shows **Create the first account** and asks for the one-time setup token that the node prints in its console (`vcs_...`, valid once, 24 hours; `api token setup` in the console prints a new one). The token creates the owner account with every right and is gone afterwards: it can no longer be exchanged for an API token either. The console reminds you at every start while the panel has no account.

### Ways to sign in

| Method | Notes |
|---|---|
| Password | 12 to 256 characters, not the user name. Stored as PBKDF2-HMAC-SHA-256 with 600,000 iterations and a random salt. |
| Authenticator app (TOTP) | RFC 6238, 6 digits, 30 seconds, one step of clock drift either way. A code works once. Asked for after the password. |
| Passkeys | WebAuthn with user verification. ES256, EdDSA and RS256. Need a host name (`localhost` or a domain), not an IP address, because browsers bind passkeys to it. |
| Single sign-on | OpenID Connect, authorization code flow with PKCE (S256), `state` and `nonce`. Only for accounts that linked their provider account under **My account**; the panel never creates accounts from the provider. |

Single sign-on is set in `panel.yml`; the client secret goes into `secrets/vectolyn.env` as `VECTOLYN_PANEL_OIDC_SECRET`. It needs `public-url`, since the provider sends the browser back to `<public-url>/api/panel/oidc/callback`, which you register there.

```yaml
# config/panel.yml
public-url: https://panel.example.org:8090
oidc:
  issuer: https://id.example.org/realms/main   # https, or http on loopback for tests
  client-id: vectolyn-panel
  name: Company login                           # button label, default "Single sign-on"
  scopes: [openid, profile, email]              # default
```

### Roles and rights

A user has one or more roles; their rights add up. Built-in roles cannot be changed:

| Role | Rights |
|---|---|
| `owner` | all |
| `operator` | services:read, services:write, config:read, backups:read, node:read, files:read, files:write, players:manage |
| `viewer` | services:read, config:read, backups:read, node:read |

Custom roles are made under **Users and roles** from these rights: the eight scopes of the [REST API](api.md) (`services:read`, `services:write`, `config:read`, `config:write`, `backups:read`, `backups:write`, `node:read`, `admin`) and the panel's own `files:read`, `files:write`, `players:manage`, `audit:read`, `users:manage`. When the panel calls the API for a user, it passes exactly the API scopes of that user's rights.

Nobody gives more than they hold: a role or a user can only be given rights the acting user has, and a user with more rights than the acting user cannot be changed or deleted. At least one enabled user keeps the `owner` role. Accounts and roles are stored in `secrets/panel-users.json` (owner-only, written atomically, only password hashes).

### Sessions and protection

- The session is a random 256-bit value in the cookie `__Host-vectolyn-session` (`HttpOnly`, `Secure`, `SameSite=Strict`, path `/`), kept in memory: a restart signs everyone out. It ends after 30 minutes without use and 12 hours after sign-in, and at once when the user is disabled, deleted or changes the password (other sessions).
- Every request that changes something must come from the panel's own origin (`Origin`, `Sec-Fetch-Site`) and carry the session's CSRF token in `X-Vectolyn-CSRF`.
- Sign-in endpoints allow 10 attempts at once and 20 per minute per client address; five failed sign-ins lock the account for 15 minutes.
- Browsers accept `Secure` cookies from `http://localhost`; on any other address the panel runs with TLS anyway.

### Audit log

`logs/panel-audit.jsonl` gets one JSON line for every setup, sign-in, failed sign-in, lock, sign-out, password change, TOTP and passkey change, single sign-on link, and every change of users and roles: time, event, acting user, affected user or role, client address. Never a password, code or key. At 10 MiB the file moves to `panel-audit.1.jsonl`. Users with `audit:read` read it through `GET /api/panel/audit?limit=100`.

## Building the frontend

The frontend is built by Gradle when `-Pvectolyn.panel.web=true` is set; this needs Node.js 20.9 or newer with npm on the `PATH`:

```sh
./gradlew build -Pvectolyn.panel.web=true
```

Gradle runs `npm ci --ignore-scripts` (exact versions from `package-lock.json`, no install scripts of any package), then `npm run build`, and copies `web/out/` into the jar under `net/vectolyn/cloud/panel/web/` together with `files.index`, the list of files the server loads. Without the property the jar carries a placeholder page that says how to build the frontend; the server and its tests behave the same. Release builds and the Build job of CI on Linux set the property.

During development:

```sh
cd cloud/panel/web
npm ci --ignore-scripts
npm run dev          # http://localhost:3000, without the panel server's endpoints
npm run check-i18n   # German and English have the same keys and placeholders
npm run build        # static export to out/
```

`npm run build` first copies the design tokens and fonts from `branding/` (`scripts/sync-brand.mjs`), so the panel uses the same files as the website; the copies are not committed.

## Accessibility

The panel aims at WCAG 2.2 AA and AAA where it is reachable: a skip link to the content, one visible focus style on everything focusable (3 px, offset), targets of at least 44 × 44 px, the page language set from the chosen language, live regions for state that changes, and no motion when the system asks for reduced motion. Colours come from the brand tokens, which meet AA contrast in both schemes.

## Tests

- `PanelServerTest` runs the server on `127.0.0.1` with a random port: CSP with the page's hash and no `unsafe-inline`, the security headers, `ETag` and `304`, immutable caching of hashed files, gzip, the redirect of a folder without slash, the 404 page, `405`, the info endpoint, refused `Host` names, refused targets (`..`, `.`, `%2e`, backslash, empty segments), and that a public address without TLS does not start.
- `PanelConfigLoaderTest`: defaults without a file, strict reading, port and `public-url` rules.
- Fuzz targets (Jazzer, see [architecture](architecture.md#fuzz-tests)) in `PanelInputFuzzTest`: request targets are clean or refused, `panel.yml` is settings or configuration errors, public URLs are origins or refused.
- The **Panel frontend** job of `ci.yml` checks that both languages have the same keys, type-checks the TypeScript and builds the static export.
