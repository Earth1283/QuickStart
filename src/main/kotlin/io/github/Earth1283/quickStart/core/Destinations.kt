package io.github.Earth1283.quickStart.core

import java.util.Locale
import java.util.UUID

internal object DestinationNames {
    private val chars = Regex("[a-zA-Z0-9_-]+")

    fun normalize(value: String, maxLength: Int = 32): String? =
        value.takeIf { it.length in 1..maxLength && chars.matches(it) }?.lowercase(Locale.ROOT)
}

internal data class Position(
    val worldId: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
) {
    init {
        require(x in -30_000_000.0..30_000_000.0 && z in -30_000_000.0..30_000_000.0 && y in -20_000_000.0..20_000_000.0) {
            "Invalid location coordinates"
        }
        require(yaw.isFinite() && pitch.isFinite()) { "Invalid location rotation" }
    }
}

internal enum class DestinationKind(val key: String) { HOME("home"), WARP("warp"), SPAWN("spawn") }

internal data class DestinationGroup(val kind: DestinationKind, val owner: String = "") {
    init {
        require((kind == DestinationKind.HOME) == owner.isNotEmpty()) { "Invalid destination owner" }
    }

    companion object {
        fun homes(owner: UUID) = DestinationGroup(DestinationKind.HOME, owner.toString())
        val warps = DestinationGroup(DestinationKind.WARP)
        val spawn = DestinationGroup(DestinationKind.SPAWN)
    }
}

internal enum class SaveResult { CREATED, UPDATED, LIMIT_REACHED }
