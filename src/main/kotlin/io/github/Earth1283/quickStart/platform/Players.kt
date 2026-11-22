package io.github.Earth1283.quickStart.platform

import io.github.Earth1283.quickStart.core.Position
import java.util.EnumMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bukkit.Location
import org.bukkit.entity.Player

internal class PlayerSession(val player: Player, parent: CoroutineScope) {
    val id: UUID = player.uniqueId
    val name: String = player.name
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val commands = Channel<suspend () -> Unit>(capacity = 8)
    private val commandLoop by lazy {
        scope.launch(start = CoroutineStart.LAZY) {
            for (action in commands) action()
        }
    }
    val disconnected = CompletableDeferred<Unit>()
    // Teleport completion publishes this state before clearing teleportInFlight.
    private var lastTeleports: EnumMap<TeleportAction, Long>? = null
    @Volatile var teleportInFlight = false
    val active: Boolean get() = job.isActive

    fun onCooldown(cooldown: TeleportCooldown): Boolean {
        val previous = lastTeleports?.get(cooldown.action) ?: return false
        return System.nanoTime() - previous < TimeUnit.SECONDS.toNanos(cooldown.seconds.toLong())
    }

    fun recordTeleport(action: TeleportAction) {
        val completed = lastTeleports ?: EnumMap<TeleportAction, Long>(TeleportAction::class.java).also { lastTeleports = it }
        completed[action] = System.nanoTime()
    }

    fun requireActive() {
        if (!active) throw EntityUnavailable()
    }

    fun submit(action: suspend () -> Unit): Boolean {
        if (!active || !commands.trySend(action).isSuccess) return false
        commandLoop.start()
        return true
    }

    fun close() {
        commands.cancel()
        job.cancel()
        disconnected.complete(Unit)
    }
}

internal class Players(private val scope: CoroutineScope) {
    private val gate = Any()
    private val sessions = mutableMapOf<UUID, PlayerSession>()
    private val online = MutableStateFlow(0)
    val count = online.asStateFlow()
    private val departures = mutableListOf<(PlayerSession) -> Unit>()
    private var closed = false

    fun joined(player: Player): PlayerSession? = synchronized(gate) {
        if (closed) return null
        val id = player.uniqueId
        val previous = sessions[id]
        if (previous?.player === player) return previous
        val session = PlayerSession(player, scope)
        sessions[id] = session
        online.value = sessions.size
        previous?.let(::departed)
        session
    }

    fun quit(player: Player) = synchronized(gate) {
        val session = sessions[player.uniqueId] ?: return@synchronized
        if (session.player !== player) return@synchronized
        sessions.remove(session.id)
        online.value = sessions.size
        departed(session)
    }

    fun session(player: Player): PlayerSession? = synchronized(gate) {
        sessions[player.uniqueId]?.takeIf { it.player === player }
    }

    fun find(name: String): PlayerSession? = synchronized(gate) {
        sessions.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    fun names(prefix: String): List<String> = synchronized(gate) {
        sessions.values.map(PlayerSession::name)
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .sortedBy { it.lowercase(Locale.ROOT) }
    }

    fun onDeparture(listener: (PlayerSession) -> Unit): AutoCloseable = synchronized(gate) {
        if (!closed) departures.add(listener)
        AutoCloseable { synchronized(gate) { departures.remove(listener) } }
    }

    fun close() = synchronized(gate) {
        closed = true
        sessions.values.forEach(::departed)
        sessions.clear()
        departures.clear()
        online.value = 0
    }

    private fun departed(session: PlayerSession) {
        session.close()
        departures.forEach { it(session) }
    }
}

internal class BackHistory(players: Players) {
    private val positions = ConcurrentHashMap<PlayerSession, Position>()
    private val departure = players.onDeparture { positions.remove(it) }

    fun get(session: PlayerSession): Position? = positions[session]

    fun record(session: PlayerSession, position: Position) {
        positions.compute(session) { _, _ -> position.takeIf { session.active } }
    }

    fun close() {
        departure.close()
        positions.clear()
    }
}

internal fun Location.snapshot(): Position = Position(checkNotNull(world).uid, x, y, z, yaw, pitch)
