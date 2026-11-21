package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.CommandModule
import io.github.Earth1283.quickStart.core.DestinationGroup
import io.github.Earth1283.quickStart.core.FeatureCall
import io.github.Earth1283.quickStart.core.FeatureCommand
import io.github.Earth1283.quickStart.core.SqliteStorage
import io.github.Earth1283.quickStart.platform.PlayerSession
import io.github.Earth1283.quickStart.platform.ServerScheduler
import io.github.Earth1283.quickStart.platform.Teleports
import io.github.Earth1283.quickStart.platform.snapshot
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
                call.actor.reply("Server spawn saved.")
            }
            FeatureCommand.SPAWN -> {
                val position = storage.get(spawn, "spawn") ?: scheduler.global {
                    server.worlds.firstOrNull()?.spawnLocation?.snapshot()
                }
                if (position == null) return call.actor.reply("Server spawn is unavailable.")
                call.teleportResult(teleports.move(player, position), "Teleported to spawn.")
            }
            else -> error("Unsupported spawn command ${call.command.id}")
        }
    }

    suspend fun placeFirstJoin(player: PlayerSession): String? {
        val position = storage.get(spawn, "spawn") ?: return null
        return teleports.move(player, position).failureMessage
    }
}
