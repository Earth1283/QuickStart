package io.github.Earth1283.quickStart.core

import io.github.Earth1283.quickStart.api.ModuleState
import io.github.Earth1283.quickStart.api.QuickStartApi
import io.github.Earth1283.quickStart.api.Readiness
import io.github.Earth1283.quickStart.features.Homes
import io.github.Earth1283.quickStart.features.Moderation
import io.github.Earth1283.quickStart.features.ModerationCommand
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
    @Volatile private var configProblem = configurationFailure?.let(::message)
    @Volatile private var messageProblem: String? = null
    @Volatile private var startupProblem: String? = null
    internal val startup = StartupMeasurements(enabledAt)
    internal val commands = LatencyMeasurements()
    private val administration = Administration(this)

    private val modules = Modules(scope, definitions(), scheduler::global) { id, failure ->
        report("Module $id", failure)
    }

    override val readiness: Readiness get() = state.get()
    override fun modules() = modules.statuses()

    private fun definitions(): List<ModuleDefinition> {
        val persistent = listOf("homes", "warps", "spawn")
        val storage = ModuleDefinition("storage", moderationEnabled || persistent.any(config.enabledModules::contains), critical = true) { sqlite.value }
        fun feature(id: String, create: () -> CommandModule) = ModuleDefinition(
            id = id,
            enabled = id in config.enabledModules,
            required = if (id in persistent) listOf("storage") else emptyList(),
        ) { create() }
        return listOf(
            storage,
            ModuleDefinition("moderation", moderationEnabled, critical = true, required = listOf("storage")) { moduleScope ->
                Moderation(sqlite.value, players, scheduler, plugin.server, moduleScope, { messages.render(it) }, ::submitDialog)
            },
            feature("teleportation") { Teleportation(players, scheduler, teleports, checkNotNull(backHistory)) },
            feature("homes") { Homes(sqlite.value, teleports) },
            feature("warps") { Warps(sqlite.value, teleports) },
            feature("spawn") { Spawn(sqlite.value, teleports, plugin.server, scheduler) },
        )
    }

    fun start() {
        try {
            if (messageFile.exists()) {
                settings = settings.copy(messages = defaultMessages.withOverrides(messageFile))
            } else {
                plugin.saveResource(messageFile.name, false)
            }
        } catch (failure: Exception) {
            messageProblem = message(failure)
            plugin.componentLogger.warn(startupText("Could not load message.yml. Using default messages.", NamedTextColor.GOLD), failure)
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
        val spawnEnabled = "spawn" in config.enabledModules
        if (spawnEnabled && features.firstJoinSpawn && !event.player.hasPlayedBefore()) {
            session.submit { spawnTeleport(session) }
        } else if (spawnEnabled && features.spawnOnJoin) {
            session.submit { spawnTeleport(session) }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = players.quit(event.player)

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
        if (!Moderation.isChatCommand(event.message)) return
        val moderation = moderation() ?: return
        val session = players.session(event.player) ?: return
        val notice = moderation.muteNotice(session) ?: return
        event.isCancelled = true
        event.player.sendMessage(messages.render(notice))
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
            FeatureCommand.BACK, FeatureCommand.SPAWN, FeatureCommand.SETSPAWN -> emptyList()
        }
    }

    suspend fun reload(): List<Message> = reloadLock.withLock {
        if (configurationFailure != null) throw CommandProblem("reload.startup-failed")
        val replacement = try {
            scheduler.blocking { Configuration.load(configFile) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            configProblem = message(failure)
            throw CommandProblem("reload.invalid-config", "detail" to message(failure))
        }
        configProblem = null
        val replacementMessages = try {
            scheduler.blocking { defaultMessages.withOverrides(messageFile) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            messageProblem = message(failure)
            throw CommandProblem("reload.invalid-messages", "detail" to message(failure))
        }
        messageProblem = null
        settings = ReloadableSettings(replacement.features, replacementMessages)
        val restart = buildList {
            if (config.startup != replacement.startup) add("startup")
            if (config.enabledModules != replacement.enabledModules) add("modules")
            if (config.warmup != replacement.warmup) add("performance.warmup")
            if (config.maxPendingOperations != replacement.maxPendingOperations) add("storage.max-pending-operations")
        }
        buildList {
            add(Message("reload.success"))
            if (restart.isNotEmpty()) add(Message("reload.restart-required", "settings" to restart.joinToString()))
        }
    }

    suspend fun doctor(): List<Message> = buildList {
        add(Message("doctor.readiness", "state" to readiness.name.lowercase(Locale.ROOT), "strategy" to config.startup))
        startupProblem?.let { add(Message("doctor.startup-failure", "detail" to it)) }
        configProblem?.let { add(Message("doctor.config-error", "detail" to it)) }
        messageProblem?.let { add(Message("doctor.message-error", "detail" to it)) }
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
                    add(Message("doctor.sqlite-last-failure", "time" to it.at, "operation" to it.operation, "detail" to it.reason))
                }
            }
            ModuleState.DISABLED -> add(Message("doctor.sqlite-disabled"))
            else -> add(Message("doctor.sqlite-state", "state" to storageStatus.state.name.lowercase(Locale.ROOT)))
        }
        if (configProblem == null) add(Message("doctor.config-valid"))
        add(Message("doctor.commands", "timings" to commands.describe()))
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
