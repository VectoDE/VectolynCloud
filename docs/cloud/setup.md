# Setup assistant

Deutsch: [setup.de.md](setup.de.md)

VectoraCloud has one entry point: `java -jar VectoraCloud.jar`. As long as `cloud.yml` has `setup-complete: false`, the node runs the setup before it starts any server. There are three ways to answer, and all of them write through the same `ConfigService` as the console commands and hand edits:

| Way | When | How |
|---|---|---|
| Questions on the console | first start with a terminal, and later the `setup` command | `SetupWizard.run` |
| Setup file | Docker, Pterodactyl, CI, any start without a person at the keyboard | `--setup-file setup.yml` |
| Files and commands | at any time afterwards | edit `cloud.yml`, `groups/`, `tasks/`, or use `config set`, `group`, `task` (see [console](console.md)) |

The dashboard will be a fourth way later and will use the same layer.

The code is in `VectoraCloud/cloud-setup` (`net.vectora.cloud.setup`).

## The questions

The assistant asks in this order. Every question shows its default in brackets; Enter takes it. `!cancel` or Ctrl+C stops at any point, and nothing is written.

1. **Language** (`de` or `en`). The answer switches the language of the following questions right away.
2. **Minecraft EULA**, with the link <https://aka.ms/MinecraftEULA>. Without consent the setup stops and the cloud starts no server. Once accepted, a later run skips the question.
3. **Network name**, 1 to 32 letters, digits, space, `.`, `_` or `-`.
4. **Servers.** One yes/no switch per server: proxy, lobby, Survival, Creative, Mod-Server (Fabric), End, minigames and the legacy proxy (Velocity for 1.8.8, own port, off by default). With the legacy proxy on, it also asks for the 1.8.8 server (PandaSpigot). Switching the proxy off prints a warning, because then nobody can join from outside. With two or more minigame groups, each group gets its own switch.
5. **Network.** Bind address (`0.0.0.0` means every address). With the proxy on: Java port and Bedrock port (UDP). With the legacy proxy on: its port. A port that the node's channel or the service range already uses is refused where you type it. On Pterodactyl the Java port defaults to `SERVER_PORT`, and the assistant reminds you that every port needs an allocation in the panel.
6. **Memory.** Maximum heap per wanted group, with these defaults:

   | Group | Default |
   |---|---|
   | proxy, legacy-proxy | 512M |
   | lobby, end, legacy-server | 1G |
   | minigame | 1536M |
   | creative | 2G |
   | survival, mod | 3G |

   Values from 256M, in `M` or `G`. The minimum heap is set to 512M or the maximum, whichever is smaller. The assistant adds everything up and warns when the sum exceeds the memory of the machine or of the Pterodactyl server (`SERVER_MEMORY`).
7. **Services.** For every wanted server except the proxies, the number of services as `static/min/max`, with these defaults:

   | Server | Default |
   |---|---|
   | lobby | 1/1/4 |
   | legacy-server (1.8.8) | 1/1/3 |
   | minigame | 0/1/8 |
   | survival, creative, mod, end | 1/1/1 |

   Static services keep their worlds and always run; above them the cloud adds dynamic ones by player count, fresh from the template, up to max (see [scaling](scaling.md#static-and-dynamic-in-one-task)). `static ≤ min ≤ max` and max from 1 to 1000 are checked where you type them. The proxies always get 1/1/1. New tasks get `scale-up-at` 75 and `scale-down-below` 50; for an existing task only the three numbers are written, and only if they changed.
8. **Database.** `managed` (the cloud runs its own MariaDB), `docker`, `external` or `h2`. On Pterodactyl `docker` is left out, since the server already runs inside a container without access to Docker. Where MariaDB has no server build (anything except Windows x64 and Linux x86_64 with glibc), `managed` is marked as falling back to H2, and H2 is the default. After `managed` the assistant explains the switch behind it: `servers.database: true` in `cloud.yml` runs MariaDB, `false` uses H2 files per server. For `external` it asks for host, port (3306), database name, user and password.

Then it prints a summary: every answer, the generated secrets (as "hidden"), and the files it would write. You choose `save`, `change` (all questions again, your answers are the new defaults) or `cancel`. Only `save` writes. If the checks find a problem, `save` is not offered until you change the answer.

After saving it says who can read `secrets/vectora.env` (see [below](#who-can-read-the-secrets)).

### The password of an external database

Secrets never appear on the screen and never go into the console history.

- **The console hides input** (JLine on a real terminal): the password is asked like any other question, but masked. On a later run Enter keeps the stored one.
- **The console shows input** (plain stdin, for example some IDEs or `docker attach` without a TTY): the assistant says so and offers three ways:
  - `file`: you put `VECTORA_DB_PASSWORD=...` into `secrets/vectora.env` yourself (it prints the full path), then confirm. The assistant checks the file and asks again until it finds a value.
  - `type`: you type it anyway, knowing that it is visible.
  - `keep`: keeps the stored password (only offered if there is one).

All other secrets (forwarding secrets, database passwords of the managed server, management secret, Floodgate key) are generated with `SecureRandom` and never shown. See [config](config.md#secrets).

### Running it again: `setup`

The console command `setup` runs the questions again. The current configuration provides the defaults, and only what you change is written. If you change nothing, the assistant says so and writes nothing; if you only change the lobby's heap, only `groups/lobby.yml` is written. An existing EULA consent and `setup-complete` stay. A server switched off stops right away (see [node](node.md#reload)); a server switched on starts with `service start <task>` or at the next start of the cloud, and the assistant says so after saving.

## Setup file (`--setup-file`)

For starts without questions:

```sh
java -jar VectoraCloud.jar --non-interactive --setup-file /data/setup.yml
```

```yaml
schema-version: 1
eula: true                    # required; you accept https://aka.ms/MinecraftEULA
language: en                  # en or de
network-name: Vectora
servers:                      # every switch is optional and keeps its default
  proxy: true
  lobby: true
  survival: true
  creative: true
  mod: false
  end: true
  minigame: true
  legacy-proxy: false
  legacy-server: false        # needs legacy-proxy: true
minigames:                    # own switch of existing minigame groups
  bedwars: false
network:
  bind: 0.0.0.0
  java-port: 25565
  bedrock-port: 19132
  legacy-port: 25564
memory:                       # maximum heap per group
  survival: 4G
services:                     # per task: any of the three numbers, the rest keeps its default
  lobby:
    static-services: 1
    min-services: 2
    max-services: 6
  minigame:
    max-services: 12
drain-timeout:                # optional, per group, 5s to 10m; missing: the group keeps its value (new groups 30s)
  lobby: 45s
node:                         # optional, memory of this node in nodes.yml
  max-memory: 8G              # at least 512M; missing: keeps its value (new homes: worked out by the node)
  memory-reserve:             # each 1M to 64G; a field left out keeps its value or default
    system: 2G                # default 1G
    node-overhead: 256M       # default 256M
    database: 512M            # default 512M, counted only for a managed or docker database
database:
  mode: external              # managed, docker, external or h2
  host: db.example.org
  port: 3306
  name: luckperms
  user: luckperms
  password-file: /run/secrets/vectora-db   # optional, see below
  # docker-image: mariadb:11.8              only with mode: docker
```

Rules:

- Everything except `schema-version` and `eula` may be left out and then keeps the default the assistant would offer.
- The file is read as strictly as `cloud.yml`: unknown fields, duplicate keys, wrong types or a missing `eula: true` are errors. The file is checked with exactly the same rules as typed answers (ports, heap, legacy server only with legacy proxy, Docker not on Pterodactyl, the whole-configuration validation). On any error nothing is written and every problem is listed.
- `memory` and `minigames` may only name groups that exist or that the setup creates. `services` may only name tasks that exist or that the setup creates; the proxies are not among them, they always run one service. Each entry is checked like a typed answer (`static ≤ min ≤ max`, max from 1 to 1000). `drain-timeout` may only name groups that exist or that the setup creates; the console assistant does not ask for it, `group set <name> drain-timeout` changes it later.
- `node` changes the node this cloud runs as (`node-id` in `cloud.yml`), checked like `nodes.yml`. The console assistant does not ask for it; `node set` changes it later. The file cannot switch `max-memory` back to automatic; `node set <id> max-memory auto` does that.
- The file is used only while `setup-complete` is false. Once the setup is done, a file that stays mounted is ignored, so it cannot undo later changes made with `config set` or the dashboard.
- The file holds no secret. The password of an external database comes from `secrets/vectora.env` (`VECTORA_DB_PASSWORD`) or from `database.password-file`: a regular file (a link to one is fine; a FIFO or device is refused) with the password on one line (a trailing line break is dropped), at most 4 KiB, UTF-8, relative to the home or absolute. That fits Docker and Kubernetes secrets.

### Why a file and not environment variables

- The launcher already accepts `--setup-file`, and the file goes through the strict YAML reader the configuration files use. Errors come with path and line.
- Maps and lists (memory per group, services per task, minigame switches) fit naturally into YAML. As variables they would need a naming scheme of their own.
- Environment variables are a poor place for secrets: they show up in `docker inspect`, in `/proc/<pid>/environ` and in the Pterodactyl panel, and every server process the cloud starts inherits them. The setup file holds no secret at all.
- Both ways would need the same checks anyway. One format means one parser, one fuzz target, one set of tests.

Platforms that think in variables can still write the file from them, see Pterodactyl below.

### Docker

```sh
docker run -it -v vectora:/data <image>   # questions in the terminal
docker run -v vectora:/data -v ./setup.yml:/data/setup.yml:ro <image> --non-interactive --setup-file /data/setup.yml
```

`<image>` is any image with Java 21 or later and `VectoraCloud.jar`. For an external database, mount the password as a secret file (Compose `secrets:`, Kubernetes secret volume) and name it in `database.password-file`.

With `-it` the questions run in the terminal. Without a TTY the console shows input, so the assistant offers the file way for the password (see above).

### Pterodactyl

The questions work in the panel console. Its input is visible to everyone who can open the console, so the assistant offers to put the password into `secrets/vectora.env` through the file manager instead.

For an egg without questions, let Wings write the setup file from egg variables with `config.files` and the `yaml` parser, and add `--non-interactive --setup-file setup.yml` to the startup command:

```json
"config": {
  "files": "{\"setup.yml\": {\"parser\": \"yaml\", \"find\": {\"schema-version\": \"1\", \"eula\": \"{{server.build.env.EULA}}\", \"network-name\": \"{{server.build.env.NETWORK_NAME}}\", \"network.java-port\": \"{{server.build.default.port}}\", \"database.mode\": \"{{server.build.env.DB_MODE}}\"}}}"
}
```

`docker` is refused on Pterodactyl. `SERVER_PORT` is the default Java port and `SERVER_MEMORY` the limit for the memory warning. The launcher detects Pterodactyl (see [launcher](launcher.md)); the assistant takes its decision.

## Who can read the secrets

`secrets/vectora.env` is restricted to the account that runs the cloud (see [config](config.md#generation-and-storage)). After saving, the assistant says who else can still get at it:

- **Linux and macOS:** the owner and root.
- **Windows:** the owner, and members of the local Administrators group. They are not on the access list, but Windows always lets them take ownership of a file and then read it. The group name is localized (`BUILTIN\Administrators`, `VORDEFINIERT\Administratoren`, …), so the assistant looks it up with `%SystemRoot%\System32\whoami.exe /groups` and always adds its SID `S-1-5-32-544`, which is the same on every Windows. Keep that group small on a server that runs the cloud.
- **File systems without permissions** (FAT, some network shares): everyone who can reach the file. The assistant says so plainly.

## Integration into the node

`CloudNode.start()` runs the setup after the cloud channel is up and orphans are ended, before any service starts (see [node](node.md#start-order)):

```java
SetupEnvironment env = SetupEnvironment.of(settings.toMap(), System.getenv());
SetupWizard.registerCommand(console, config, env);       // only with a console
SetupResult result = SetupWizard.ensureComplete(console, config, env);  // console may be null
if (!result.ready()) {
    // cancelled or EULA declined: start no server; the console stays open for "setup"
}
```

`ensureComplete` does nothing once `setup-complete` is true, uses the setup file if there is one, asks on the console if questions are allowed, and otherwise throws a `SetupException` that explains `--setup-file`. The node prints the exception with `describe(language)` (on the console, or in the log without one) and starts no service. `nonInteractive` checks `setup-complete` itself as well, so a setup file that stays mounted never undoes later changes. The launcher passes `setup-file`, `non-interactive` and the `platform.*` entries through to `SetupEnvironment`. If the setup is finished later with `setup` on the console, the node starts the wanted servers right after it.

## What gets written

Writing happens in three steps, so nothing ends up half done and marked complete:

1. all configuration changes in one `ConfigService.apply`, validated as a whole. If it fails, nothing is written at all;
2. only then the secrets: every missing generated secret, then the password of an external database, so a password never goes with a host and user that were not saved;
3. last, `eula-accepted: true` and `setup-complete: true`.

If step 2 or 3 fails, the configuration goes back to the state before (groups and tasks the setup created are removed again) and a password the setup wrote goes back to the one before. Generated secrets that were missing stay; they never replace a value. `setup-complete` stays false and the next start asks again. For every wanted server without a group, the assistant creates the group (template, software, Minecraft version, heap, `combat: legacy` for PandaSpigot) and a task with the service counts from step 7 (proxies 1/1/1). An unwanted server only gets its switch under `servers:` in `cloud.yml` turned off; an existing group stays as it is. Minigame groups switched off one by one get `wanted: false` in their group file. The download catalog of new groups starts empty and is filled by the server templates.

## Tests

`VectoraCloud/cloud-setup/src/test`: the questions through a scripted terminal (order, defaults, cancel at any point, EOF, Ctrl+C, declined EULA, Pterodactyl, masked and visible password input, reserved ports, `change`, rerun with no and with one change, German texts, the `setup` command), the setup file (every rule above, `ensureComplete`, only regular files up to the limit), the writer (`SetupApplierTest`: declined EULA, rollback after a failed secret write, no secret after a failed configuration write), password files (also FIFOs and devices), the Administrators group, the environment, and a check that no text opens with "No" or "Not", including the `setup` command's description, the labels of the port hint and the problems of cloud-config that the setup passes on. Jazzer fuzz tests: `SetupFileFuzzTest.setupFileFailsOnlyWithReadableProblems` and `SetupFileFuzzTest.passwordFileGivesASecretOrAProblem`, seeds in `src/test/resources/net/vectora/cloud/setup/SetupFileFuzzTestInputs/`.
