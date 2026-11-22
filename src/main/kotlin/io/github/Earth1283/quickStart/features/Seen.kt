package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.ChatAction
import io.github.Earth1283.quickStart.core.CommandModule
import io.github.Earth1283.quickStart.core.FeatureCall
import io.github.Earth1283.quickStart.core.FeatureCommand
import io.github.Earth1283.quickStart.core.Message
import io.github.Earth1283.quickStart.core.MessageGroup
import io.github.Earth1283.quickStart.core.SanctionKind
import io.github.Earth1283.quickStart.core.SqliteStorage
import io.github.Earth1283.quickStart.core.formatDuration
import io.github.Earth1283.quickStart.core.formatTimestamp
import io.github.Earth1283.quickStart.core.parseIp
import io.github.Earth1283.quickStart.platform.EntityUnavailable
import io.github.Earth1283.quickStart.platform.PlayerSession
import io.github.Earth1283.quickStart.platform.Players
import io.github.Earth1283.quickStart.platform.ServerScheduler
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.floor
import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.bukkit.entity.Player

internal class Seen(
    private val storage: SqliteStorage,
    private val players: Players,
    private val scheduler: ServerScheduler,
    private val server: Server,
    private val moderationEnabled: Boolean,
) : CommandModule {
    override suspend fun execute(call: FeatureCall) {
        if (call.command != FeatureCommand.SEEN) error("Unsupported seen command ${call.command.id}")
        val actor = call.actor
        val query = call.name()
        val parsedId = parseUuid(query)
        val matchedOnline = (parsedId?.let(players::find) ?: players.find(query))?.takeIf(PlayerSession::active)

        val (showDetails, canSeeOnline, modActions) = actor.onOwner { sender ->
            val details = sender.hasPermission("quickstart.seen.details")
            val visible = matchedOnline != null && (details || sender !is Player || sender.canSee(matchedOnline.player))
            val buttons = if (moderationEnabled) {
                ModerationCommand.entries
                    .filter { it in inspectionButtons && sender.hasPermission(it.permission) }
            } else emptyList()
            val canOpenMenu = moderationEnabled && ModerationCommand.entries.any {
                it.action != ModerationAction.LIST && sender.hasPermission(it.permission)
            }
            Triple(details, visible, buttons to canOpenMenu)
        }
        val (modButtons, canOpenMenu) = modActions

        val now = Instant.now()
        if (matchedOnline != null && canSeeOnline) {
            val profile = storage.playerProfile(matchedOnline.id)
            val duration = formatDuration(Duration.between(matchedOnline.joinedAt, now))
            val replies = buildList {
                add(Message("seen.online", "player" to matchedOnline.name, "duration" to duration))
                val firstSeen = profile?.firstSeen ?: matchedOnline.joinedAt
                add(Message("seen.first-joined", "time" to formatUtc(firstSeen)))
                if (showDetails) {
                    add(Message("seen.uuid", "uuid" to matchedOnline.id.toString()))
                    matchedOnline.ipString?.let { add(Message("seen.ip", "ip" to it)) }
                    liveLocation(matchedOnline)?.let { (world, x, y, z) ->
                        add(Message("seen.location", "world" to world, "x" to x, "y" to y, "z" to z))
                    }
                    if (moderationEnabled && storage.sanctions.find(SanctionKind.MUTE, matchedOnline.id, matchedOnline.address) != null) {
                        add(Message("seen.muted"))
                    }
                    staffActions(modButtons, canOpenMenu, matchedOnline.name)?.let(::add)
                }
            }
            actor.reply(replies)
            return
        }

        val profile = parsedId?.let { storage.playerProfile(it) } ?: storage.findPlayerProfileByName(query)
        if (profile != null) {
            val lastSeen = profile.lastQuit ?: profile.lastSeen
            val ago = formatDuration(Duration.between(lastSeen, now))
            val replies = buildList {
                add(Message("seen.offline", "player" to profile.name, "duration" to ago, "time" to formatUtc(lastSeen)))
                add(Message("seen.first-joined", "time" to formatUtc(profile.firstSeen)))
                if (showDetails) {
                    add(Message("seen.uuid", "uuid" to profile.id.toString()))
                    profile.lastIp?.let { add(Message("seen.last-ip", "ip" to it)) }
                    profile.lastPosition?.let { pos ->
                        val worldName = scheduler.global { server.getWorld(pos.worldId)?.name } ?: pos.worldId.toString()
                        add(Message(
                            "seen.last-location",
                            "world" to worldName,
                            "x" to floor(pos.x).toInt(),
                            "y" to floor(pos.y).toInt(),
                            "z" to floor(pos.z).toInt(),
                        ))
                    }
                    if (moderationEnabled) {
                        val ip = profile.lastIp?.let(::parseIp)
                        if (storage.sanctions.find(SanctionKind.BAN, profile.id, ip) != null) add(Message("seen.banned"))
                        if (storage.sanctions.find(SanctionKind.MUTE, profile.id, ip) != null) add(Message("seen.muted"))
                    }
                    staffActions(modButtons, canOpenMenu, profile.id.toString(), profile.name)?.let(::add)
                }
            }
            actor.reply(replies)
            return
        }

        val cached = scheduler.global {
            val offline = if (parsedId != null) server.getOfflinePlayer(parsedId) else server.getOfflinePlayerIfCached(query)
            offline?.takeIf { it.hasPlayedBefore() }?.let {
                Triple(it.uniqueId, it.name ?: query, Instant.ofEpochMilli(it.lastSeen) to Instant.ofEpochMilli(it.firstPlayed))
            }
        }
        if (cached == null) {
            actor.reply("seen.unknown", "player" to query)
            return
        }
        val (id, name, times) = cached
        val (lastSeen, firstSeen) = times
        val ago = formatDuration(Duration.between(lastSeen, now))
        val replies = buildList {
            add(Message("seen.offline", "player" to name, "duration" to ago, "time" to formatUtc(lastSeen)))
            if (firstSeen.toEpochMilli() > 0) add(Message("seen.first-joined", "time" to formatUtc(firstSeen)))
            if (showDetails) {
                add(Message("seen.uuid", "uuid" to id.toString()))
                if (moderationEnabled) {
                    if (storage.sanctions.find(SanctionKind.BAN, id, null) != null) add(Message("seen.banned"))
                    if (storage.sanctions.find(SanctionKind.MUTE, id, null) != null) add(Message("seen.muted"))
                }
                staffActions(modButtons, canOpenMenu, id.toString(), name)?.let(::add)
            }
        }
        actor.reply(replies)
    }

    private suspend fun liveLocation(session: PlayerSession): LocationSnapshot? = try {
        scheduler.entity(session.player) {
            session.requireActive()
            val loc = session.player.location
            LocationSnapshot(loc.world.name, loc.blockX, loc.blockY, loc.blockZ)
        }
    } catch (_: EntityUnavailable) {
        null
    }

    private fun staffActions(
        commands: List<ModerationCommand>,
        canOpenMenu: Boolean,
        target: String,
        menuTarget: String = target,
    ): Message? {
        val buttons = buildList {
            for (command in commands) {
                val key = when (command) {
                    ModerationCommand.ALTS -> "alts"
                    ModerationCommand.BANSTATUS -> "status"
                    ModerationCommand.MODHISTORY -> "history"
                    else -> command.id
                }
                val value = "${command.id} $target"
                add(Message("ui.control", "action" to ChatAction(
                    value,
                    Message("moderation.buttons.$key"),
                    Message("moderation.buttons.hover", "command" to value),
                )))
            }
            if (canOpenMenu) {
                val value = "qs mod $menuTarget"
                add(Message("ui.control", "action" to ChatAction(
                    value,
                    Message("moderation.buttons.moderate"),
                    Message("moderation.buttons.hover", "command" to value),
                )))
            }
        }
        if (buttons.isEmpty()) return null
        return Message("ui.action-row", "actions" to MessageGroup(buttons, Component.text("  ")))
    }

    private data class LocationSnapshot(val world: String, val x: Int, val y: Int, val z: Int)

    private companion object {
        val inspectionButtons = listOf(ModerationCommand.ALTS, ModerationCommand.BANSTATUS, ModerationCommand.MODHISTORY)

        fun formatUtc(instant: Instant): String = formatTimestamp(instant)

        fun parseUuid(value: String): UUID? = try {
            UUID.fromString(value).takeIf { it.toString().equals(value, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
