package io.github.Earth1283.quickStart.core

import java.io.File
import java.util.Locale
import org.bukkit.configuration.file.YamlConfiguration

internal enum class StartupStrategy { LAZY, ADHD, ALL_AT_ONCE }

internal data class FeatureSettings(
    val homeLimit: Int,
    val homeCooldownSeconds: Int,
    val homeNameMaxLength: Int,
    val perWarpPermissions: Boolean,
    val warpCooldownSeconds: Int,
    val warpNameMaxLength: Int,
    val firstJoinSpawn: Boolean,
    val spawnOnRespawn: Boolean,
    val respectBedSpawn: Boolean,
    val spawnOnJoin: Boolean,
    val spawnCooldownSeconds: Int,
    val requestSendCooldownSeconds: Int,
    val requestTeleportCooldownSeconds: Int,
    val backCooldownSeconds: Int,
    val teleportWarmupSeconds: Int,
    val requestExpirationSeconds: Int,
    val strictTpahere: Boolean,
    val backOnDeath: Boolean,
    val backOnTeleport: Boolean,
    val safeTeleport: Boolean,
)

internal data class WarmupSettings(
    val enabled: Boolean,
    val emptyServerWorkers: Int,
    val activeServerWorkers: Int,
    val pauseOnJoin: Boolean,
) {
    fun workers(onlinePlayers: Int): Int = when {
        !enabled -> 0
        onlinePlayers == 0 -> emptyServerWorkers
        pauseOnJoin -> 0
        else -> activeServerWorkers
    }
}

internal data class Configuration(
    val startup: StartupStrategy,
    val enabledModules: Set<String>,
    val warmup: WarmupSettings,
    val features: FeatureSettings,
    val maxPendingOperations: Int = 128,
) {
    companion object {
        val featureIds = listOf("teleportation", "homes", "warps", "spawn", "moderation", "seen")

        fun load(file: File): Configuration = parse(YamlConfiguration().apply { load(file) })

        fun parse(yaml: YamlConfiguration): Configuration {
            val values = ConfigValues(yaml)
            val startup = values.string("startup", "ADHD").uppercase(Locale.ROOT)
            val strategy = StartupStrategy.entries.firstOrNull { it.name == startup }
                ?: throw ConfigurationException("startup must be LAZY, ADHD, or ALL_AT_ONCE")
            val modules = featureIds.filter { values.boolean("modules.$it", true) }.toSet()
            val warmup = WarmupSettings(
                values.boolean("performance.warmup.enabled", true),
                values.integer("performance.warmup.empty-server-workers", 2, 0..16),
                values.integer("performance.warmup.active-server-workers", 1, 0..16),
                values.boolean("performance.warmup.pause-optional-on-join", true),
            )
            if (warmup.activeServerWorkers > warmup.emptyServerWorkers) {
                throw ConfigurationException("performance.warmup.active-server-workers must not exceed empty-server-workers")
            }
            val storageBackend = values.string("storage.backend", "sqlite")
            if (storageBackend != "sqlite") {
                throw ConfigurationException("storage.backend must be sqlite")
            }
            val maxPendingOperations = values.integer("storage.max-pending-operations", 128, 8..4096)
            for (path in listOf("telemetry.enabled", "scope-creep.scripting.enabled")) {
                if (values.boolean(path, false)) throw ConfigurationException("$path must be false in this version")
            }
            val homeNameMaxLength = values.integer("homes.name-max-length", 32, 1..32)
            val warpNameMaxLength = values.integer("warps.name-max-length", 32, 1..32)
            val teleportCooldown = values.integer("teleportation.cooldown-seconds", 5, 0..3600)
            val features = FeatureSettings(
                values.integer("homes.limit", 5, 1..1000),
                values.integer("homes.cooldown-seconds", 0, 0..3600),
                homeNameMaxLength,
                values.boolean("warps.per-warp-permissions", false),
                values.integer("warps.cooldown-seconds", 0, 0..3600),
                warpNameMaxLength,
                values.boolean("spawn.first-join", false),
                values.boolean("spawn.on-respawn", false),
                values.boolean("spawn.respect-bed-spawn", true),
                values.boolean("spawn.on-join", false),
                values.integer("spawn.cooldown-seconds", 0, 0..3600),
                values.integer("teleportation.request-send-cooldown-seconds", teleportCooldown, 0..3600),
                values.integer("teleportation.request-teleport-cooldown-seconds", teleportCooldown, 0..3600),
                values.integer("teleportation.back-cooldown-seconds", teleportCooldown, 0..3600),
                values.integer("teleportation.warmup-seconds", 3, 0..3600),
                values.integer("teleportation.request-expiration-seconds", 60, 1..3600),
                values.boolean("teleportation.strict-tpahere", false),
                values.boolean("teleportation.back-on-death", true),
                values.boolean("teleportation.back-on-teleport", true),
                values.boolean("teleportation.safe-teleport", true),
            )
            return Configuration(strategy, modules, warmup, features, maxPendingOperations)
        }
    }
}

internal class ConfigurationException(message: String) : IllegalArgumentException(message)

private class ConfigValues(private val yaml: YamlConfiguration) {
    fun string(path: String, default: String): String =
        read(path, default) as? String ?: throw ConfigurationException("$path must be a string")

    fun boolean(path: String, default: Boolean): Boolean =
        read(path, default) as? Boolean ?: throw ConfigurationException("$path must be true or false")

    fun integer(path: String, default: Int, range: IntRange): Int =
        (read(path, default) as? Int)?.takeIf { it in range }
            ?: throw ConfigurationException("$path must be an integer from ${range.first} to ${range.last}")

    private fun read(path: String, default: Any): Any {
        val sections = path.split('.').dropLast(1)
        for (index in sections.indices) {
            val section = sections.take(index + 1).joinToString(".")
            if (yaml.contains(section) && !yaml.isConfigurationSection(section)) {
                throw ConfigurationException("$section must be a configuration section")
            }
        }
        return yaml.get(path) ?: default
    }
}
