package io.github.Earth1283.quickStart.platform

import io.github.Earth1283.quickStart.core.Message
import io.github.Earth1283.quickStart.core.Position
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.concurrent.CompletableFuture
import kotlin.math.floor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause

internal enum class TeleportResult(val failureMessage: Message?) {
    SUCCESS(null),
    CANCELLED(Message("teleport.cancelled")),
    MOVED(Message("teleport.cancelled-movement")),
    DAMAGED(Message("teleport.cancelled-damage")),
    COOLDOWN(Message("teleport.cooldown")),
    IN_PROGRESS(Message("teleport.in-progress")),
    WORLD_UNAVAILABLE(Message("teleport.world-unavailable")),
    DISCONNECTED(Message("teleport.disconnected")),
    UNSAFE_OUT_OF_BOUNDS(Message("teleport.unsafe-bounds")),
    UNSAFE_HAZARD(Message("teleport.unsafe-hazard")),
    UNSAFE_OBSTRUCTED(Message("teleport.unsafe-obstructed")),
    UNSAFE_DROP(Message("teleport.unsafe-drop")),
}

internal enum class TeleportAction { HOME, WARP, SPAWN, BACK, REQUEST }

internal data class TeleportCooldown(val action: TeleportAction, val seconds: Int)

internal class TeleportWarmup(
    private val origin: Location,
    val task: ScheduledTask,
    val completion: CompletableDeferred<TeleportResult>,
) {
    fun movedTo(location: Location): Boolean = location.x != origin.x || location.y != origin.y ||
        location.z != origin.z || location.world != origin.world
}

internal class Teleports(
    private val server: Server,
    private val scheduler: ServerScheduler,
    private val backHistory: BackHistory?,
    private val recordBack: () -> Boolean,
    private val warmupSeconds: () -> Int,
    private val safeTeleportEnabled: (PlayerSession) -> Boolean,
    private val render: (Message) -> Component,
) {
    private sealed interface MovePreparation {
        data object Started : MovePreparation
        data class CheckSafety(val world: World, val destination: Location, val allowMidAir: Boolean) : MovePreparation
    }

    suspend fun position(session: PlayerSession): Position = scheduler.entity(session.player) {
        session.requireActive()
        session.player.location.snapshot()
    }

    suspend fun move(
        session: PlayerSession,
        position: Position,
        cooldown: TeleportCooldown? = null,
        anchor: PlayerSession? = null,
        warmup: Boolean = true,
        validateSafety: Boolean = true,
    ): TeleportResult {
        val completion = CompletableDeferred<TeleportResult>()
        val prep = scheduler.entity(session.player) {
            prepareMove(session, position, cooldown, completion, anchor, warmup, validateSafety)
        }
        if (prep is MovePreparation.CheckSafety) {
            val issue = try {
                val chunkX = floor(position.x).toInt() shr 4
                val chunkZ = floor(position.z).toInt() shr 4
                val loaded = awaitWithDisconnect(session, prep.world.getChunkAtAsync(chunkX, chunkZ))
                if (!loaded) {
                    session.teleportInFlight = false
                    return TeleportResult.DISCONNECTED
                }
                scheduler.region(prep.destination) {
                    SafeTeleport.evaluate(prep.destination, allowMidAir = prep.allowMidAir)
                }
            } catch (failure: Throwable) {
                session.teleportInFlight = false
                if (failure is EntityUnavailable) return TeleportResult.DISCONNECTED
                throw failure
            }
            if (issue != null) {
                session.teleportInFlight = false
                return issue.toTeleportResult()
            }
            try {
                scheduler.entity(session.player) {
                    session.teleportInFlight = false
                    startMove(session, position, cooldown, completion, anchor, warmup)
                }
            } catch (failure: Throwable) {
                session.teleportInFlight = false
                if (failure is EntityUnavailable) return TeleportResult.DISCONNECTED
                throw failure
            }
        }
        return awaitMove(session, completion)
    }

    suspend fun moveTo(session: PlayerSession, anchor: PlayerSession, cooldown: TeleportCooldown): TeleportResult {
        val checkSafety = safeTeleportEnabled(session)
        if (checkSafety) {
            val position = scheduler.entity(anchor.player) {
                anchor.requireActive()
                anchor.player.location.snapshot()
            }
            return move(session, position, cooldown, anchor, warmup = true, validateSafety = true)
        }
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

    private fun prepareMove(
        session: PlayerSession,
        position: Position,
        cooldown: TeleportCooldown?,
        completion: CompletableDeferred<TeleportResult>,
        anchor: PlayerSession?,
        warmup: Boolean,
        validateSafety: Boolean,
    ): MovePreparation {
        val blocked = when {
            !session.active || anchor?.active == false -> TeleportResult.DISCONNECTED
            session.teleportInFlight || session.teleportWarmup != null -> TeleportResult.IN_PROGRESS
            else -> null
        }
        if (blocked != null) {
            completion.complete(blocked)
            return MovePreparation.Started
        }
        val enforceCooldown = cooldown != null && cooldown.seconds > 0
        if (enforceCooldown && session.onCooldown(cooldown)) {
            completion.complete(TeleportResult.COOLDOWN)
            return MovePreparation.Started
        }
        val world = server.getWorld(position.worldId)
        if (world == null) {
            completion.complete(TeleportResult.WORLD_UNAVAILABLE)
            return MovePreparation.Started
        }
        val destination = Location(world, position.x, position.y, position.z, position.yaw, position.pitch)
        if (validateSafety && safeTeleportEnabled(session) && session.player.gameMode != GameMode.SPECTATOR) {
            session.teleportInFlight = true
            return MovePreparation.CheckSafety(world, destination, session.player.isFlying)
        }
        beginMove(session, world, position, destination, cooldown, completion, anchor, warmup)
        return MovePreparation.Started
    }

    private fun startMove(
        session: PlayerSession,
        position: Position,
        cooldown: TeleportCooldown?,
        completion: CompletableDeferred<TeleportResult>,
        anchor: PlayerSession?,
        warmup: Boolean = true,
    ) {
        val blocked = when {
            !session.active || anchor?.active == false -> TeleportResult.DISCONNECTED
            session.teleportInFlight || session.teleportWarmup != null -> TeleportResult.IN_PROGRESS
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
        beginMove(session, world, position, destination, cooldown, completion, anchor, warmup)
    }

    private fun beginMove(
        session: PlayerSession,
        world: World,
        position: Position,
        destination: Location,
        cooldown: TeleportCooldown?,
        completion: CompletableDeferred<TeleportResult>,
        anchor: PlayerSession?,
        warmup: Boolean,
    ) {
        val seconds = if (warmup) warmupSeconds() else 0
        if (seconds == 0) {
            teleport(session, destination, cooldown, completion)
        } else {
            startWarmup(session, anchor, seconds, completion) {
                if (server.getWorld(position.worldId) === world) teleport(session, destination, cooldown, completion)
                else completion.complete(TeleportResult.WORLD_UNAVAILABLE)
            }
        }
    }

    fun checkWarmupMovement(session: PlayerSession, destination: Location) {
        if (session.teleportWarmup?.movedTo(destination) == true) cancelWarmup(session, TeleportResult.MOVED)
    }

    fun cancelWarmup(session: PlayerSession, result: TeleportResult) {
        val warmup = stopWarmup(session) ?: return
        try {
            if (session.active) session.player.sendActionBar(render(checkNotNull(result.failureMessage)))
        } finally {
            warmup.completion.complete(result)
        }
    }

    private fun startWarmup(
        session: PlayerSession,
        anchor: PlayerSession?,
        seconds: Int,
        completion: CompletableDeferred<TeleportResult>,
        finish: () -> Unit,
    ) {
        val player = session.player
        val origin = player.location
        var ticksLeft = seconds * TICKS_PER_SECOND
        val firstNotice = warmupNotice(seconds)
        val task = scheduler.eachEntityTick(player, { completion.complete(TeleportResult.DISCONNECTED) }) {
            val warmup = checkNotNull(session.teleportWarmup)
            try {
                ticksLeft--
                when {
                    !session.active || anchor?.active == false -> cancelWarmup(session, TeleportResult.DISCONNECTED)
                    warmup.movedTo(player.location) -> cancelWarmup(session, TeleportResult.MOVED)
                    ticksLeft == 0 -> {
                        stopWarmup(session)
                        player.sendActionBar(Component.empty())
                        finish()
                    }
                    ticksLeft % TICKS_PER_SECOND == 0 -> player.sendActionBar(warmupNotice(ticksLeft / TICKS_PER_SECOND))
                }
            } catch (failure: Exception) {
                stopWarmup(session)
                completion.completeExceptionally(failure)
            }
        }
        if (task == null) {
            completion.complete(TeleportResult.DISCONNECTED)
            return
        }
        session.teleportWarmup = TeleportWarmup(origin, task, completion)
        try {
            player.sendActionBar(firstNotice)
        } catch (failure: Exception) {
            stopWarmup(session)
            completion.completeExceptionally(failure)
        }
    }

    private fun warmupNotice(seconds: Int) = render(Message("teleport.warmup", "seconds" to seconds))

    private fun stopWarmup(session: PlayerSession): TeleportWarmup? {
        val warmup = session.teleportWarmup ?: return null
        session.teleportWarmup = null
        warmup.task.cancel()
        return warmup
    }

    private fun teleport(
        session: PlayerSession,
        destination: Location,
        cooldown: TeleportCooldown?,
        completion: CompletableDeferred<TeleportResult>,
    ) {
        val origin = if (backHistory != null && recordBack()) session.player.location.snapshot() else null
        session.teleportInFlight = true
        try {
            session.player.teleportAsync(destination, TeleportCause.PLUGIN).whenComplete { success, failure ->
                if (success == true) {
                    if (origin != null && recordBack()) backHistory?.record(session, origin)
                    if (cooldown != null && cooldown.seconds > 0) session.recordTeleport(cooldown.action)
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
            completion.completeExceptionally(failure)
        }
    }

    private suspend fun awaitMove(session: PlayerSession, completion: CompletableDeferred<TeleportResult>): TeleportResult =
        select {
            completion.onAwait { it }
            session.disconnected.onAwait { TeleportResult.DISCONNECTED }
        }

    private suspend fun <T> awaitWithDisconnect(session: PlayerSession, future: CompletableFuture<T>): Boolean {
        val completed = CompletableDeferred<Unit>()
        future.whenComplete { _, failure ->
            if (failure != null) completed.completeExceptionally(failure) else completed.complete(Unit)
        }
        return select {
            completed.onAwait { true }
            session.disconnected.onAwait { false }
        }
    }

    private fun SafetyIssue.toTeleportResult(): TeleportResult = when (this) {
        SafetyIssue.OUT_OF_BOUNDS -> TeleportResult.UNSAFE_OUT_OF_BOUNDS
        SafetyIssue.HAZARD -> TeleportResult.UNSAFE_HAZARD
        SafetyIssue.OBSTRUCTED -> TeleportResult.UNSAFE_OBSTRUCTED
        SafetyIssue.UNSAFE_DROP -> TeleportResult.UNSAFE_DROP
    }

    private companion object {
        const val TICKS_PER_SECOND = 20
    }
}
