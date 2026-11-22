package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.ChatAction
import io.github.Earth1283.quickStart.core.CommandModule
import io.github.Earth1283.quickStart.core.CommandProblem
import io.github.Earth1283.quickStart.core.FeatureCall
import io.github.Earth1283.quickStart.core.FeatureCommand
import io.github.Earth1283.quickStart.core.Message
import io.github.Earth1283.quickStart.core.MessageGroup
import io.github.Earth1283.quickStart.core.SqliteStorage
import io.github.Earth1283.quickStart.core.ToggleSetting
import io.github.Earth1283.quickStart.core.chatPage
import io.github.Earth1283.quickStart.core.navigation
import io.github.Earth1283.quickStart.platform.BackHistory
import io.github.Earth1283.quickStart.platform.EntityUnavailable
import io.github.Earth1283.quickStart.platform.PlayerSession
import io.github.Earth1283.quickStart.platform.Players
import io.github.Earth1283.quickStart.platform.ServerScheduler
import io.github.Earth1283.quickStart.platform.TeleportAction
import io.github.Earth1283.quickStart.platform.TeleportCooldown
import io.github.Earth1283.quickStart.platform.TeleportResult
import io.github.Earth1283.quickStart.platform.Teleports
import java.util.UUID
import net.kyori.adventure.text.Component

internal class Teleportation(
    private val players: Players,
    private val scheduler: ServerScheduler,
    private val teleports: Teleports,
    private val backHistory: BackHistory,
    private val storage: () -> SqliteStorage?,
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
            FeatureCommand.TPREQUESTS -> inbox(call, player)
            FeatureCommand.BACK -> {
                val position = backHistory.get(player) ?: return call.actor.reply("back.unavailable")
                val cooldown = TeleportCooldown(TeleportAction.BACK, call.settings.backCooldownSeconds)
                val result = teleports.move(player, position, cooldown)
                call.teleportResult(result, "back.teleported")
            }
            FeatureCommand.SAFETELEPORT -> toggleSafeTeleport(call, player)
            else -> error("Unsupported teleportation command ${call.command.id}")
        }
    }

    private suspend fun toggleSafeTeleport(call: FeatureCall, player: PlayerSession) {
        val serverDefault = call.settings.safeTeleport
        val sqlite = storage()
        val current = player.safeTeleportOverride ?: sqlite?.preferences(player.id)?.safeTeleport
        val choice = call.argument?.let(ToggleSetting::parse)
        val updated = when (choice) {
            ToggleSetting.STATUS -> current
            ToggleSetting.ON -> persistSafeTeleport(sqlite, player, true)
            ToggleSetting.OFF -> persistSafeTeleport(sqlite, player, false)
            ToggleSetting.DEFAULT -> persistSafeTeleport(sqlite, player, null)
            null -> {
                val effective = current ?: serverDefault
                persistSafeTeleport(sqlite, player, !effective)
            }
        }
        val effective = updated ?: serverDefault
        val stateKey = when {
            updated == null && effective -> "safeteleport.default-on"
            updated == null -> "safeteleport.default-off"
            effective -> "safeteleport.enabled"
            else -> "safeteleport.disabled"
        }
        val replies = buildList {
            add(Message(stateKey))
            if (sqlite == null && updated != null) add(Message("safeteleport.session-only"))
            val actions = buildList {
                val next = if (effective) "off" else "on"
                val key = if (effective) "disable" else "enable"
                add(Message("ui.control", "action" to ChatAction(
                    "safeteleport $next",
                    Message("safeteleport.buttons.$key"),
                    Message("safeteleport.buttons.$key-hover"),
                )))
                if (updated != null) {
                    add(Message("ui.control", "action" to ChatAction(
                        "safeteleport default",
                        Message("safeteleport.buttons.default"),
                        Message("safeteleport.buttons.default-hover"),
                    )))
                }
            }
            add(Message("ui.action-row", "actions" to MessageGroup(actions, Component.text("  "))))
        }
        call.actor.reply(replies)
    }

    private suspend fun persistSafeTeleport(sqlite: SqliteStorage?, player: PlayerSession, value: Boolean?): Boolean? {
        if (sqlite != null) {
            val saved = sqlite.setSafeTeleport(player.id, value).safeTeleport
            player.safeTeleportOverride = null
            return saved
        }
        player.safeTeleportOverride = value
        return value
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
                        if (result is RequestSend.Sent) {
                            target.player.sendMessage(call.actor.render(invitation(result.request, expiration.toLong())))
                        }
                    }
            }
        } catch (_: EntityUnavailable) {
            RequestSend.Disconnected
        }
        when (status) {
            is RequestSend.Sent -> call.actor.reply("requests.sent", "player" to target.name)
            RequestSend.Cooldown -> call.actor.reply("requests.cooldown")
            RequestSend.Disconnected -> call.actor.reply("requests.player-unavailable")
        }
    }

    private suspend fun respond(call: FeatureCall, player: PlayerSession) {
        val sender = call.argument
        val id = sender?.takeIf { it.startsWith('@') }?.substring(1)?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        val request = if (id == null) {
            requests.take(player) { sender == null || it.name.equals(sender, ignoreCase = true) }
        } else {
            requests.takeById(player, id)
        }
        if (request == null) return call.actor.reply(
            if (id == null) "requests.no-match" else "requests.stale",
            "inbox" to inboxAction(),
        )
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

    fun pending(player: PlayerSession): List<PendingRequest<PlayerSession>> = requests.pending(player)

    private suspend fun inbox(call: FeatureCall, player: PlayerSession) {
        val page = pending(player).chatPage(call.page())
        if (page.entries.isEmpty()) return call.actor.reply("requests.empty")
        call.actor.reply(buildList {
            add(Message("requests.heading"))
            for ((request, remaining) in page.entries) add(invitation(request, remaining, showInbox = false))
            add(page.navigation(call.command))
        })
    }

    private fun invitation(
        request: TeleportRequest<PlayerSession>,
        remaining: Long,
        showInbox: Boolean = true,
    ): Message {
        val player = request.from.name
        val variant = when {
            request.destination != null -> "incoming-here-fixed"
            request.bringHere -> "incoming-here"
            else -> "incoming"
        }
        val accept = ChatAction(
            "tpaccept @${request.id}",
            Message("requests.accept"),
            Message("requests.$variant-hover", "player" to player, "seconds" to remaining),
        )
        val deny = ChatAction(
            "tpdeny @${request.id}",
            Message("requests.deny"),
            Message("requests.deny-hover", "player" to player),
        )
        return Message(
            "requests.$variant",
            "player" to player,
            "seconds" to remaining,
            "accept" to accept,
            "deny" to deny,
            "inbox" to if (showInbox) inboxAction() else Component.empty(),
            requestId = request.id,
        )
    }

    private fun inboxAction() = ChatAction(
        "tprequests",
        Message("requests.inbox"),
        Message("help.details.tprequests"),
    )

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
