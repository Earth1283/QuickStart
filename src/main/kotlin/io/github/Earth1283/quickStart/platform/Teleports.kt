package io.github.Earth1283.quickStart.platform

import io.github.Earth1283.quickStart.core.Message
import io.github.Earth1283.quickStart.core.Position
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

internal enum class TeleportAction { HOME, WARP, SPAWN, BACK, REQUEST }

internal data class TeleportCooldown(val action: TeleportAction, val seconds: Int)

internal class Teleports(
    private val server: Server,
    private val scheduler: ServerScheduler,
    private val backHistory: BackHistory?,
    private val recordBack: () -> Boolean,
) {
    suspend fun position(session: PlayerSession): Position = scheduler.entity(session.player) {
        session.requireActive()
        session.player.location.snapshot()
    }

    suspend fun move(
        session: PlayerSession,
        position: Position,
        cooldown: TeleportCooldown? = null,
        anchor: PlayerSession? = null,
    ): TeleportResult {
        val completion = CompletableDeferred<TeleportResult>()
        scheduler.entity(session.player) { startMove(session, position, cooldown, completion, anchor) }
        return awaitMove(session, completion)
    }

    suspend fun moveTo(session: PlayerSession, anchor: PlayerSession, cooldown: TeleportCooldown): TeleportResult {
        val completion = CompletableDeferred<TeleportResult>()
        val crossRegionPosition = scheduler.entity(anchor.player) {
            anchor.requireActive()
            val position = anchor.player.location.snapshot()
            if (!server.isOwnedByCurrentRegion(session.player)) return@entity position
            startMove(session, position, cooldown, completion, anchor)
            null
        }
        if (crossRegionPosition != null) {
            scheduler.entity(session.player) { startMove(session, crossRegionPosition, cooldown, completion, anchor) }
        }
        return awaitMove(session, completion)
    }

    private fun startMove(
        session: PlayerSession,
        position: Position,
        cooldown: TeleportCooldown?,
        completion: CompletableDeferred<TeleportResult>,
        anchor: PlayerSession?,
    ) {
        val blocked = when {
            !session.active || anchor?.active == false -> TeleportResult.DISCONNECTED
            session.teleportInFlight -> TeleportResult.IN_PROGRESS
            else -> null
        }
        if (blocked != null) {
            completion.complete(blocked)
            return
        }
        val enforceCooldown = cooldown != null && cooldown.seconds > 0
        if (enforceCooldown && session.onCooldown(cooldown)) {
            completion.complete(TeleportResult.COOLDOWN)
            return
        }
        val world = server.getWorld(position.worldId)
        if (world == null) {
            completion.complete(TeleportResult.WORLD_UNAVAILABLE)
            return
        }
        val destination = Location(world, position.x, position.y, position.z, position.yaw, position.pitch)
        val origin = if (backHistory != null && recordBack()) session.player.location.snapshot() else null
        session.teleportInFlight = true
        try {
            session.player.teleportAsync(destination, TeleportCause.PLUGIN).whenComplete { success, failure ->
                if (success == true) {
                    if (origin != null && recordBack()) backHistory?.record(session, origin)
                    if (enforceCooldown) session.recordTeleport(cooldown.action)
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
    }

    private suspend fun awaitMove(session: PlayerSession, completion: CompletableDeferred<TeleportResult>): TeleportResult =
        select {
            completion.onAwait { it }
            session.disconnected.onAwait { TeleportResult.DISCONNECTED }
        }
}
