package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.CommandModule
import io.github.Earth1283.quickStart.core.CommandProblem
import io.github.Earth1283.quickStart.core.FeatureCall
import io.github.Earth1283.quickStart.core.FeatureCommand
import io.github.Earth1283.quickStart.core.Message
import io.github.Earth1283.quickStart.platform.BackHistory
import io.github.Earth1283.quickStart.platform.EntityUnavailable
import io.github.Earth1283.quickStart.platform.PlayerSession
import io.github.Earth1283.quickStart.platform.Players
import io.github.Earth1283.quickStart.platform.ServerScheduler
import io.github.Earth1283.quickStart.platform.TeleportAction
import io.github.Earth1283.quickStart.platform.TeleportCooldown
import io.github.Earth1283.quickStart.platform.TeleportResult
import io.github.Earth1283.quickStart.platform.Teleports
import net.kyori.adventure.text.Component

internal class Teleportation(
    private val players: Players,
    private val scheduler: ServerScheduler,
    private val teleports: Teleports,
    private val backHistory: BackHistory,
) : CommandModule {
    private val requests = TeleportRequests(PlayerSession::active)
    private var departure: AutoCloseable? = null

    override fun activate() {
        departure = players.onDeparture(requests::disconnect)
    }

    override fun close() {
        departure?.close()
        requests.clear()
    }

    override suspend fun execute(call: FeatureCall) {
        val player = call.actor.player()
        when (call.command) {
            FeatureCommand.TPA, FeatureCommand.TPAHERE -> send(call, player)
            FeatureCommand.TPACCEPT, FeatureCommand.TPDENY -> respond(call, player)
            FeatureCommand.BACK -> {
                val position = backHistory.get(player) ?: return call.actor.reply("back.unavailable")
                val cooldown = TeleportCooldown(TeleportAction.BACK, call.settings.backCooldownSeconds)
                val result = teleports.move(player, position, cooldown)
                call.teleportResult(result, "back.teleported")
            }
            else -> error("Unsupported teleportation command ${call.command.id}")
        }
    }

    private suspend fun send(call: FeatureCall, from: PlayerSession) {
        val target = players.find(call.name()) ?: throw CommandProblem("requests.player-unavailable")
        if (from === target) throw CommandProblem("requests.self")
        val bringHere = call.command == FeatureCommand.TPAHERE
        val expiration = call.settings.requestExpirationSeconds
        val status = try {
            val destination = if (bringHere && call.settings.strictTpahere) teleports.position(from) else null
            scheduler.entity(target.player) {
                target.requireActive()
                if (!target.player.hasPermission("quickstart.tpa")) throw CommandProblem("requests.player-unavailable")
                requests.send(from, target, bringHere, call.settings.requestSendCooldownSeconds, expiration, destination)
                    .also { result ->
                        if (result == RequestStatus.SENT) {
                            val key = when {
                                destination != null -> "requests.incoming-here-fixed"
                                bringHere -> "requests.incoming-here"
                                else -> "requests.incoming"
                            }
                            target.player.sendMessage(call.actor.render(Message(key, "player" to from.name, "seconds" to expiration)))
                        }
                    }
            }
        } catch (_: EntityUnavailable) {
            RequestStatus.DISCONNECTED
        }
        when (status) {
            RequestStatus.SENT -> call.actor.reply("requests.sent", "player" to target.name)
            RequestStatus.COOLDOWN -> call.actor.reply("requests.cooldown")
            RequestStatus.DISCONNECTED -> call.actor.reply("requests.player-unavailable")
        }
    }

    private suspend fun respond(call: FeatureCall, player: PlayerSession) {
        val sender = call.argument
        val request = requests.take(player) { sender == null || it.name.equals(sender, ignoreCase = true) }
            ?: return call.actor.reply("requests.no-match")
        if (call.command == FeatureCommand.TPDENY) {
            call.actor.reply("requests.denied", "player" to request.from.name)
            notify(request.from, call.actor.render(Message("requests.denied-notice", "player" to player.name)))
            return
        }
        val mover = if (request.bringHere) player else request.from
        val anchor = if (request.bringHere) request.from else player
        val cooldown = TeleportCooldown(TeleportAction.REQUEST, call.settings.requestTeleportCooldownSeconds)
        val destination = request.destination
        val result = try {
            if (destination == null) teleports.moveTo(mover, anchor, cooldown)
            else teleports.move(mover, destination, cooldown, anchor)
        } catch (_: EntityUnavailable) {
            TeleportResult.DISCONNECTED
        }
        call.teleportResult(result, "requests.completed", "player" to request.from.name)
        val notice = result.failureMessage ?: Message("requests.accepted-notice", "player" to player.name)
        notify(request.from, call.actor.render(notice))
    }

    private suspend fun notify(session: PlayerSession, message: Component) {
        try {
            scheduler.entity(session.player) {
                session.requireActive()
                session.player.sendMessage(message)
            }
        } catch (_: EntityUnavailable) {
        }
    }
}
