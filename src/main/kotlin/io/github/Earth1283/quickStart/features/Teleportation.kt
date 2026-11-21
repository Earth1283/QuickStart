package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.CommandModule
import io.github.Earth1283.quickStart.core.CommandProblem
import io.github.Earth1283.quickStart.core.FeatureCall
import io.github.Earth1283.quickStart.core.FeatureCommand
import io.github.Earth1283.quickStart.platform.BackHistory
import io.github.Earth1283.quickStart.platform.EntityUnavailable
import io.github.Earth1283.quickStart.platform.PlayerSession
import io.github.Earth1283.quickStart.platform.Players
import io.github.Earth1283.quickStart.platform.ServerScheduler
import io.github.Earth1283.quickStart.platform.TeleportResult
import io.github.Earth1283.quickStart.platform.Teleports

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
                val position = backHistory.get(player) ?: return call.actor.reply("No previous location is available.")
                val result = teleports.move(player, position, call.settings.teleportCooldownSeconds)
                call.teleportResult(result, "Returned to your previous location.")
            }
            else -> error("Unsupported teleportation command ${call.command.id}")
        }
    }

    private suspend fun send(call: FeatureCall, from: PlayerSession) {
        val target = players.find(call.name()) ?: throw CommandProblem("That player is unavailable.")
        if (from === target) throw CommandProblem("You cannot request a teleport with yourself.")
        val bringHere = call.command == FeatureCommand.TPAHERE
        val status = try {
            scheduler.entity(target.player) {
                target.requireActive()
                if (!target.player.hasPermission("quickstart.tpa")) throw CommandProblem("That player is unavailable.")
                requests.send(from, target, bringHere, call.settings.teleportCooldownSeconds, call.settings.requestExpirationSeconds)
                    .also { result ->
                        if (result == RequestStatus.SENT) {
                            val action = if (bringHere) "asked you to teleport to them" else "asked to teleport to you"
                            target.player.sendMessage("${from.name} $action. /tpaccept ${from.name} or /tpdeny ${from.name}")
                        }
                    }
            }
        } catch (_: EntityUnavailable) {
            RequestStatus.DISCONNECTED
        }
        call.actor.reply(when (status) {
            RequestStatus.SENT -> "Teleport request sent to ${target.name}."
            RequestStatus.COOLDOWN -> "Please wait before sending another teleport request."
            RequestStatus.DISCONNECTED -> "That player is unavailable."
        })
    }

    private suspend fun respond(call: FeatureCall, player: PlayerSession) {
        val sender = call.argument
        val request = requests.take(player) { sender == null || it.name.equals(sender, ignoreCase = true) }
            ?: return call.actor.reply("You have no matching teleport request.")
        if (call.command == FeatureCommand.TPDENY) {
            call.actor.reply("Teleport request from ${request.from.name} denied.")
            notify(request.from, "${player.name} denied your teleport request.")
            return
        }
        val mover = if (request.bringHere) player else request.from
        val anchor = if (request.bringHere) request.from else player
        val result = try {
            val position = teleports.position(anchor)
            teleports.move(mover, position, call.settings.teleportCooldownSeconds) { anchor.active }
        } catch (_: EntityUnavailable) {
            TeleportResult.DISCONNECTED
        }
        call.teleportResult(result, "Teleport request from ${request.from.name} completed.")
        notify(request.from, result.failureMessage ?: "${player.name} accepted your teleport request.")
    }

    private suspend fun notify(session: PlayerSession, message: String) {
        try {
            scheduler.entity(session.player) {
                session.requireActive()
                session.player.sendMessage(message)
            }
        } catch (_: EntityUnavailable) {
        }
    }
}
