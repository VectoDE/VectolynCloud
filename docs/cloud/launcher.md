# VectoraCloud launcher

`VectoraCloud/cloud-launcher` is the entry point of `java -jar VectoraCloud.jar`. It runs a few checks, unpacks the node and its libraries, and hands over to the node. It has no third-party dependencies and never references a node class at compile time.

## Start order

The order is fixed. Each step runs only if the one before it passed.

1. **Java version.** `Bootstrap` (the `Main-Class`) reads `java.specification.version`. Below 21 it prints a German or English message with the version and path of the running Java and where to get Java 21, then exits with code 3.
2. **Root or administrator.** See [below](#root-and-administrator-check). Refused starts exit with code 2.
3. **Arguments.** See [Arguments](#arguments). Mistakes exit with code 64 and print the help.
4. **Node.** The embedded libraries are unpacked to `<home>/cache/libs` and checked, then `net.vectora.cloud.node.NodeMain` is loaded by name. See [Embedded libraries](#embedded-libraries) and [Node entry point](#node-entry-point).

Step 2 needs `--allow-root` and `--lang` before step 3 parses the arguments, so it reads just those two from the raw command line. A root start with a broken argument therefore reports the root problem first.

### Why the bootstrap class is compiled for Java 8

Every Vectora module is built with `--release 21` ([Java compatibility](../java-compatibility.md)). A Java 8 to 20 runtime cannot even load such a class; it stops with `UnsupportedClassVersionError` and a class file version number. To print a readable message instead, the one class the JVM loads first, `net.vectora.cloud.launcher.Bootstrap`, lives in its own source set `bootstrap` and is compiled with `--release 8`. It uses no Java 9+ API and calls `Launcher.main` by reflection once the version is fine. `BootstrapTest` checks that its class file has major version 52. This is the only class with a release target other than 21. The build file switches off javac's "release 8 is obsolete" lint for this source set only, because `-Werror` would otherwise fail on it.

## Root and administrator check

VectoraCloud starts servers and downloads and runs software. With root or administrator rights, a bug or a tampered download would affect the whole machine, so the launcher refuses such a start.

| System | Counts as elevated | Sources, in order |
|---|---|---|
| Linux, macOS, other Unix | UID 0 | `com.sun.security.auth.module.UnixSystem` (JDK module `jdk.security.auth`, by reflection); effective UID from the `Uid:` line of `/proc/self/status`; `/usr/bin/id -u`, or `/bin/id -u` if the former does not exist |
| Windows | Integrity level High (`S-1-16-12288`) or higher, e.g. System (`S-1-16-16384`) | Token groups from `com.sun.security.auth.module.NTSystem` (same module, by reflection); `%SystemRoot%\System32\whoami.exe /groups /fo csv /nh` |

**Why the integrity level on Windows.** An account in the Administrators group gets a filtered token in a normal console (integrity Medium, `S-1-16-8192`), and only "Run as administrator" gives it the full token (High). The Administrators SID `S-1-5-32-544` is no good signal: the filtered token still lists it, as a deny-only group, so checking it would refuse every admin account even in a normal console. The integrity label is exactly what "elevated" means.

**Why `NTSystem` first.** It reads the process token in-process: no child process, no dependency on `whoami.exe` being on the `PATH`, and no localized output. Checked on Windows 11: in an elevated console its group list contains `S-1-16-12288`, the same as `whoami /groups`. `whoami` stays as a fallback for runtimes built without `jdk.security.auth` (custom `jlink` images); its group names are localized, so only the SIDs are parsed. Other dependency-free options were weaker: `net session` and `fltmc` only report success or failure, depend on services being started and cannot tell High from System. JNA or a native helper would add a dependency.

The fallback commands are started by absolute path and never looked up on the `PATH`, so a planted `id` or `whoami` earlier on the `PATH` cannot answer the check. A UID or integrity level that does not fit into a number counts as "no answer".

If no source works, the launcher prints a boxed `WARNING` (German: `WARNUNG`) and continues: the rights could not be determined, and anyone who started it as administrator or root should stop it right away and start it again as a normal user. Refusing in that case would lock out normal users on unusual systems.

**Override.** `--allow-root` or the environment variable `VECTORA_ALLOW_ROOT=true` (only `true`, any case) allow an elevated start. A boxed warning is printed every time. The node receives `root-allowed=true`.

**Message.** In German or English (`--lang`, otherwise `user.language`), with instructions for the current system:

- Windows: a normal Command Prompt or PowerShell instead of "Run as administrator"; as a service, a dedicated account instead of LocalSystem.
- Linux and macOS: a dedicated user (`useradd --system --create-home vectora`, `sudo -u vectora java -jar VectoraCloud.jar`), systemd with `User=` and `Group=`, Docker with `USER 1000` or `docker run --user 1000:1000`.

## Arguments

| Argument | Meaning |
|---|---|
| `--home <path>` | Cloud home. Default: the working directory. |
| `--non-interactive` | Never ask on the console (Docker, Pterodactyl, systemd). |
| `--setup-file <path>` | Answers for the setup assistant. |
| `--lang de\|en` | Language of the messages. Default: `user.language`, German stays German, everything else is English. |
| `--allow-root` | Allow a start with root or administrator rights. |
| `--help` | Print the help and exit. |
| `--version` | Print the version and exit. |

Values may follow as the next argument or after `=` (`--home=/data`). Each option may appear once. Unknown arguments, positional arguments, missing or invalid values and switches with a value are errors. The parser is hand-written; seven options do not justify a library in the one module that has to load before everything else.

## Platform detection

`PlatformDetector` produces a `Platform` value: operating system, architecture (`x86_64`, `aarch64` or the raw `os.arch`), container, Pterodactyl and terminal. The setup assistant will use it, for example to skip the Docker database mode inside Pterodactyl or to ask nothing without a terminal.

- **Container:** `/.dockerenv` (Docker), `/run/.containerenv` (Podman), or `docker`, `containerd`, `kubepods` or `libpod` in `/proc/1/cgroup`. Under cgroup v2 with a private cgroup namespace the cgroup file only shows `0::/`, so the marker files are the main signal there.
- **Pterodactyl:** checked against the sources. Wings sets `TZ`, `STARTUP`, `SERVER_MEMORY`, `SERVER_IP` and `SERVER_PORT` in every server container (`server/server.go`, `GetEnvironmentVariables`). The Panel adds `P_SERVER_UUID`, `P_SERVER_LOCATION` (`app/Services/Servers/EnvironmentService.php`) and `P_SERVER_ALLOCATION_LIMIT` (`config/pterodactyl.php`). `P_SERVER_UUID` alone counts. Without it, `STARTUP`, `SERVER_MEMORY` and `SERVER_PORT` must all be set, so a lone `SERVER_PORT` on a normal host does not trigger it. A Pterodactyl server also counts as a container.
- **Terminal:** `System.console()` is not null; on Java 22+ additionally `Console.isTerminal()` (called by reflection, because from Java 22 on `System.console()` can return a console without a terminal).

All reads go through the `SystemAccess` interface. The tests replace it and replay the output of a root shell, an elevated or normal Windows console, Docker and Pterodactyl containers.

## Embedded libraries

`VectoraCloud.jar` is assembled by the task `:VectoraCloud:cloud-launcher:cloudJar` (part of `assemble`, output `VectoraCloud/cloud-launcher/build/cloud/VectoraCloud.jar`):

```
net/vectora/cloud/launcher/...         launcher classes (release 21) and Bootstrap (release 8)
META-INF/vectora/libs.index            index, see below
META-INF/vectora/libs/*.jar            cloud-node, the other cloud modules and every runtime library, unchanged
META-INF/MANIFEST.MF                   Main-Class: net.vectora.cloud.launcher.Bootstrap, Implementation-Version
```

`libs.index` is written by the task `embeddedLibrariesIndex` from the launcher's runtime class path. One line per jar, the layout of `sha256sum`:

```
# VectoraCloud embedded libraries, format 1: <sha256>  <file name>
b2fe0161…  cloud-node-0.1.0-SNAPSHOT.jar
```

At startup the launcher:

1. parses the index; file names must match `[A-Za-z0-9][A-Za-z0-9._+-]{0,199}\.jar`, so no separators, no `..`, no absolute paths;
2. resolves each name in `<home>/cache/libs` and checks that the result is directly inside that directory (zip slip, a second check independent of the name rule);
3. reuses a jar already in the cache only if its SHA-256 matches; otherwise unpacks it to a temporary file, hashes it while writing, and moves it into place only if the hash matches. A wrong hash stops the start with code 4 and leaves no file behind;
4. deletes jars in `cache/libs` that the index no longer lists (older versions);
5. creates a `URLClassLoader` named `vectora-node` over the checked jars, with the launcher's class loader as parent, and sets it as context class loader while calling the node.

Without `libs.index` on the class path (IDE, Gradle tests) the launcher's own class loader is used.

### Why not a shaded jar

The usual way is a fat jar built with the Shadow plugin, which merges all classes into one jar. The launcher keeps the libraries as separate, unchanged jars instead:

- **Verifiable.** Each library keeps its published bytes, so its SHA-256 can be compared with Maven Central and is checked on every start. A merged jar can only be checked as a whole.
- **Nothing to merge.** No conflicts between `META-INF/services` files, `module-info.class`, multi-release `META-INF/versions/` trees, license and notice files, or signature files of signed jars. Shadow needs transformer rules for each of these and fails silently when one is missing.
- **Checks before loading.** The launcher classes are the only code on the application class path. Nothing of the node or its libraries is loaded before the Java, root and argument checks have passed.
- **No third-party build plugin.** One supply-chain dependency less in the build; the packing is a few lines in `cloud-launcher/build.gradle.kts`.

The cost: the first start writes the libraries to `cache/libs` (about 7 MB with the stage-2 scaffold), and code that relies on the system class loader instead of the context class loader does not see the node's classes.

## Node entry point

The node provides:

```java
package net.vectora.cloud.node;

public final class NodeMain {
    public static void start(java.util.Map<String, String> settings) { ... }
}
```

`start` may return after starting its own non-daemon threads; the JVM keeps running until they end. If it throws, the launcher prints the cause and exits with code 1. If the class is missing (as in builds before the node exists), the launcher says so and exits with code 5.

The settings are strings because the node cannot depend on the launcher (the dependency points the other way):

| Key | Value |
|---|---|
| `home` | absolute, normalized cloud home |
| `language` | `de` or `en` |
| `non-interactive` | `true` or `false` |
| `setup-file` | absolute path; only present with `--setup-file` |
| `root-allowed` | `true` if an elevated start was allowed |
| `launcher.version` | version from the manifest, `dev` outside the jar |
| `platform.os` | `windows`, `linux`, `macos` or `other` |
| `platform.arch` | `x86_64`, `aarch64` or the raw `os.arch` |
| `platform.docker` | `true` inside a container |
| `platform.pterodactyl` | `true` on Pterodactyl |
| `platform.terminal` | `true` with an interactive terminal |

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Node started, or `--help` / `--version` |
| 1 | The node threw while starting |
| 2 | Started as root or administrator without `--allow-root` / `VECTORA_ALLOW_ROOT=true` |
| 3 | Java older than 21 |
| 4 | Embedded library missing, unsafe name, or wrong SHA-256 |
| 5 | No node entry point in this build, or an invalid one |
| 64 | Unknown or invalid argument (`EX_USAGE`) |
