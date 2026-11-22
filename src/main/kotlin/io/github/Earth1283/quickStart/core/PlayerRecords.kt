package io.github.Earth1283.quickStart.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal data class PlayerPreferences(
    val respectBedSpawn: Boolean? = null,
    val safeTeleport: Boolean? = null,
) {
    val isDefault: Boolean get() = respectBedSpawn == null && safeTeleport == null

    companion object {
        val DEFAULT = PlayerPreferences()
    }
}

internal data class PlayerProfile(
    val id: UUID,
    val name: String,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val lastQuit: Instant?,
    val lastIp: String?,
    val lastPosition: Position?,
)

internal data class PlayerIpRecord(
    val ip: String,
    val firstSeen: Instant,
    val lastSeen: Instant,
)

internal data class AltAccount(
    val id: UUID,
    val name: String,
    val lastSeen: Instant,
    val sharedIps: List<String>,
)

internal data class AltLookupResult(
    val checkedIps: List<String>,
    val accounts: List<AltAccount>,
)

internal enum class ToggleSetting(val id: String) {
    ON("on"),
    OFF("off"),
    DEFAULT("default"),
    STATUS("status");

    companion object {
        val suggestions = entries.map(ToggleSetting::id)

        fun parse(value: String): ToggleSetting? = when (value.lowercase(Locale.ROOT)) {
            "on", "true", "enable", "enabled" -> ON
            "off", "false", "disable", "disabled" -> OFF
            "default", "reset" -> DEFAULT
            "status" -> STATUS
            else -> null
        }
    }
}

internal fun formatDuration(duration: Duration): String {
    val totalSeconds = maxOf(0L, duration.seconds)
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    val parts = buildList {
        if (days > 0) add("${days}d")
        if (hours > 0) add("${hours}h")
        if (minutes > 0) add("${minutes}m")
        if (seconds > 0 || isEmpty()) add("${seconds}s")
    }
    return parts.take(3).joinToString(" ")
}

private val humanTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)

internal fun formatTimestamp(instant: Instant): String = humanTimestampFormatter.format(instant)

internal fun formatExpiration(at: Instant, now: Instant = Instant.now()): String {
    if (!at.isAfter(now)) return formatTimestamp(at)
    val remaining = Duration.between(now, at)
    val rounded = if (remaining.nano > 0) Duration.ofSeconds(remaining.seconds + 1) else remaining
    return "in ${formatDuration(rounded)} (${formatTimestamp(at)})"
}
