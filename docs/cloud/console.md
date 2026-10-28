# VectoraCloud console

Module `VectoraCloud/cloud-console`, package `net.vectora.cloud.console`. It reads commands from the terminal, runs them, and offers a question API that the setup assistant builds on. Every change to the configuration goes through [cloud-config](config.md), so a command is checked exactly like a hand-edited file.

German version of the command reference: [console.de.md](console.de.md).

## Terminal

`TerminalSelector` decides how the console talks to the terminal. It looks at the running Java version, which JLine providers load, and whether stdin and stdout are a terminal:

| Situation | Mode |
|---|---|
| stdin or stdout is not a terminal (Docker without `-t`, Pterodactyl, systemd, a pipe) | plain: reads stdin line by line |
| Java 22 or newer and the `ffm` provider loads | JLine with `ffm` |
| otherwise, `jni` loads (Java 21 path) | JLine with `jni` |
| no native provider loads | JLine's `dumb` terminal |

`ffm` uses the Foreign Function & Memory API, which is final since Java 22. On Java 21 its classes are not even loaded; the check is the running version, never the order of jars on the class path. This is the one place in the cloud that uses a Java 22+ API, see [Java compatibility](../java-compatibility.md).

Override with the system property `vectora.console` or the environment variable `VECTORA_CONSOLE`: `auto` (default), `ffm`, `jni`, `dumb` or `plain`. A provider that is not available falls back to the next one in the table, and the log says why.

Both native providers call native code. Java 24 and newer warn about that unless native access is allowed, so `VectoraCloud.jar` has to declare `Enable-Native-Access: ALL-UNNAMED` in its manifest (the tests pass `--enable-native-access=ALL-UNNAMED`).

### On a terminal (JLine)

- Prompt `vectora> `, colored only if the terminal reports at least 8 colors and `NO_COLOR` is not set. Errors are red, warnings yellow, success green.
- Line editing, tab completion of commands, subcommands and values (keys of `cloud.yml`, group and task names, enum values).
- Log lines appear above the prompt; the line being typed stays.
- History in `<home>/state/console.history`, at most 500 lines, restricted to the owner. Not stored: answers to questions, secrets, lines of commands marked sensitive, lines that start with a space. If the file is a symbolic link, the history stays in memory.
- History expansion (`!!`) is off, so a `!` in a value stays as typed.
- Ctrl+C and Ctrl+D run `shutdown`, exactly as if it was typed.

### Without a terminal (plain)

- Reads stdin as UTF-8, one line per command. No prompt is printed, because the output usually ends up in a log viewer. No colors, no history.
- Lines longer than 4096 characters are refused.
- The end of stdin only stops reading; the cloud keeps running. Under systemd and in Docker without `-i`, stdin is empty from the start. Stop the cloud with a signal (SIGTERM, `docker stop`), which the node handles.
- Ctrl+C does not reach the console here. To cancel a question, type `!cancel`.

## Command line syntax

| Input | Words |
|---|---|
| `config set proxy.port 25566` | `config`, `set`, `proxy.port`, `25566` |
| `config set network-name 'My Network'` | ..., `My Network` |
| `"say \"hi\""` | `say "hi"` |
| `"C:\Vectora"` | `C:\Vectora` (a backslash before other characters stays) |
| `a\ b` | `a b` |
| `config set database.docker-image ""` | ..., empty word (clears an optional text) |

Refused, with the position: an unclosed quote, a backslash at the end of the line, control characters and line breaks, more than 4096 characters, more than 64 words. Error messages name the rule, never the text typed, so a secret pasted at the wrong prompt does not end up in the output or a log. Jazzer fuzzes the parser (`CommandLineParserFuzzTest`).

## Commands

The language of all texts comes from `language` in `cloud.yml` and switches at once.

### `help [command] [subcommand]`

Without arguments it lists every command. With a command it shows the usage line, the aliases, the subcommands and the arguments.

### `shutdown`, `exit`

Stops every service and the cloud. Ctrl+C and Ctrl+D on a terminal run the same command.

### `clear`, `cls`

Clears the screen. Without an ANSI terminal it says so and does nothing.

### `config`

| Subcommand | Effect |
|---|---|
| `config get <key>` | Shows a value of `cloud.yml`. |
| `config set <key> <value>` | Changes it. The value is checked like a hand edit: types, patterns, ranges, the whole-configuration rules (ports, EULA before `setup-complete`, TLS for a public API). On an error nothing is written. |
| `config list` | Shows every key with its value. |
| `config reload` | Reads every configuration file again; an invalid file keeps the current configuration and prints the errors. |

Keys: `network-name`, `language`, `eula-accepted`, `setup-complete`, `node-id`, `servers.<proxy|lobby|survival|creative|mod|end|database|minigame>`, `proxy.bind`, `proxy.port`, `proxy.bedrock-port`, `legacy-proxy.enabled`, `legacy-proxy.port`, `database.mode`, `database.host`, `database.port`, `database.name`, `database.user`, `database.docker-image`, `api.enabled`, `api.bind`, `api.port`, `api.tls.enabled`, `api.tls.keystore`.

Values follow the file rules: `true` or `false` only, whole numbers, enum values in lower case. `""` clears `database.docker-image` and `api.tls.keystore`. Secrets are not in `cloud.yml`; they stay in `secrets/vectora.env`.

### `group`

| Subcommand | Effect |
|---|---|
| `group list` | Every group with template, software, version and whether its server kind is wanted. |
| `group info <name>` | All fields of one group. |
| `group create <name> <template> <software> <minecraft-version>` | New `groups/<name>.yml` with `java: auto`, heap 512M to 1G, combat `legacy` for `pandaspigot`, otherwise `modern`. Fails if the group exists. |
| `group set <name> <field> <value>` | Fields: `template`, `software`, `minecraft-version`, `java`, `heap.min`, `heap.max`, `combat`. |
| `group delete <name>`, `group remove <name>` | Deletes the file. Fails while a task uses the group. |
| `group wanted <name> <on\|off>` | Switches the server kind of the group in `cloud.yml` (`servers:`). |

### `task`

| Subcommand | Effect |
|---|---|
| `task list` | Every task with group, mode and service range. |
| `task info <name>` | All fields of one task. |
| `task create <name> <group> [static\|dynamic]` | New `tasks/<name>.yml` with one service on any node. The group must exist. |
| `task set <name> <field> <value>` | Fields: `group`, `mode`, `min-services`, `max-services`, `players-per-service`, `scale-up-at`, `scale-down-after`, `cooldown`, `nodes` (comma list, `""` for any node). |
| `task delete <name>`, `task remove <name>` | Deletes the file. |
| `task wanted <name> <on\|off>` | Switches the server kind of the task's group. |

`wanted` is stored per server kind, not per group: a group belongs to the kind whose template folder it uses (`Lobby-Server` is `lobby`). Switching it affects every group with that template. A group with another template has no switch; the command says so.

The catalog of a group (plugins and mods) is edited in the file for now.

Service commands (`start`, `stop`, `screen`, ...) come from cloud-node, which registers them in the same registry.

## Adding commands

```java
console.commands().register(Command.builder("service")
        .description(Text.of("Manage services.", "Dienste verwalten."))
        .subcommand(Command.builder("stop")
                .description(Text.of("Stops a service.", "Stoppt einen Dienst."))
                .argument(Argument.required("service", Text.of("Service id", "Dienst-ID"))
                        .suggest(previous -> services.ids()))
                .executes(context -> services.stop(context.argument("service"))))
        .build());
```

- Names are lower case letters, digits and `-`. A name or alias can belong to one command only.
- Arguments are positional. Optional ones come last; `takingRest()` collects the remaining words.
- An executor throws `CommandException` for a user mistake and lets `ConfigException` and `PromptCancelledException` through. Anything else is logged and shown as an internal error, without the exception message.
- `sensitive()` keeps every line of a command out of the history. Use it for any command that takes a secret as an argument; better still, ask for the secret with the question API.
- `Console.logHandler(redactor)` gives a `java.util.logging` handler that prints log records above the prompt; pass `RedactingFilter::redact` from cloud-config as redactor.

## Question API

`Prompter` asks `Question`s. The setup assistant uses it.

```java
Prompter ask = console.prompter();
String name = ask.ask(Question.text(Text.of("Network name", "Netzwerkname")).defaultValue("Vectora"));
int port = ask.ask(Question.number(Text.of("Proxy port", "Proxy-Port"), 1, 65535).defaultValue(25565));
boolean eula = ask.ask(Question.yesNo(Text.of("Accept the Minecraft EULA?", "Minecraft-EULA akzeptieren?")));
DatabaseMode mode = ask.ask(Question.choice(Text.of("Database", "Datenbank"), List.of(
        Question.option("managed", Text.of("Managed MariaDB", "Verwaltete MariaDB"), DatabaseMode.MANAGED),
        Question.option("external", Text.of("External server", "Externer Server"), DatabaseMode.EXTERNAL))));
Secret password = ask.ask(Question.secret(Text.of("Database password", "Datenbank-Passwort"))
        .validate(s -> s.length() >= 12 ? null : Text.of("At least 12 characters.", "Mindestens 12 Zeichen.")));
```

| Kind | Accepts |
|---|---|
| `text` | Any text without control characters; surrounding spaces are removed. |
| `number(min, max)` | A whole number in the range. |
| `yesNo` | `y`, `yes`, `j`, `ja`, `n`, `no`, `nein`, any case. |
| `choice` | The number in the list or the option key, any case. |
| `secret` | Any text, typed masked (`*`), returned as `Secret`. Never stored in the history, never shown, not even as default. |
| `custom(parser, display)` | Whatever the parser accepts; it throws `InvalidAnswerException` otherwise. |

- `defaultValue` is taken on Enter. A secret default shows "Enter keeps the current value".
- `validate` adds checks; a check returns null or a `Text` that says what is expected. Error messages never repeat the answer.
- `help` prints a line before the question.
- An invalid answer prints the problem and asks again, at most 10 times in a row.
- Ctrl+C, Ctrl+D, the end of stdin or `!cancel` throw `PromptCancelledException`. Inside a command, the console prints "Cancelled."
