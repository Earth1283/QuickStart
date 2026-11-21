# QuickStart

QuickStart provides homes, warps, server spawn, and player teleport requests for Paper and Folia. Version 0.1 targets Minecraft 26.1.2 and Java 25.

## Install

Run `./gradlew build` and copy `build/libs/QuickStart-0.1.0.jar` into the server's `plugins` directory. QuickStart creates `plugins/QuickStart/config.yml` and stores saved locations in `plugins/QuickStart/quickstart.db`. Use `--enable-native-access=ALL-UNNAMED` in the server's Java arguments to enable SQLite's native library access on Java 25.

| Startup setting | Behavior |
| --- | --- |
| `ADHD` | Starts required services, then prepares enabled features in the background. By default, new background work pauses while players are online. |
| `LAZY` | Starts required services and prepares each enabled feature on its first use. |
| `ALL_AT_ONCE` | Prepares every enabled feature before accepting logins. Players who connect during startup can retry once the server is ready. |

Use `/qs modules` to inspect module states and `/qs doctor` to check configuration and storage. If required storage fails, `ADHD` and `LAZY` continue to serve features that are independent of SQLite. `ALL_AT_ONCE` rejects logins until the startup error is resolved and the server is restarted.

## Commands

| Commands | Behavior |
| --- | --- |
| `/sethome [name]`, `/home [name]`, `/delhome [name]`, `/homes` | Manage personal homes. The default name is `home` and the default limit is five. |
| `/setwarp <name>`, `/warp <name>`, `/delwarp <name>`, `/warps` | Manage and use shared destinations. Warp creation and deletion require `quickstart.warps.manage`. |
| `/setspawn`, `/spawn` | Set or visit the server spawn. `/spawn` falls back to the primary world's spawn until an operator sets one. Setting spawn requires `quickstart.spawn.manage`. |
| `/tpa <player>`, `/tpahere <player>` | Request a teleport to a player or invite them to teleport to you. Each player can have one outgoing request at a time. |
| `/tpaccept [player]`, `/tpdeny [player]` | Respond to a request. Omit the name to respond to the most recent incoming request. |
| `/back` | Return to your previous teleport or death location during the current session. |
| `/qs`, `/qs help` | List available commands. |
| `/qs modules`, `/qs doctor`, `/qs diagnostics startup`, `/qs reload` | Inspect readiness and timings or reload settings. These subcommands require `quickstart.admin`. |

Home and warp names are case-insensitive and accept up to 32 letters, digits, underscores, or hyphens. Optional warp visibility uses `quickstart.warp.<name>` and `quickstart.warps.bypass`. Enable `spawn.first-join` to send new players to the configured spawn when they join. The default request expiration is 60 seconds and the default cooldown for outgoing requests and request teleports is five seconds.

`/qs reload` applies home limits, warp visibility, first-join spawn, teleportation settings, and messages. Startup strategy, module selection, and warmup settings take effect on restart. Invalid settings leave the current configuration in effect and appear in `/qs doctor`.

## Messages

QuickStart creates `plugins/QuickStart/message.yml` on startup. Edit it to customize command replies, teleport notices, and login messages using MiniMessage tags such as `<gold>Welcome</gold>`. Keep placeholders such as `<name>` and `<player>` where you want QuickStart to insert their values. Missing entries use the bundled defaults.

## Storage and plugin API

QuickStart queues SQLite operations on a dedicated worker. An acknowledged save or delete has committed. Accepted writes finish during orderly shutdown and can still commit if the issuing player disconnects. A full queue rejects new work. Retrying a home, warp, or spawn save replaces the destination at the same name. Use `/qs doctor` to inspect storage failures and check the destination before repeating an operation with an uncertain result.

Plugins can depend on `QuickStart` and use the shaded JAR as a compile-only dependency. Load `io.github.Earth1283.quickStart.api.QuickStartApi` through Bukkit's `ServicesManager` after QuickStart enables. `getReadiness()` and `modules()` are safe to call from any thread. They return status snapshots and perform no disk operations. `DEGRADED` means available modules can still serve requests and `FAILED` means startup prevented feature execution. The service is unregistered during shutdown.

See [Server verification](verification/README.md) for the integration suite and profiling workload.

Licensed under [AGPL-3.0-or-later](LICENSE).
