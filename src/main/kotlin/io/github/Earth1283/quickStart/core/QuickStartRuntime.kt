package io.github.Earth1283.quickStart.core

import io.github.Earth1283.quickStart.api.ModuleState
import io.github.Earth1283.quickStart.api.QuickStartApi
import io.github.Earth1283.quickStart.api.Readiness
import io.github.Earth1283.quickStart.features.Homes
import io.github.Earth1283.quickStart.features.Moderation
import io.github.Earth1283.quickStart.features.ModerationCommand
import io.github.Earth1283.quickStart.features.Seen
import io.github.Earth1283.quickStart.features.Spawn
import io.github.Earth1283.quickStart.features.Teleportation
import io.github.Earth1283.quickStart.features.Warps
import io.github.Earth1283.quickStart.features.canSeeWarp
import io.github.Earth1283.quickStart.platform.BackHistory
import io.github.Earth1283.quickStart.platform.EntityUnavailable
import io.github.Earth1283.quickStart.platform.PlayerSession
import io.github.Earth1283.quickStart.platform.Players
import io.github.Earth1283.quickStart.platform.ServerScheduler
import io.github.Earth1283.quickStart.platform.TeleportResult
import io.github.Earth1283.quickStart.platform.Teleports
import io.github.Earth1283.quickStart.platform.snapshot
import io.papermc.paper.event.player.AsyncChatEvent
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

internal class QuickStartRuntime(
    private val plugin: JavaPlugin,
    private val configFile: File,
    internal val config: Configuration,
    private val configurationFailure: Throwable?,
    enabledAt: TimeSource.Monotonic.ValueTimeMark,
    initialConfigWarnings: List<String> = emptyList(),
    initialMissingConfigKeys: List<String> = emptyList(),
) : QuickStartApi, Listener, TabExecutor {
    private data class ReloadableSettings(val features: FeatureSettings, val messages: Messages)

    private val lifecycle = SupervisorJob()
    private val scope = CoroutineScope(lifecycle + Dispatchers.Default)
    private val scheduler = ServerScheduler(plugin)
    private val players = Players(scope)
    private val moderationEnabled = "moderation" in config.enabledModules
    private val backHistory = if ("teleportation" in config.enabledModules) BackHistory(players) else null
    private val teleports by lazy {
        Teleports(
            plugin.server,
            scheduler,
            backHistory,
            recordBack = { features.backOnTeleport },
            warmupSeconds = { features.teleportWarmupSeconds },
            safeTeleportEnabled = ::isSafeTeleportEnabled,
            render = { messages.render(it) },
        )
    }
    private val sqlite = lazy {
        SqliteStorage(plugin.dataFolder.toPath().resolve("quickstart.db"), config.maxPendingOperations) { report("SQLite shutdown", it) }
    }
    private val loaded = CompletableDeferred<Unit>()
    private val bootFinished = CompletableDeferred<Readiness>()
    private val state = AtomicReference(Readiness.STARTING)
    private val reloadLock = Mutex()
    private val messageFile = File(plugin.dataFolder, "message.yml")
    private val defaultMessages = checkNotNull(plugin.getResource(messageFile.name))
        .reader(Charsets.UTF_8).use(Messages::loadDefaults)
    private val colorStartupLogs = System.getenv("NO_COLOR").isNullOrEmpty() &&
        System.getenv("TERM")?.trim()?.lowercase(Locale.ROOT) !in setOf("dumb", "unknown") &&
        when (System.getProperty("terminal.ansi")?.lowercase(Locale.ROOT)) {
            "true" -> true
            "false" -> false
            else -> System.console()?.isTerminal == true
        }
    @Volatile private var settings = ReloadableSettings(config.features, defaultMessages)
    private val features get() = settings.features
    private val messages get() = settings.messages
    @Volatile private var configErrors: List<String> = extractConfigErrors(configurationFailure)
    @Volatile private var configWarnings: List<String> = initialConfigWarnings
    @Volatile private var missingConfigKeys: List<String> = initialMissingConfigKeys
    @Volatile private var messageProblem: String? = null
    @Volatile private var messageWarnings: List<String> = emptyList()
    @Volatile private var startupProblem: String? = null
    @Volatile private var lastDiskConfig: Configuration = config
    internal val startup = StartupMeasurements(enabledAt)
    internal val commands = LatencyMeasurements()
    private val administration = Administration(this)

    private val modules = Modules(scope, definitions(), scheduler::global) { id, failure ->
        report("Module $id", failure)
    }

    override val readiness: Readiness get() = state.get()
    override fun modules() = modules.statuses()

    private fun definitions(): List<ModuleDefinition> {
        val persistent = listOf("homes", "warps", "spawn", "seen")
        val storage = ModuleDefinition("storage", moderationEnabled || persistent.any(config.enabledModules::contains), critical = true) { sqlite.value }
        fun feature(id: String, create: () -> CommandModule) = ModuleDefinition(
            id = id,
            enabled = id in config.enabledModules,
            required = if (id in persistent) listOf("storage") else emptyList(),
        ) { create() }
        return listOf(
            storage,
            ModuleDefinition("moderation", moderationEnabled, critical = true, required = listOf("storage")) { moduleScope ->
                Moderation(
                    sqlite.value,
                    players,
                    scheduler,
                    plugin.server,
                    moduleScope,
                    { messages.render(it) },
                    ::submitDialog,
                    { features.moderation },
                    { plugin.logger.info(it) },
                )
            },
            feature("teleportation") { Teleportation(players, scheduler, teleports, checkNotNull(backHistory), ::storage) },
            feature("homes") { Homes(sqlite.value, teleports) },
            feature("warps") { Warps(sqlite.value, teleports) },
            feature("spawn") { Spawn(sqlite.value, teleports, plugin.server, scheduler) },
            feature("seen") { Seen(sqlite.value, players, scheduler, plugin.server, moderationEnabled) },
        )
    }

    fun start() {
        try {
            if (messageFile.exists()) {
                val loaded = defaultMessages.withOverridesWithDiagnostics(messageFile)
                settings = settings.copy(messages = loaded.messages)
                messageWarnings = loaded.warnings
                for (warning in loaded.warnings) {
                    plugin.componentLogger.warn(startupText(warning, NamedTextColor.GOLD))
                }
            } else {
                plugin.saveResource(messageFile.name, false)
            }
        } catch (failure: Exception) {
            messageProblem = message(failure)
            plugin.componentLogger.warn(startupText("Could not load message.yml. Using default messages.", NamedTextColor.GOLD), failure)
        }
        for (warning in configWarnings) {
            plugin.componentLogger.warn(startupText(warning, NamedTextColor.GOLD))
        }
        val server = plugin.server
        server.pluginManager.registerEvents(this, plugin)
        if (backHistory != null) {
            val listener = BackListener(backHistory, players) { features }
            backListener = listener
            server.pluginManager.registerEvents(listener, plugin)
        }
        for (id in listOf("qs") + FeatureCommand.byId.keys + ModerationCommand.byId.keys) {
            val command = checkNotNull(plugin.getCommand(id)) { "Missing command registration for $id" }
            command.setExecutor(this)
            command.tabCompleter = this
        }
        server.servicesManager.register(QuickStartApi::class.java, this, plugin, ServicePriority.Normal)
        startup.bootstrapMillis = startup.elapsedMillis()
        if (configurationFailure != null) {
            reportStartupFailure("Configuration", configurationFailure)
            finishBoot(Readiness.FAILED)
        } else {
            scope.launch { boot() }
        }
    }

    private suspend fun boot() {
        try {
            val criticalStarted = TimeSource.Monotonic.markNow()
            val criticalFailures = try {
                modules.criticalIds.mapNotNull { id ->
                    try {
                        modules.initialize(id)
                        null
                    } catch (failure: ModuleUnavailable) {
                        failure
                    }
                }
            } finally {
                startup.criticalMillis = criticalStarted.elapsedNow().inWholeMilliseconds
            }
            loaded.await()
            startupProblem = criticalFailures.firstOrNull()?.message
            if (config.startup == StartupStrategy.ALL_AT_ONCE) {
                criticalFailures.firstOrNull()?.let { throw it }
                for (id in modules.optionalIds) modules.initialize(id)
            }
            finishBoot(if (criticalFailures.isEmpty()) Readiness.READY else Readiness.DEGRADED)
            if (config.startup == StartupStrategy.ADHD && config.warmup.enabled && modules.optionalIds.isNotEmpty() &&
                maxOf(config.warmup.workers(0), config.warmup.workers(1)) > 0
            ) {
                val warmupStarted = TimeSource.Monotonic.markNow()
                warmModules(modules.optionalIds, config.warmup, players.count) { id ->
                    try {
                        modules.initialize(id)
                    } catch (_: ModuleUnavailable) {
                    }
                }
                startup.warmupMillis = warmupStarted.elapsedNow().inWholeMilliseconds
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModuleUnavailable) {
            startupProblem = message(failure)
            finishBoot(Readiness.FAILED)
        } catch (failure: Exception) {
            startupProblem = message(failure)
            reportStartupFailure("Startup", failure)
            finishBoot(Readiness.FAILED)
        }
    }

    private fun finishBoot(result: Readiness) {
        if (!state.compareAndSet(Readiness.STARTING, result)) return
        if (result == Readiness.READY) startup.readinessMillis = startup.elapsedMillis()
        bootFinished.complete(result)
        val statusColor = when (result) {
            Readiness.READY -> NamedTextColor.GREEN
            Readiness.DEGRADED -> NamedTextColor.GOLD
            Readiness.FAILED -> NamedTextColor.RED
            else -> NamedTextColor.GRAY
        }
        plugin.componentLogger.info(startupText("${config.startup} ", NamedTextColor.AQUA).append(
            startupText("startup ${result.name.lowercase(Locale.ROOT)} after ${startup.elapsedMillis()} ms", statusColor),
        ))
    }

    fun close() {
        if (state.getAndSet(Readiness.STOPPING) == Readiness.STOPPING) return
        bootFinished.complete(Readiness.STOPPING)
        HandlerList.unregisterAll(this)
        backListener?.let(HandlerList::unregisterAll)
        plugin.server.servicesManager.unregister(QuickStartApi::class.java, this)
        players.close()
        backHistory?.close()
        modules.close()
        lifecycle.cancel()
    }

    @EventHandler
    fun onServerLoaded(event: ServerLoadEvent) {
        loaded.complete(Unit)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        if (moderationEnabled) {
            val moderation = moderation()
            if (moderation == null) {
                val key = if (readiness == Readiness.STARTING) "login.starting" else "moderation.login-unavailable"
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.render(Message(key)))
                return
            }
            val ban = moderation.banNotice(event.uniqueId, event.address)
            if (ban != null) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, ban)
                return
            }
        }
        if (config.startup == StartupStrategy.ALL_AT_ONCE && readiness != Readiness.READY) {
            val key = if (readiness == Readiness.FAILED) "login.failed" else "login.starting"
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.render(Message(key)))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val session = players.joined(event.player) ?: return
        if (moderation()?.kickIfBanned(session) == true) return
        recordProfile { storage ->
            storage.recordPlayerJoin(session.id, session.name, session.ipString, session.joinedAt)
        }
        val spawnEnabled = "spawn" in config.enabledModules
        if (spawnEnabled && features.firstJoinSpawn && !event.player.hasPlayedBefore()) {
            session.submit { spawnTeleport(session) }
        } else if (spawnEnabled && features.spawnOnJoin) {
            session.submit { spawnTeleport(session) }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val session = players.session(event.player)
        val quitPosition = if (session != null) runCatching { event.player.location.snapshot() }.getOrNull() else null
        val quitAt = Instant.now()
        players.quit(event.player)
        if (session != null) {
            recordProfile { storage ->
                storage.recordPlayerQuit(session.id, session.name, quitAt, quitPosition)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val moderation = moderation() ?: return
        val session = players.session(event.player) ?: return
        val notice = moderation.muteNotice(session) ?: return
        event.isCancelled = true
        val actor = CommandActor(session.player, session, scheduler) { messages }
        scope.launch { actor.reply(notice) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChatCommand(event: PlayerCommandPreprocessEvent) {
        val moderation = moderation() ?: return
        if (!moderation.isMutedCommand(event.message)) return
        val session = players.session(event.player) ?: return
        val notice = moderation.muteNotice(session) ?: return
        event.isCancelled = true
        event.player.sendMessage(messages.render(notice))
    }

    private fun storage(): SqliteStorage? = modules.readyInstance("storage") as? SqliteStorage

    private fun isSafeTeleportEnabled(session: PlayerSession): Boolean =
        session.safeTeleportOverride ?: storage()?.preferences(session.id)?.safeTeleport ?: features.safeTeleport

    private fun recordProfile(action: suspend (SqliteStorage) -> Unit) {
        if ("storage" !in modules.criticalIds) return
        scope.launch {
            try {
                bootFinished.await()
                val storage = storage() ?: return@launch
                action(storage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("Player profile", failure)
            }
        }
    }

    private fun moderation(): Moderation? = if (moderationEnabled) modules.readyInstance("moderation") as? Moderation else null

    fun canModerate(sender: CommandSender): Boolean = moderationEnabled && ModerationCommand.entries.any { sender.hasPermission(it.permission) }

    suspend fun openModeration(target: String, actor: CommandActor) {
        val moderation = moderation() ?: throw CommandProblem("moderation.unavailable")
        moderation.openMenu(target, actor)
    }

    fun completeModeration(sender: CommandSender, prefix: String): List<String> = moderation()?.completeMenu(sender, prefix).orEmpty()

    private fun submitDialog(actor: CommandActor, action: suspend () -> Unit) {
        val session = actor.player()
        if (!session.submit { handleErrors(actor, action) } && session.active) {
            scope.launch { actor.reply("common.command-queue-full") }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!event.hasChangedPosition()) return
        val session = players.session(event.player) ?: return
        if (session.teleportWarmup != null) teleports.checkWarmupMovement(session, event.to)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleportMovement(event: PlayerTeleportEvent) = onMove(event)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("DEPRECATION", "REMOVAL")
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val session = players.session(player) ?: return
        if (session.teleportWarmup == null) return
        // Absorption can take damage without reducing the player's health.
        if (event.finalDamage > 0 || event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION) < 0) {
            teleports.cancelWarmup(session, TeleportResult.DAMAGED)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        if (!features.spawnOnRespawn || "spawn" !in config.enabledModules) return
        val session = players.session(event.player) ?: return
        val hasPersonalSpawn = event.isBedSpawn || event.isAnchorSpawn
        val respectPersonalSpawn = storage()?.preferences(session.id)?.respectBedSpawn ?: features.respectBedSpawn
        if (hasPersonalSpawn && respectPersonalSpawn) return
        session.submit { spawnTeleport(session) }
    }

    internal class BackListener(private val history: BackHistory, private val players: Players, private val features: () -> FeatureSettings) : Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onTeleport(event: PlayerTeleportEvent) {
            if (!features().backOnTeleport) return
            players.session(event.player)?.let { history.record(it, event.from.snapshot()) }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onDeath(event: PlayerDeathEvent) {
            if (!features().backOnDeath) return
            val player = event.entity
            players.session(player)?.let { history.record(it, player.location.snapshot()) }
        }
    }

    private var backListener: BackListener? = null

    private suspend fun spawnTeleport(session: PlayerSession) {
        val actor = CommandActor(session.player, session, scheduler) { messages }
        handleErrors(actor) {
            val boot = bootFinished.await()
            if (boot != Readiness.READY && boot != Readiness.DEGRADED) return@handleErrors
            modules.withModule("spawn") { module ->
                startup.recordFirstPlayerReady()
                (module as Spawn).placeFirstJoin(session)?.let { actor.reply(it) }
            }
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val feature = FeatureCommand.byId[command.name]
        val sanction = ModerationCommand.byId[command.name]
        val player = sender as? Player
        val session = player?.let(players::session)
        if (player != null && session == null) {
            sender.sendMessage(messages.render(Message("common.session-starting")))
            return true
        }
        val actor = CommandActor(sender, session, scheduler) { messages }
        val action: suspend () -> Unit = {
            val started = TimeSource.Monotonic.markNow()
            try {
                handleErrors(actor) {
                    when {
                        sanction != null -> {
                            if (!moderationEnabled) throw ModuleUnavailable("moderation is disabled")
                            val moderation = moderation() ?: throw CommandProblem("moderation.unavailable")
                            moderation.execute(sanction, args, actor)
                        }
                        feature != null -> executeFeature(actor, feature, args)
                        else -> administration.execute(actor, args)
                    }
                }
            } finally {
                if (feature != null || sanction != null) commands.record(started.elapsedNow().inWholeNanoseconds)
            }
        }
        if (session == null) scope.launch { action() }
        else if (!session.submit(action)) sender.sendMessage(messages.render(Message("common.command-queue-full")))
        return true
    }

    private suspend fun executeFeature(actor: CommandActor, feature: FeatureCommand, args: Array<out String>) {
        val features = this.features
        val nameMaxLength = when (feature.module) {
            "homes" -> features.homeNameMaxLength
            "warps" -> features.warpNameMaxLength
            else -> 32
        }
        val argument = feature.parse(args, nameMaxLength)
        if (feature.playerOnly) actor.player()
        when (readiness) {
            Readiness.READY, Readiness.DEGRADED -> Unit
            Readiness.STARTING -> throw CommandProblem("common.starting")
            else -> throw CommandProblem("common.unavailable")
        }
        modules.withModule(feature.module) { module ->
            if (actor.session != null) startup.recordFirstPlayerReady()
            (module as CommandModule).execute(FeatureCall(feature, argument, actor, features))
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (command.name == "qs") return administration.complete(sender, args)
        ModerationCommand.byId[command.name]?.let {
            if (!sender.hasPermission(it.permission)) return emptyList()
            return moderation()?.complete(it, args).orEmpty()
        }
        val feature = FeatureCommand.byId[command.name] ?: return emptyList()
        if (args.size != 1 || feature.module !in config.enabledModules || !sender.hasPermission(feature.permission)) {
            return emptyList()
        }
        if (readiness != Readiness.READY && readiness != Readiness.DEGRADED) return emptyList()
        val viewer = sender as? Player
        val session = viewer?.let(players::session)
        if (feature.playerOnly && session?.active != true) return emptyList()
        val prefix = args[0]
        val settings = features
        return when (feature) {
            FeatureCommand.HOME, FeatureCommand.DELHOME, FeatureCommand.SETHOME, FeatureCommand.HOMES -> {
                val storage = modules.readyInstance("storage") as? SqliteStorage ?: return emptyList()
                val names = storage.cachedNames(DestinationGroup.homes(checkNotNull(session).id))
                if (feature == FeatureCommand.HOMES) return pageSuggestions(names.size, prefix)
                val candidates = if (feature == FeatureCommand.SETHOME) names + "home" else names
                matchingSuggestions(candidates.filter { it.length <= settings.homeNameMaxLength }, prefix)
            }
            FeatureCommand.WARP, FeatureCommand.SETWARP, FeatureCommand.DELWARP, FeatureCommand.WARPS -> {
                val storage = modules.readyInstance("storage") as? SqliteStorage ?: return emptyList()
                val names = storage.cachedNames(DestinationGroup.warps)
                if (feature == FeatureCommand.WARPS) {
                    return pageSuggestions(names.count { !settings.perWarpPermissions || canSeeWarp(sender, it) }, prefix)
                }
                val candidates = names.filter {
                    it.length <= settings.warpNameMaxLength &&
                        it.startsWith(prefix, ignoreCase = true) &&
                        (!settings.perWarpPermissions || canSeeWarp(sender, it))
                }
                matchingSuggestions(candidates, prefix)
            }
            FeatureCommand.TPA, FeatureCommand.TPAHERE -> matchingSuggestions(
                players.onlineSessions()
                    .filter { it !== session && checkNotNull(viewer).canSee(it.player) }
                    .map(PlayerSession::name),
                prefix,
            )
            FeatureCommand.TPACCEPT, FeatureCommand.TPDENY, FeatureCommand.TPREQUESTS -> {
                val teleportation = modules.readyInstance("teleportation") as? Teleportation ?: return emptyList()
                val pending = teleportation.pending(checkNotNull(session))
                if (feature == FeatureCommand.TPREQUESTS) return pageSuggestions(pending.size, prefix)
                matchingSuggestions(pending.map { it.request.from.name }, prefix)
            }
            FeatureCommand.BEDSPAWN, FeatureCommand.SAFETELEPORT -> matchingSuggestions(ToggleSetting.suggestions, prefix)
            FeatureCommand.SEEN -> {
                val online = players.onlineSessions()
                    .filter { viewer == null || viewer.hasPermission("quickstart.seen.details") || viewer.canSee(it.player) }
                    .map(PlayerSession::name)
                val stored = storage()?.cachedPlayerNames().orEmpty()
                matchingSuggestions(online + stored, prefix)
            }
            FeatureCommand.BACK, FeatureCommand.SPAWN, FeatureCommand.SETSPAWN -> emptyList()
        }
    }

    suspend fun reload(dryRun: Boolean = false): List<Message> = reloadLock.withLock {
        if (!dryRun && configurationFailure != null) throw CommandProblem("reload.startup-failed")
        val loadedConfig = try {
            scheduler.blocking { Configuration.loadWithDiagnostics(configFile) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val errors = extractConfigErrors(failure)
            if (!dryRun) configErrors = errors
            return@withLock buildList {
                add(Message(if (dryRun) "reload.check-failed" else "reload.invalid-config-header"))
                for (error in errors) add(Message("reload.error-item", "detail" to error))
            }
        }
        val loadedMessages = try {
            scheduler.blocking { defaultMessages.withOverridesWithDiagnostics(messageFile) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val detail = message(failure)
            if (!dryRun) messageProblem = detail
            throw CommandProblem("reload.invalid-messages", "detail" to detail)
        }

        val replacement = loadedConfig.configuration
        val changes = features.diff(replacement.features)
        val restart = restartRequiredFields(replacement)
        val allWarnings = loadedConfig.warnings + loadedMessages.warnings

        if (!dryRun) {
            configErrors = emptyList()
            configWarnings = loadedConfig.warnings
            missingConfigKeys = loadedConfig.missingKeys
            messageProblem = null
            messageWarnings = loadedMessages.warnings
            lastDiskConfig = replacement
            settings = ReloadableSettings(replacement.features, loadedMessages.messages)
        }

        buildList {
            if (dryRun) {
                add(Message("reload.check-valid", "overrides" to loadedMessages.overrideCount))
                if (configurationFailure != null) add(Message("reload.check-startup-restart"))
            } else {
                add(Message("reload.success"))
            }
            if (changes.isNotEmpty()) {
                val summary = changes.joinToString(", ") { "${it.key} (${it.before} -> ${it.after})" }
                add(Message(if (dryRun) "reload.check-changes" else "reload.changes", "changes" to summary))
            } else {
                add(Message(if (dryRun) "reload.check-no-changes" else "reload.no-changes"))
            }
            if (restart.isNotEmpty()) add(Message("reload.restart-required", "settings" to restart.joinToString()))
            for (warning in allWarnings) add(Message("reload.warning", "detail" to warning))
            if (loadedConfig.missingKeys.isNotEmpty()) {
                val preview = loadedConfig.missingKeys.take(5).joinToString(", ") +
                    if (loadedConfig.missingKeys.size > 5) ", ..." else ""
                add(Message("reload.missing-keys", "count" to loadedConfig.missingKeys.size, "keys" to preview))
            }
        }
    }

    suspend fun inspectConfig(section: String?): List<Message> {
        val disk = runCatching { scheduler.blocking { Configuration.loadWithDiagnostics(configFile) } }.getOrNull()
        val diskCfg = disk?.configuration ?: lastDiskConfig
        val liveFeatures = features
        val restart = restartRequiredFields(diskCfg)
        if (section == null) {
            return buildList {
                add(Message("config.heading"))
                add(Message("config.summary", "startup" to config.startup, "modules" to config.enabledModules.sorted().joinToString(", ").ifEmpty { "none" }))
                if (restart.isNotEmpty()) {
                    add(Message("config.pending-restart", "settings" to restart.joinToString()))
                }
                val warnings = (disk?.warnings ?: configWarnings) + messageWarnings
                if (warnings.isNotEmpty()) {
                    add(Message("config.warning-count", "count" to warnings.size))
                }
                val missing = disk?.missingKeys ?: missingConfigKeys
                if (missing.isNotEmpty()) {
                    add(Message("config.missing-count", "count" to missing.size))
                }
                val buttons = Administration.configSections.map { sec ->
                    Message("ui.control", "action" to ChatAction(
                        "qs config $sec",
                        Message("config.section-button", "section" to sec),
                        Message("config.section-hover", "section" to sec),
                    ))
                }
                for (row in buttons.chunked(4)) {
                    add(Message("ui.action-row", "actions" to MessageGroup(row, Component.text("  "))))
                }
            }
        }
        return buildList {
            add(Message("config.section-heading", "section" to section))
            fun line(key: String, value: Any, diskValue: Any? = null) {
                if (diskValue != null && diskValue != value) {
                    add(Message("config.entry-pending", "key" to key, "value" to value.toString(), "disk" to diskValue.toString()))
                } else {
                    add(Message("config.entry", "key" to key, "value" to value.toString()))
                }
            }
            when (section) {
                "startup" -> line("startup", config.startup, diskCfg.startup)
                "modules" -> {
                    for (id in Configuration.featureIds) {
                        line("modules.$id", id in config.enabledModules, id in diskCfg.enabledModules)
                    }
                }
                "homes" -> {
                    line("homes.limit", liveFeatures.homeLimit)
                    line("homes.cooldown-seconds", liveFeatures.homeCooldownSeconds)
                    line("homes.name-max-length", liveFeatures.homeNameMaxLength)
                }
                "warps" -> {
                    line("warps.per-warp-permissions", liveFeatures.perWarpPermissions)
                    line("warps.cooldown-seconds", liveFeatures.warpCooldownSeconds)
                    line("warps.name-max-length", liveFeatures.warpNameMaxLength)
                }
                "spawn" -> {
                    line("spawn.first-join", liveFeatures.firstJoinSpawn)
                    line("spawn.on-respawn", liveFeatures.spawnOnRespawn)
                    line("spawn.respect-bed-spawn", liveFeatures.respectBedSpawn)
                    line("spawn.on-join", liveFeatures.spawnOnJoin)
                    line("spawn.cooldown-seconds", liveFeatures.spawnCooldownSeconds)
                }
                "teleportation" -> {
                    line("teleportation.warmup-seconds", liveFeatures.teleportWarmupSeconds)
                    line("teleportation.request-send-cooldown-seconds", liveFeatures.requestSendCooldownSeconds)
                    line("teleportation.request-teleport-cooldown-seconds", liveFeatures.requestTeleportCooldownSeconds)
                    line("teleportation.back-cooldown-seconds", liveFeatures.backCooldownSeconds)
                    line("teleportation.request-expiration-seconds", liveFeatures.requestExpirationSeconds)
                    line("teleportation.strict-tpahere", liveFeatures.strictTpahere)
                    line("teleportation.back-on-death", liveFeatures.backOnDeath)
                    line("teleportation.back-on-teleport", liveFeatures.backOnTeleport)
                    line("teleportation.safe-teleport", liveFeatures.safeTeleport)
                }
                "moderation" -> {
                    val mod = liveFeatures.moderation
                    line("moderation.broadcast-to-staff", mod.broadcastToStaff)
                    line("moderation.log-to-console", mod.logToConsole)
                    line("moderation.max-reason-length", mod.maxReasonLength)
                    line("moderation.require-reason-for", mod.requireReasonFor.sorted().joinToString(", ").ifEmpty { "none" })
                    line("moderation.min-ipv4-cidr-prefix", "/${mod.minIpv4CidrPrefix}")
                    line("moderation.min-ipv6-cidr-prefix", "/${mod.minIpv6CidrPrefix}")
                    line("moderation.muted-commands", mod.mutedCommands.sorted().joinToString(", ").ifEmpty { "none" })
                    line("moderation.reason-presets", "${mod.reasonPresets.size} configured")
                }
                "performance" -> {
                    line("performance.warmup.enabled", config.warmup.enabled, diskCfg.warmup.enabled)
                    line("performance.warmup.empty-server-workers", config.warmup.emptyServerWorkers, diskCfg.warmup.emptyServerWorkers)
                    line("performance.warmup.active-server-workers", config.warmup.activeServerWorkers, diskCfg.warmup.activeServerWorkers)
                    line("performance.warmup.pause-optional-on-join", config.warmup.pauseOnJoin, diskCfg.warmup.pauseOnJoin)
                }
                "storage" -> {
                    line("storage.backend", "sqlite")
                    line("storage.max-pending-operations", config.maxPendingOperations, diskCfg.maxPendingOperations)
                }
            }
        }
    }

    private fun restartRequiredFields(replacement: Configuration): List<String> = buildList {
        if (config.startup != replacement.startup) add("startup")
        if (config.enabledModules != replacement.enabledModules) add("modules")
        if (config.warmup != replacement.warmup) add("performance.warmup")
        if (config.maxPendingOperations != replacement.maxPendingOperations) add("storage.max-pending-operations")
    }

    suspend fun doctor(): List<Message> = buildList {
        add(Message("doctor.readiness", "state" to readiness.name.lowercase(Locale.ROOT), "strategy" to config.startup))
        startupProblem?.let { add(Message("doctor.startup-failure", "detail" to it)) }
        for (error in configErrors) add(Message("doctor.config-error", "detail" to error))
        messageProblem?.let { add(Message("doctor.message-error", "detail" to it)) }
        for (warning in configWarnings) add(Message("doctor.config-warning", "detail" to warning))
        for (warning in messageWarnings) add(Message("doctor.message-warning", "detail" to warning))
        if (missingConfigKeys.isNotEmpty()) {
            val preview = missingConfigKeys.take(5).joinToString(", ") +
                if (missingConfigKeys.size > 5) ", ..." else ""
            add(Message("doctor.config-missing-keys", "count" to missingConfigKeys.size, "keys" to preview))
        }
        val statuses = modules()
        statuses.forEach { module ->
            module.failure?.let { add(Message("doctor.module-failed", "module" to module.id, "detail" to it)) }
        }
        val storageStatus = statuses.first { it.id == "storage" }
        when (storageStatus.state) {
            ModuleState.READY -> {
                val storage = sqlite.value
                val previous = storage.lastFailure
                try {
                    storage.health()
                    add(Message("doctor.sqlite-healthy", "timings" to storage.timings.describe()))
                } catch (failure: StorageFailure) {
                    add(Message("doctor.sqlite-health-failed", "detail" to message(failure)))
                }
                previous?.let {
                    add(Message("doctor.sqlite-last-failure", "time" to formatTimestamp(it.at), "operation" to it.operation, "detail" to it.reason))
                }
            }
            ModuleState.DISABLED -> add(Message("doctor.sqlite-disabled"))
            else -> add(Message("doctor.sqlite-state", "state" to storageStatus.state.name.lowercase(Locale.ROOT)))
        }
        if (configErrors.isEmpty()) add(Message("doctor.config-valid"))
        add(Message("doctor.commands", "timings" to commands.describe()))
    }

    private fun extractConfigErrors(failure: Throwable?): List<String> = when (failure) {
        null -> emptyList()
        is ConfigurationException -> failure.errors
        else -> listOf(message(failure))
    }

    private suspend fun handleErrors(actor: CommandActor, action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CommandProblem) {
            actor.reply(failure.notice)
        } catch (failure: ModuleUnavailable) {
            actor.reply("common.operation-unavailable", "detail" to message(failure))
        } catch (failure: StorageFailure) {
            actor.reply("common.storage-failure", "detail" to message(failure))
        } catch (_: EntityUnavailable) {
            actor.reply("common.connection-inactive")
        } catch (failure: Exception) {
            report("Command", failure)
            actor.reply("common.operation-failed")
        }
    }

    private fun report(context: String, failure: Throwable) {
        plugin.logger.log(Level.SEVERE, "$context: ${message(failure)}", failure)
    }

    private fun reportStartupFailure(context: String, failure: Throwable) {
        plugin.componentLogger.error(startupText("$context: ${message(failure)}", NamedTextColor.RED), failure)
    }

    private fun startupText(text: String, color: NamedTextColor): Component =
        Component.text(text, color.takeIf { colorStartupLogs })

    private fun message(failure: Throwable) = failure.message ?: failure.javaClass.simpleName
}
