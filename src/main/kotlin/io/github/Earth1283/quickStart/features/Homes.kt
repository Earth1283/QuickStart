package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.CommandModule
import io.github.Earth1283.quickStart.core.DestinationGroup
import io.github.Earth1283.quickStart.core.FeatureCall
import io.github.Earth1283.quickStart.core.FeatureCommand
import io.github.Earth1283.quickStart.core.SaveResult
import io.github.Earth1283.quickStart.core.SqliteStorage
import io.github.Earth1283.quickStart.core.replyWithNames
import io.github.Earth1283.quickStart.platform.TeleportAction
import io.github.Earth1283.quickStart.platform.TeleportCooldown
import io.github.Earth1283.quickStart.platform.Teleports

internal class Homes(private val storage: SqliteStorage, private val teleports: Teleports) : CommandModule {
    override suspend fun execute(call: FeatureCall) {
        val actor = call.actor
        val player = actor.player()
        val homes = DestinationGroup.homes(player.id)
        when (call.command) {
            FeatureCommand.SETHOME -> {
                val name = call.name()
                val limit = call.settings.homeLimit
                val result = storage.save(homes, name, teleports.position(player), limit)
                when (result) {
                    SaveResult.CREATED -> actor.reply("homes.saved", "name" to name)
                    SaveResult.UPDATED -> actor.reply("homes.updated", "name" to name)
                    SaveResult.LIMIT_REACHED -> actor.reply("homes.limit", "limit" to limit)
                }
            }
            FeatureCommand.HOME -> {
                val name = call.name()
                val position = storage.get(homes, name) ?: return actor.reply("homes.not-found", "name" to name)
                val cooldown = TeleportCooldown(TeleportAction.HOME, call.settings.homeCooldownSeconds)
                val result = teleports.move(player, position, cooldown)
                call.teleportResult(result, "homes.teleported", "name" to name)
            }
            FeatureCommand.DELHOME -> {
                val name = call.name()
                val removed = storage.delete(homes, name)
                actor.reply(if (removed) "homes.deleted" else "homes.not-found", "name" to name)
            }
            FeatureCommand.HOMES -> {
                val names = storage.names(homes)
                replyWithNames(actor, names, "homes")
            }
            else -> error("Unsupported homes command ${call.command.id}")
        }
    }
}
