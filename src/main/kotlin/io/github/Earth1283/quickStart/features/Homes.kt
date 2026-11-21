package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.CommandModule
import io.github.Earth1283.quickStart.core.DestinationGroup
import io.github.Earth1283.quickStart.core.FeatureCall
import io.github.Earth1283.quickStart.core.FeatureCommand
import io.github.Earth1283.quickStart.core.SaveResult
import io.github.Earth1283.quickStart.core.SqliteStorage
import io.github.Earth1283.quickStart.core.replyWithNames
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
                actor.reply(when (result) {
                    SaveResult.CREATED -> "Home '$name' saved."
                    SaveResult.UPDATED -> "Home '$name' updated."
                    SaveResult.LIMIT_REACHED -> "You have reached your limit of $limit homes."
                })
            }
            FeatureCommand.HOME -> {
                val name = call.name()
                val position = storage.get(homes, name) ?: return actor.reply("Home '$name' was not found.")
                call.teleportResult(teleports.move(player, position, call.settings.homeCooldownSeconds.takeIf { it > 0 }), "Teleported to home '$name'.")
            }
            FeatureCommand.DELHOME -> {
                val name = call.name()
                val removed = storage.delete(homes, name)
                actor.reply(if (removed) "Home '$name' deleted." else "Home '$name' was not found.")
            }
            FeatureCommand.HOMES -> {
                val names = storage.names(homes)
                replyWithNames(actor, names, "Homes", "You have no saved homes.")
            }
            else -> error("Unsupported homes command ${call.command.id}")
        }
    }
}
