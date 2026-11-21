package io.github.Earth1283.quickStart.platform

import io.github.Earth1283.quickStart.core.Message
import io.github.Earth1283.quickStart.core.Position
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause

internal enum class TeleportResult(val failureMessage: Message?) {
    SUCCESS(null),
    CANCELLED(Message("teleport.cancelled")),
    COOLDOWN(Message("teleport.cooldown")),
    IN_PROGRESS(Message("teleport.in-progress")),
    WORLD_UNAVAILABLE(Message("teleport.world-unavailable")),
    DISCONNECTED(Message("teleport.disconnected")),
}

internal class Teleports(
    private val server: Server,
    private val scheduler: ServerScheduler,
    private val backHistory: BackHistory?,
) {
    suspend fun position(session: PlayerSession): Position = scheduler.entity(session.player) {
        session.requireActive()
        session.player.location.snapshot()
    }

    suspend fun move(
        session: PlayerSession,
        position: Position,
        cooldownSeconds: Int? = null,
        stillAllowed: () -> Boolean = { true },
    ): TeleportResult {
        val completion = CompletableDeferred<TeleportResult>()
        val started = scheduler.entity(session.player) {
            if (!session.active || !stillAllowed()) return@entity TeleportResult.DISCONNECTED
            if (session.teleportInFlight) return@entity TeleportResult.IN_PROGRESS
            val lastTeleport = session.lastRequestTeleport
            if (cooldownSeconds != null && lastTeleport != null &&
                System.nanoTime() - lastTeleport < TimeUnit.SECONDS.toNanos(cooldownSeconds.toLong())
            ) return@entity TeleportResult.COOLDOWN
            val world = server.getWorld(position.worldId) ?: return@entity TeleportResult.WORLD_UNAVAILABLE
            val destination = Location(world, position.x, position.y, position.z, position.yaw, position.pitch)
            val origin = if (backHistory != null) session.player.location.snapshot() else null
            session.teleportInFlight = true
            try {
                session.player.teleportAsync(destination, TeleportCause.PLUGIN).whenComplete { success, failure ->
                    if (success == true) {
                        if (origin != null) backHistory?.record(session, origin)
                        if (cooldownSeconds != null) session.lastRequestTeleport = System.nanoTime()
                    }
                    session.teleportInFlight = false
                    when {
                        failure != null -> completion.completeExceptionally(failure)
                        success == true -> completion.complete(TeleportResult.SUCCESS)
                        else -> completion.complete(TeleportResult.CANCELLED)
                    }
                }
            } catch (failure: Exception) {
                session.teleportInFlight = false
                throw failure
            }
            null
        }
        return started ?: select {
            completion.onAwait { it }
            session.disconnected.onAwait { TeleportResult.DISCONNECTED }
        }
    }
}
