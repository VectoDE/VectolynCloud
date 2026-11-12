# Module reference

One entry per Gradle module (see `settings.gradle.kts`). "Project dependencies" lists the modules a module declares in its `build.gradle.kts`; transitive ones are not repeated. The dependency rules behind the graph are in [architecture](../cloud/architecture.md#dependency-graph). The reference page of a module is the authority for its behaviour; this page only tells you where to look.

None of the modules is published as a library. The classes below are the entry points inside the code base, not a stable API for third parties. Stable interfaces for outside use are the [REST and WebSocket API](../cloud/api.md) ([OpenAPI file](../cloud/api.openapi.yaml)), the [cloud channel protocol](../cloud/protocol.md), the [configuration files](../cloud/config.md) and the console commands.

## Shared by the cloud and its servers

These modules run inside proxies and servers. They stay free of Netty, Jackson and JLine on purpose.

| Module | Package | Entry types | Project dependencies | Reference |
|---|---|---|---|---|
| `common/protocol` | `net.vectolyn.protocol` | `Protocol`, `ProtocolVersion`, `FrameCodec`, `Frame`; messages in `message` (`Hello`, `Ping`, `ServiceStatus`, `RegisterServer`, `TransferPlayer`, `NetworkKick` and more) with `MessageRegistry` and `MessageCodec` | none | [protocol](../cloud/protocol.md) |
| `common/client` | `net.vectolyn.cloud.client` | `CloudClient`, `CloudClientSettings`, `CloudClientListener`, `Backoff` | `common/protocol` | [network](../cloud/network.md#client-cloudclient) |

## Cloud

| Module | Package | Entry types | Project dependencies | Reference |
|---|---|---|---|---|
| `cloud/config` | `net.vectolyn.cloud.config` | `ConfigService` (the one write path), `CloudHome`, `ConfigStore`, `ConfigMigration`; model in `model` (`CloudConfig`, `GroupConfig`, `TaskConfig`, `NodesConfig`), changes in `operation` (`PutGroup`, `SetLanguage`, `CompleteSetup` and more) | none | [config](../cloud/config.md) |
| `cloud/template` | `net.vectolyn.cloud.template` | `TemplateRenderer`, `TemplateInstaller`, `PlaceholderRenderer`, `PlaceholderValues`, `RenderReport` | none | [template](../cloud/template.md) |
| `cloud/download` | `net.vectolyn.cloud.download` | `ArtifactDownloader`, `ArtifactCatalog`, `SoftwareLockStore`, `SafeExtractor`, `HostAllowlist`, `JreManager` (in `jre`) | none | [download](../cloud/download.md) |
| `cloud/console` | `net.vectolyn.cloud.console` | `Console`, `CommandRegistry`, `Command`, `CommandDispatcher`, `Prompter` and `Question` (question API), `BuiltinCommands` | `cloud/config` | [console](../cloud/console.md) |
| `cloud/network` | `net.vectolyn.cloud.network` | `CloudNetworkServer`, `NetworkSettings`, `ServiceTokens`, `ConnectionLimiter`, `ChannelListener` | `common/protocol`, `cloud/config` | [network](../cloud/network.md) |
| `cloud/api` | `net.vectolyn.cloud.api` | `ApiServer`, `ApiSettings`, `ApiBackend` (the node implements it), `ApiTokens`, `Scope`, `EventHub`, `Router` | `cloud/config`, `cloud/console`, `cloud/backup` | [api](../cloud/api.md) |
| `cloud/database` | `net.vectolyn.cloud.database` | `DatabaseProvisioner` and its implementations (managed MariaDB, Docker, external, H2), `ConnectionBudget`, `DatabaseEndpoint` | `cloud/config`, `cloud/download` | [database](../cloud/database.md) |
| `cloud/setup` | `net.vectolyn.cloud.setup` | `SetupWizard`, `InteractiveSetup`, `SetupFile`, `SetupAnswers`, `SetupApplier`, `SetupChecks` | `cloud/console`, `cloud/database` | [setup](../cloud/setup.md) |
| `cloud/monitor` | `net.vectolyn.cloud.monitor` | `Monitor`, `AlertEngine`, `AlertRule`, `WebhookNotifier`, `PrometheusEndpoint`, `HistoryStore` | `cloud/config`, `common/protocol`, `cloud/console` | [monitor](../cloud/monitor.md) |
| `cloud/backup` | `net.vectolyn.cloud.backup` | `BackupService`, `BackupScheduler`, `BackupManifest`, `Retention`, `ServiceControl` (provided by the node) | `cloud/config`, `cloud/console`, `cloud/download` | [backup](../cloud/backup.md) |
| `cloud/cluster` | `net.vectolyn.cloud.cluster` | `ClusterService`, `ClusterCa`, `JoinTokens`, `ClusterServer`, `MirrorClient`, `VersionPolicy`, `ClusterCommands` | `cloud/config`, `common/protocol`, `cloud/console`, `cloud/network` | [cluster](../cloud/cluster.md) |
| `cloud/node` | `net.vectolyn.cloud.node` | `NodeMain` (entry point), `CloudNode`, `service.ServiceManager`, `service.ServicePlanner`, `scaling.Autoscaler`, `channel.ChannelBridge`, `software.SoftwareInstaller`, `ops.NodeApi` | all cloud modules above except the launcher; embeds the three bridge jars | [node](../cloud/node.md), [scaling](../cloud/scaling.md) |
| `cloud/launcher` | `net.vectolyn.cloud.launcher` | `Bootstrap` (`Main-Class`, Java 8 bytecode), `Launcher`, `ArgumentParser`, `RootGuard`, `LibraryExtractor`, `PlatformDetector`, `NodeStarter` | `cloud/node` | [launcher](../cloud/launcher.md) |

`cloud/launcher` builds `VectolynCloud.jar` (`:cloud:launcher:cloudJar`). The libraries are unpacked from the jar at start, see [launcher](../cloud/launcher.md#embedded-libraries).

## Plugins

| Module | Output | Entry types | Project dependencies | Reference |
|---|---|---|---|---|
| `plugins/bridge/common` | library | `ProxyBridge`, `CloudClientLink`, `ServerRegistry`, `PlayerTracker`, `KickClassifier`, `BungeeGuardCheck` | `common/client` | [bridges](../cloud/bridges.md) |
| `plugins/bridge/velocity` | `VectolynCloudBridgeVelocity` | `VelocityBridgePlugin` | `plugins/bridge/common` | [bridges](../cloud/bridges.md) |
| `plugins/bridge/paper` | `VectolynCloudBridgePaper` | `PaperBridgePlugin` | `plugins/bridge/common` | [bridges](../cloud/bridges.md) |
| `plugins/bridge/legacy` | `VectolynCloudBridgeLegacy` (PandaSpigot 1.8.8, Java 8 class files) | `LegacyBridgePlugin`, `HandshakeGuard` | `plugins/bridge/common` | [bridges](../cloud/bridges.md), [legacy 1.8.8](../cloud/legacy-1.8.8.md) |
| `plugins/lobby/paper` | `VectolynLobby` | `VectolynLobby`, `SpawnPolicy`, `BuildProtection` | none | [Server Network](https://github.com/VectoDE/VectolynCloud/wiki/Server-Network) |
| `plugins/portal/paper` | `VectolynPortalBridge` | `PortalBridge` | none | [Server Network](https://github.com/VectoDE/VectolynCloud/wiki/Server-Network) |

The bridge jars are embedded in `cloud/node` with their SHA-256 and copied into `plugins/` of each server per platform ([node](../cloud/node.md#server-software)). The lobby and portal plugins are small Paper plugins; the full lobby (stage 4) and the Vectolyn plugin (stage 3) are not built yet.

## Build tools

| Module | Purpose | Reference |
|---|---|---|
| `tools/release-banner` | Renders the image of every GitHub release and holds the tag rules (`ReleaseVersion`, `check-tag.sh`) | [operations](../cloud/operations.md#versions-and-tags) |
| `build-logic` (included build) | Convention plugins `vectolyn.java-conventions` and `vectolyn.java25-api-conventions` | [architecture](../cloud/architecture.md#build-setup) |
