# QuickStart

QuickStart provides homes, warps, server spawn, player teleport requests, and moderation for Paper and Folia. Version 0.1 targets Minecraft 26.1.2 and Java 25.

## Install

Run `./gradlew build` and copy `build/libs/QuickStart-0.1.0.jar` into the server's `plugins` directory. QuickStart creates `plugins/QuickStart/config.yml` and stores saved locations in `plugins/QuickStart/quickstart.db`. Use `--enable-native-access=ALL-UNNAMED` in the server's Java arguments to enable SQLite's native library access on Java 25.

| Startup setting | Behavior |
| --- | --- |
| `ADHD` | Starts required services, then prepares enabled features in the background. By default, new background work pauses while players are online. |
| `LAZY` | Starts required services and prepares each enabled feature on its first use. |
| `ALL_AT_ONCE` | Prepares every enabled feature before accepting logins. Players who connect during startup can retry once the server is ready. |

Use `/qs modules` to inspect module states and `/qs doctor` to check configuration and storage. With moderation enabled, QuickStart requires the moderation database to load before admitting players. With moderation disabled, `ADHD` and `LAZY` can serve features that are independent of SQLite after a storage failure. `ALL_AT_ONCE` rejects logins until the startup error is resolved and the server is restarted.

QuickStart colors startup messages automatically in an interactive terminal and honors the server's `terminal.ansi` setting. A nonempty `NO_COLOR` or a `TERM` of `dumb` or `unknown` disables QuickStart's startup colors.

## Commands

| Commands | Behavior |
| --- | --- |
| `/sethome [name]`, `/home [name]`, `/delhome [name]`, `/homes [page]` | Manage personal homes. The default name is `home` and the default limit is five. |
| `/setwarp <name>`, `/warp <name>`, `/delwarp <name>`, `/warps [page]` | Manage and use shared destinations. Warp creation and deletion require `quickstart.warps.manage`. |
| `/setspawn`, `/spawn`, `/bedspawn [on\|off\|default\|status]` | Set or visit the server spawn, or override whether your bed or respawn anchor takes precedence when `spawn.on-respawn` is enabled. Setting spawn requires `quickstart.spawn.manage`; bed overrides use `quickstart.spawn.bedoverride`. |
| `/tpa <player>`, `/tpahere <player>` | Request a teleport to a player or invite them to teleport to you. Each player can have one outgoing request at a time. |
| `/tprequests [page]` | Browse incoming teleport requests, newest first. |
| `/tpaccept [player]`, `/tpdeny [player]` | Respond to a request. Omit the name to respond to the most recent incoming request. |
| `/back` | Return to your previous teleport or death location during the current session. |
| `/safeteleport [on\|off\|default\|status]` (`/safetp`) | Toggle destination safety validation (hazards, suffocation, world bounds, and unsafe drops) for your own teleports (`quickstart.teleport.safetoggle`). |
| `/seen <player\|uuid>` | Show whether a player is online or when they were last seen (`quickstart.seen`). Staff with `quickstart.seen.details` also see UUID, IP, location, active sanctions, and moderation links. |
| `/qs`, `/qs help` | List available commands. |
| `/qs modules`, `/qs doctor`, `/qs diagnostics startup`, `/qs reload` | Inspect readiness and timings or reload settings. These subcommands require `quickstart.admin`. |

Home and warp names are case-insensitive and accept up to 32 letters, digits, underscores, or hyphens. Click a destination in `/homes` or `/warps` to teleport to it. Each page shows up to five destinations. Use Previous and Next or type a page number. Save, delete, and reload buttons fill in the command for you to review before sending. Optional warp visibility uses `quickstart.warp.<name>` and `quickstart.warps.bypass`. Enable `spawn.first-join` to send new players to the configured spawn when they join.

Incoming requests show when they expire and have clickable Accept and Deny buttons. Each button applies to that specific invitation. Hover over Accept to see where the teleport will go. Use `/tprequests` to find an invitation again and see its remaining time. Requests expire after 60 seconds by default. Enable `teleportation.strict-tpahere` to send recipients to the location where the invitation was made. By default, recipients go to the sender's location when they accept.

Home, warp, spawn, request sending, request teleporting, and `/back` each have an independent cooldown. Outgoing requests, request teleports, and `/back` default to five seconds through `teleportation.cooldown-seconds`. The optional `request-send-cooldown-seconds`, `request-teleport-cooldown-seconds`, and `back-cooldown-seconds` settings override that default under `teleportation`.

`/home`, `/warp`, `/spawn`, `/back`, and accepted teleport requests show a three-second action bar countdown. Moving or taking damage cancels the countdown. Players can look around while they wait. Set `teleportation.warmup-seconds` to `0` for immediate teleports. Safe teleport validation (`teleportation.safe-teleport`, enabled by default) checks destinations on the owning region for hazards, suffocation, bounds, and unsafe drops before teleporting; individual players with `quickstart.teleport.safetoggle` can override it with `/safeteleport`. When `spawn.on-respawn` is enabled, `spawn.respect-bed-spawn` (enabled by default) keeps personal beds and respawn anchors unless a player overrides it with `/bedspawn`. Automatic join and respawn placement is immediate.

`/qs reload` applies home limits, warp visibility, first-join spawn, teleportation settings, and messages. Startup strategy, module selection, and `performance.warmup` settings take effect on restart. Invalid settings leave the current configuration in effect and appear in `/qs doctor`.

## Tab completion

Press Tab after a QuickStart command to see matching saved homes, visible warps, online players, pending request senders, page numbers, or available `/qs` subcommands. Completions reflect current permissions and use saved names already in memory.

## Moderation

| Commands | Target |
| --- | --- |
| `/ban`, `/tempban <player> <duration> <reason>`, `/unban` | Player name or UUID. |
| `/ipban` (`/banip`), `/tempipban`, `/unbanip` | IPv4, IPv6, or an online player's name. A name resolves to their current IP address. |
| `/iprangeban`, `/tempiprangeban`, `/unbaniprange` | An IPv4 or IPv6 CIDR network such as `192.0.2.0/24` or `2001:db8::/32`. |
| `/mute`, `/tempmute`, `/unmute` | Player name or UUID. |
| `/ipmute`, `/tempipmute`, `/unmuteip` | IPv4, IPv6, or an online player's name. |
| `/kick <player> [reason]` | An online player. |
| `/banstatus <target>`, `/mutestatus <target>` | Active sanctions. An online account lookup also checks its current address. An IP ban lookup includes matching CIDR bans. |
| `/modhistory <target> [page]` | QuickStart actions for the specified account, IP, or network, newest first. |
| `/alts <player\|uuid\|ip> [page]` | Accounts that share recorded IP addresses with the specified player or IP, with online and sanction status. |

Add an expiration immediately after the target for temporary commands. Use a duration like `1h30m` or a UTC deadline like `2027-01-08 18:30`. Seconds are optional for a deadline. Press Tab for duration presets, calendar dates, times, and relevant targets. In long date formats, choose the date, type a space, and press Tab again for the time.

Add a reason after the target or expiration. The reason is required for `/tempban` and optional for other sanctions. Use MiniMessage formatting such as `<red>Repeated griefing</red><newline><gray>Appeal on our forums.</gray>` for styled, multiline notices. Enter `<usedialogue>` as the entire reason to open the multiline editor from a Minecraft Java 1.21.6 or newer client. The editor applies the action after you confirm. Staff can type the reason directly in the command, including from the console. Set the notice templates in `message.yml` to customize the surrounding text.

Use `/qs mod <player>` to open the moderation menu on a compatible client. Choose an action, enter an expiration if the action is temporary, and write a multiline reason before confirming. You can also open the menu with an IP or CIDR target.

Status and History buttons open the corresponding view. Unban and Unmute buttons fill in the exact command for review before sending. QuickStart keeps bans, mutes, kicks, and successful reversals in its SQLite history. Mutes cover public chat and common messaging commands such as `/msg`, `/tell`, `/me`, and `/teammsg`.

Operators have `quickstart.moderation` by default. Every command has its own `quickstart.moderation.<command>` permission. For example, `.tempban` allows temporary bans and `.unban` allows lifting account bans. Grant `quickstart.moderation.inspect` to allow all three inspection commands. Use `/quickstart:ban` or `/quickstart:kick` on servers where vanilla or another plugin handles the short name. Account moderation supports online players, names in the server's profile cache, and explicit UUIDs. Disable the suite with `modules.moderation: false` and restart.

## Messages

QuickStart creates `plugins/QuickStart/message.yml` on startup. Edit it to customize command replies, teleport notices, and login messages using MiniMessage tags such as `<gold>Welcome</gold>`. Keep placeholders such as `<name>` and `<player>` where you want QuickStart to insert their values. Missing entries use the bundled defaults. Existing entries remain overrides after an update. To adopt a refreshed default, remove the corresponding entry and run `/qs reload`.

## Storage and plugin API

QuickStart queues SQLite operations on a dedicated worker. An acknowledged save or delete has committed. Accepted writes finish during orderly shutdown and can still commit if the issuing player disconnects. A full queue rejects new work. Retrying a home, warp, or spawn save replaces the destination at the same name. Use `/qs doctor` to inspect storage failures and check the destination before repeating an operation with an uncertain result.

Plugins can depend on `QuickStart` and use the shaded JAR as a compile-only dependency. Load `io.github.Earth1283.quickStart.api.QuickStartApi` through Bukkit's `ServicesManager` after QuickStart enables. `getReadiness()` and `modules()` are safe to call from any thread. They return status snapshots and perform no disk operations. `DEGRADED` means available modules can still serve requests and `FAILED` means startup prevented feature execution. The service is unregistered during shutdown.

See [Server verification](verification/README.md) for the integration suite and profiling workload.

Licensed under [AGPL-3.0-or-later](LICENSE).
