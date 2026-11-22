package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.ChatAction
import io.github.Earth1283.quickStart.core.CommandModule
import io.github.Earth1283.quickStart.core.DestinationGroup
import io.github.Earth1283.quickStart.core.FeatureCall
import io.github.Earth1283.quickStart.core.FeatureCommand
import io.github.Earth1283.quickStart.core.Message
import io.github.Earth1283.quickStart.core.MessageGroup
import io.github.Earth1283.quickStart.core.SqliteStorage
import io.github.Earth1283.quickStart.core.ToggleSetting
import io.github.Earth1283.quickStart.platform.PlayerSession
import io.github.Earth1283.quickStart.platform.ServerScheduler
import io.github.Earth1283.quickStart.platform.TeleportAction
import io.github.Earth1283.quickStart.platform.TeleportCooldown
import io.github.Earth1283.quickStart.platform.Teleports
import io.github.Earth1283.quickStart.platform.snapshot
import net.kyori.adventure.text.Component
import org.bukkit.Server

internal class Spawn(
    private val storage: SqliteStorage,
    private val teleports: Teleports,
    private val server: Server,
    private val scheduler: ServerScheduler,
) : CommandModule {
    private val spawn = DestinationGroup.spawn

    override suspend fun execute(call: FeatureCall) {
        val player = call.actor.player()
        when (call.command) {
            FeatureCommand.SETSPAWN -> {
                storage.save(spawn, "spawn", teleports.position(player))
                call.actor.reply("spawn.saved")
            }
            FeatureCommand.SPAWN -> {
                val position = storage.get(spawn, "spawn") ?: scheduler.global {
                    server.worlds.firstOrNull()?.spawnLocation?.snapshot()
                }
                if (position == null) return call.actor.reply("spawn.unavailable")
                val cooldown = TeleportCooldown(TeleportAction.SPAWN, call.settings.spawnCooldownSeconds)
                val result = teleports.move(player, position, cooldown)
                call.teleportResult(result, "spawn.teleported")
            }
            FeatureCommand.BEDSPAWN -> toggleBedSpawn(call, player)
            else -> error("Unsupported spawn command ${call.command.id}")
        }
    }

    suspend fun placeFirstJoin(player: PlayerSession): Message? {
        val position = storage.get(spawn, "spawn") ?: return null
        return teleports.move(player, position, warmup = false, validateSafety = false).failureMessage
    }

    private suspend fun toggleBedSpawn(call: FeatureCall, player: PlayerSession) {
        val serverDefault = call.settings.respectBedSpawn
        val current = storage.preferences(player.id).respectBedSpawn
        val choice = call.argument?.let(ToggleSetting::parse)
        val updated = when (choice) {
            ToggleSetting.STATUS -> current
            ToggleSetting.ON -> storage.setRespectBedSpawn(player.id, true).respectBedSpawn
            ToggleSetting.OFF -> storage.setRespectBedSpawn(player.id, false).respectBedSpawn
            ToggleSetting.DEFAULT -> storage.setRespectBedSpawn(player.id, null).respectBedSpawn
            null -> {
                val effective = current ?: serverDefault
                storage.setRespectBedSpawn(player.id, !effective).respectBedSpawn
            }
        }
        val effective = updated ?: serverDefault
        val stateKey = when {
            updated == null && effective -> "bedspawn.default-on"
            updated == null -> "bedspawn.default-off"
            effective -> "bedspawn.enabled"
            else -> "bedspawn.disabled"
        }
        val replies = buildList {
            add(Message(stateKey))
            if (!call.settings.spawnOnRespawn) add(Message("bedspawn.respawn-inactive"))
            val actions = buildList {
                val next = if (effective) "off" else "on"
                val key = if (effective) "disable" else "enable"
                add(Message("ui.control", "action" to ChatAction(
                    "bedspawn $next",
                    Message("bedspawn.buttons.$key"),
                    Message("bedspawn.buttons.$key-hover"),
                )))
                if (updated != null) {
                    add(Message("ui.control", "action" to ChatAction(
                        "bedspawn default",
                        Message("bedspawn.buttons.default"),
                        Message("bedspawn.buttons.default-hover"),
                    )))
                }
            }
            add(Message("ui.action-row", "actions" to MessageGroup(actions, Component.text("  "))))
        }
        call.actor.reply(replies)
    }
}
