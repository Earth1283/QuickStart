package io.github.Earth1283.quickStart.core

import java.io.File
import java.util.Locale
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

internal enum class StartupStrategy { LAZY, ADHD, ALL_AT_ONCE }

internal data class ModerationSettings(
    val broadcastToStaff: Boolean = true,
    val logToConsole: Boolean = true,
    val maxReasonLength: Int = DEFAULT_MAX_REASON_LENGTH,
    val requireReasonFor: Set<String> = DEFAULT_REQUIRE_REASON_FOR,
    val minIpv4CidrPrefix: Int = DEFAULT_MIN_IPV4_CIDR_PREFIX,
    val minIpv6CidrPrefix: Int = DEFAULT_MIN_IPV6_CIDR_PREFIX,
    val mutedCommands: Set<String> = DEFAULT_MUTED_COMMANDS,
    val reasonPresets: List<String> = DEFAULT_REASON_PRESETS,
) {
    companion object {
        const val DEFAULT_MAX_REASON_LENGTH = 1024
        const val DEFAULT_MIN_IPV4_CIDR_PREFIX = 16
        const val DEFAULT_MIN_IPV6_CIDR_PREFIX = 32
        val DEFAULT_REQUIRE_REASON_FOR: Set<String> = setOf("tempban")
        val DEFAULT_MUTED_COMMANDS: Set<String> = setOf(
            "me", "msg", "tell", "w", "teammsg", "tm", "message", "m", "whisper", "pm", "reply", "r", "say", "tellraw",
        )
        val DEFAULT_REASON_PRESETS: List<String> = listOf(
            "Spam or advertising",
            "Harassment or disrespect",
            "Griefing or stealing",
            "Cheating or unfair advantage",
        )
        val REASON_COMMAND_IDS: Set<String> = setOf(
            "ban", "tempban", "unban",
            "ipban", "tempipban", "unbanip",
            "iprangeban", "tempiprangeban", "unbaniprange",
            "mute", "tempmute", "unmute",
            "ipmute", "tempipmute", "unmuteip",
            "kick",
        )
    }
}

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
    val moderation: ModerationSettings = ModerationSettings(),
) {
    fun diff(other: FeatureSettings): List<SettingChange> = buildList {
        fun <T> compare(key: String, before: T, after: T, format: (T) -> String = { it.toString() }) {
            if (before != after) add(SettingChange(key, format(before), format(after)))
        }
        compare("homes.limit", homeLimit, other.homeLimit)
        compare("homes.cooldown-seconds", homeCooldownSeconds, other.homeCooldownSeconds)
        compare("homes.name-max-length", homeNameMaxLength, other.homeNameMaxLength)
        compare("warps.per-warp-permissions", perWarpPermissions, other.perWarpPermissions)
        compare("warps.cooldown-seconds", warpCooldownSeconds, other.warpCooldownSeconds)
        compare("warps.name-max-length", warpNameMaxLength, other.warpNameMaxLength)
        compare("spawn.first-join", firstJoinSpawn, other.firstJoinSpawn)
        compare("spawn.on-respawn", spawnOnRespawn, other.spawnOnRespawn)
        compare("spawn.respect-bed-spawn", respectBedSpawn, other.respectBedSpawn)
        compare("spawn.on-join", spawnOnJoin, other.spawnOnJoin)
        compare("spawn.cooldown-seconds", spawnCooldownSeconds, other.spawnCooldownSeconds)
        compare("teleportation.warmup-seconds", teleportWarmupSeconds, other.teleportWarmupSeconds)
        compare("teleportation.request-send-cooldown-seconds", requestSendCooldownSeconds, other.requestSendCooldownSeconds)
        compare("teleportation.request-teleport-cooldown-seconds", requestTeleportCooldownSeconds, other.requestTeleportCooldownSeconds)
        compare("teleportation.back-cooldown-seconds", backCooldownSeconds, other.backCooldownSeconds)
        compare("teleportation.request-expiration-seconds", requestExpirationSeconds, other.requestExpirationSeconds)
        compare("teleportation.strict-tpahere", strictTpahere, other.strictTpahere)
        compare("teleportation.back-on-death", backOnDeath, other.backOnDeath)
        compare("teleportation.back-on-teleport", backOnTeleport, other.backOnTeleport)
        compare("teleportation.safe-teleport", safeTeleport, other.safeTeleport)
        compare("moderation.broadcast-to-staff", moderation.broadcastToStaff, other.moderation.broadcastToStaff)
        compare("moderation.log-to-console", moderation.logToConsole, other.moderation.logToConsole)
        compare("moderation.max-reason-length", moderation.maxReasonLength, other.moderation.maxReasonLength)
        compare("moderation.require-reason-for", moderation.requireReasonFor, other.moderation.requireReasonFor) {
            it.sorted().joinToString(", ").ifEmpty { "none" }
        }
        compare("moderation.min-ipv4-cidr-prefix", moderation.minIpv4CidrPrefix, other.moderation.minIpv4CidrPrefix) { "/$it" }
        compare("moderation.min-ipv6-cidr-prefix", moderation.minIpv6CidrPrefix, other.moderation.minIpv6CidrPrefix) { "/$it" }
        compare("moderation.muted-commands", moderation.mutedCommands, other.moderation.mutedCommands) { "${it.size} commands" }
        compare("moderation.reason-presets", moderation.reasonPresets, other.moderation.reasonPresets) { "${it.size} presets" }
    }
}

internal data class SettingChange(val key: String, val before: String, val after: String)

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

internal data class ConfigurationLoadResult(
    val configuration: Configuration,
    val warnings: List<String>,
    val missingKeys: List<String>,
)

internal data class Configuration(
    val startup: StartupStrategy,
    val enabledModules: Set<String>,
    val warmup: WarmupSettings,
    val features: FeatureSettings,
    val maxPendingOperations: Int = 128,
) {
    companion object {
        val featureIds = listOf("teleportation", "homes", "warps", "spawn", "moderation", "seen")

        val expectedKeys: Set<String> = linkedSetOf(
            "startup",
            "modules.homes",
            "modules.warps",
            "modules.spawn",
            "modules.teleportation",
            "modules.moderation",
            "modules.seen",
            "homes.limit",
            "homes.cooldown-seconds",
            "homes.name-max-length",
            "warps.per-warp-permissions",
            "warps.cooldown-seconds",
            "warps.name-max-length",
            "spawn.first-join",
            "spawn.on-respawn",
            "spawn.respect-bed-spawn",
            "spawn.on-join",
            "spawn.cooldown-seconds",
            "teleportation.warmup-seconds",
            "teleportation.cooldown-seconds",
            "teleportation.request-expiration-seconds",
            "teleportation.strict-tpahere",
            "teleportation.back-on-death",
            "teleportation.back-on-teleport",
            "teleportation.safe-teleport",
            "moderation.broadcast-to-staff",
            "moderation.log-to-console",
            "moderation.max-reason-length",
            "moderation.require-reason-for",
            "moderation.min-ipv4-cidr-prefix",
            "moderation.min-ipv6-cidr-prefix",
            "moderation.muted-commands",
            "moderation.reason-presets",
            "performance.warmup.enabled",
            "performance.warmup.empty-server-workers",
            "performance.warmup.active-server-workers",
            "performance.warmup.pause-optional-on-join",
            "storage.backend",
            "storage.max-pending-operations",
            "telemetry.enabled",
            "scope-creep.scripting.enabled",
        )

        val optionalKeys: Set<String> = setOf(
            "teleportation.request-send-cooldown-seconds",
            "teleportation.request-teleport-cooldown-seconds",
            "teleportation.back-cooldown-seconds",
        )

        val knownKeys: Set<String> = expectedKeys + optionalKeys

        fun load(file: File): Configuration = loadWithDiagnostics(file).configuration

        fun loadWithDiagnostics(file: File): ConfigurationLoadResult =
            parseWithDiagnostics(YamlConfiguration().apply { load(file) })

        fun parse(yaml: YamlConfiguration): Configuration = parseWithDiagnostics(yaml).configuration

        fun parseWithDiagnostics(yaml: YamlConfiguration): ConfigurationLoadResult {
            val values = ConfigValues(yaml)
            val startup = values.string("startup", "ADHD").uppercase(Locale.ROOT)
            val strategy = StartupStrategy.entries.firstOrNull { it.name == startup } ?: run {
                values.error("startup must be LAZY, ADHD, or ALL_AT_ONCE")
                StartupStrategy.ADHD
            }
            val modules = featureIds.filter { values.boolean("modules.$it", true) }.toSet()
            val warmup = WarmupSettings(
                values.boolean("performance.warmup.enabled", true),
                values.integer("performance.warmup.empty-server-workers", 2, 0..16),
                values.integer("performance.warmup.active-server-workers", 1, 0..16),
                values.boolean("performance.warmup.pause-optional-on-join", true),
            )
            if (warmup.activeServerWorkers > warmup.emptyServerWorkers) {
                values.error("performance.warmup.active-server-workers must not exceed empty-server-workers")
            }
            val storageBackend = values.string("storage.backend", "sqlite")
            if (storageBackend != "sqlite") {
                values.error("storage.backend must be sqlite")
            }
            val maxPendingOperations = values.integer("storage.max-pending-operations", 128, 8..4096)
            for (path in listOf("telemetry.enabled", "scope-creep.scripting.enabled")) {
                if (values.boolean(path, false)) values.error("$path must be false in this version")
            }
            val homeNameMaxLength = values.integer("homes.name-max-length", 32, 1..32)
            val warpNameMaxLength = values.integer("warps.name-max-length", 32, 1..32)
            val teleportCooldown = values.integer("teleportation.cooldown-seconds", 5, 0..3600)

            val requireReasonRaw = values.stringList(
                "moderation.require-reason-for",
                ModerationSettings.DEFAULT_REQUIRE_REASON_FOR.toList(),
            )
            val requireReasonFor = buildSet {
                for (entry in requireReasonRaw) {
                    val normalized = entry.trim().lowercase(Locale.ROOT).removePrefix("/")
                    if (normalized !in ModerationSettings.REASON_COMMAND_IDS) {
                        values.error(
                            "moderation.require-reason-for contains unknown command '$entry' " +
                                "(allowed: ${ModerationSettings.REASON_COMMAND_IDS.sorted().joinToString(", ")})",
                        )
                    } else {
                        add(normalized)
                    }
                }
            }
            val mutedCommandsRaw = values.stringList(
                "moderation.muted-commands",
                ModerationSettings.DEFAULT_MUTED_COMMANDS.toList(),
            )
            val mutedCommands = mutedCommandsRaw.mapNotNull { entry ->
                entry.trim().removePrefix("/").lowercase(Locale.ROOT).takeIf(String::isNotEmpty)
            }.toSet()
            val reasonPresets = values.stringList(
                "moderation.reason-presets",
                ModerationSettings.DEFAULT_REASON_PRESETS,
            ).mapNotNull { it.trim().takeIf(String::isNotEmpty) }

            val moderation = ModerationSettings(
                broadcastToStaff = values.boolean("moderation.broadcast-to-staff", true),
                logToConsole = values.boolean("moderation.log-to-console", true),
                maxReasonLength = values.integer("moderation.max-reason-length", ModerationSettings.DEFAULT_MAX_REASON_LENGTH, 32..2048),
                requireReasonFor = requireReasonFor,
                minIpv4CidrPrefix = values.integer("moderation.min-ipv4-cidr-prefix", ModerationSettings.DEFAULT_MIN_IPV4_CIDR_PREFIX, 0..32),
                minIpv6CidrPrefix = values.integer("moderation.min-ipv6-cidr-prefix", ModerationSettings.DEFAULT_MIN_IPV6_CIDR_PREFIX, 0..128),
                mutedCommands = mutedCommands,
                reasonPresets = reasonPresets,
            )

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
                moderation,
            )

            values.throwIfInvalid()

            val presentLeaves = collectLeafPaths(yaml)
            val warnings = buildList {
                for (path in presentLeaves) {
                    if (path !in knownKeys) {
                        val suggestion = closestKey(path, knownKeys)
                        add(if (suggestion != null) "Unknown config key '$path' (did you mean '$suggestion'?)" else "Unknown config key '$path'")
                    }
                }
            }
            val missingKeys = expectedKeys.filter { !yaml.contains(it) }
            return ConfigurationLoadResult(
                Configuration(strategy, modules, warmup, features, maxPendingOperations),
                warnings,
                missingKeys,
            )
        }
    }
}

internal class ConfigurationException(
    message: String,
    val errors: List<String> = listOf(message),
) : IllegalArgumentException(message)

internal fun collectLeafPaths(yaml: YamlConfiguration): List<String> = buildList {
    for ((key, value) in yaml.getValues(true)) {
        if (value is ConfigurationSection) {
            if (value.getKeys(false).isEmpty()) add(key)
        } else {
            add(key)
        }
    }
}

internal fun closestKey(input: String, candidates: Iterable<String>): String? {
    val lowered = input.lowercase(Locale.ROOT)
    val maxDistance = when {
        lowered.length <= 6 -> 2
        else -> 3
    }
    return candidates
        .map { candidate -> candidate to levenshtein(lowered, candidate.lowercase(Locale.ROOT)) }
        .filter { (_, distance) -> distance in 1..maxDistance }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}

private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(
                previous[j] + 1,
                current[j - 1] + 1,
                previous[j - 1] + cost,
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

private class ConfigValues(private val yaml: YamlConfiguration) {
    private val errors = mutableListOf<String>()
    private val invalidSections = mutableSetOf<String>()

    fun error(message: String) {
        if (message !in errors) errors.add(message)
    }

    fun throwIfInvalid() {
        if (errors.isEmpty()) return
        throw ConfigurationException(errors.joinToString("; "), errors.toList())
    }

    fun string(path: String, default: String): String {
        val raw = read(path, default) ?: return default
        return raw as? String ?: run {
            error("$path must be a string")
            default
        }
    }

    fun boolean(path: String, default: Boolean): Boolean {
        val raw = read(path, default) ?: return default
        return raw as? Boolean ?: run {
            error("$path must be true or false")
            default
        }
    }

    fun integer(path: String, default: Int, range: IntRange): Int {
        val raw = read(path, default) ?: return default
        return (raw as? Int)?.takeIf { it in range } ?: run {
            error("$path must be an integer from ${range.first} to ${range.last}")
            default
        }
    }

    fun stringList(path: String, default: List<String>): List<String> {
        val raw = read(path, default) ?: return default
        if (raw !is List<*> || raw.any { it !is String }) {
            error("$path must be a list of strings")
            return default
        }
        @Suppress("UNCHECKED_CAST")
        return raw as List<String>
    }

    private fun read(path: String, default: Any): Any? {
        val sections = path.split('.').dropLast(1)
        for (index in sections.indices) {
            val section = sections.take(index + 1).joinToString(".")
            if (yaml.contains(section) && !yaml.isConfigurationSection(section)) {
                if (invalidSections.add(section)) {
                    error("$section must be a configuration section")
                }
                return null
            }
        }
        return yaml.get(path) ?: default
    }
}
