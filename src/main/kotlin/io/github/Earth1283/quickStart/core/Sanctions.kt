package io.github.Earth1283.quickStart.core

import com.google.common.net.InetAddresses
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal enum class SanctionKind { BAN, MUTE }

internal enum class TargetKind { ACCOUNT, IP, RANGE }

internal sealed interface SanctionTarget {
    val kind: TargetKind
    val value: String
    fun matches(id: UUID, address: InetAddress?): Boolean

    data class Account(val id: UUID) : SanctionTarget {
        override val kind get() = TargetKind.ACCOUNT
        override val value get() = id.toString()
        override fun matches(id: UUID, address: InetAddress?) = this.id == id
    }

    data class Ip(val address: InetAddress) : SanctionTarget {
        override val kind get() = TargetKind.IP
        override val value get() = InetAddresses.toAddrString(address)
        override fun matches(id: UUID, address: InetAddress?) = this.address == address
    }

    data class Range(val network: IpNetwork) : SanctionTarget {
        override val kind get() = TargetKind.RANGE
        override val value get() = network.toString()
        override fun matches(id: UUID, address: InetAddress?) = address != null && network.contains(address)
    }

    companion object {
        fun decode(kind: TargetKind, value: String): SanctionTarget = when (kind) {
            TargetKind.ACCOUNT -> Account(UUID.fromString(value))
            TargetKind.IP -> Ip(checkNotNull(parseIp(value)))
            TargetKind.RANGE -> Range(checkNotNull(IpNetwork.parse(value)))
        }
    }
}

internal fun parseIp(value: String): InetAddress? = try {
    if ('%' in value) null else InetAddresses.forString(value)
} catch (_: IllegalArgumentException) {
    null
}

@ConsistentCopyVisibility
internal data class IpNetwork private constructor(private val base: InetAddress, val prefix: Int) {
    val isIpv6: Boolean get() = base.address.size == 16

    fun contains(address: InetAddress): Boolean {
        val network = base.address
        val candidate = address.address
        if (network.size != candidate.size) return false
        val wholeBytes = prefix / Byte.SIZE_BITS
        for (index in 0 until wholeBytes) {
            if (network[index] != candidate[index]) return false
        }
        val remainingBits = prefix % Byte.SIZE_BITS
        return remainingBits == 0 || masked(candidate[wholeBytes], remainingBits) == network[wholeBytes]
    }

    override fun toString() = "${InetAddresses.toAddrString(base)}/$prefix"

    companion object {
        fun parse(value: String): IpNetwork? {
            val parts = value.split('/', limit = 2)
            if (parts.size != 2) return null
            val address = parseIp(parts[0]) ?: return null
            val bytes = address.address
            val rawPrefix = parts[1].takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() ?: return null
            // Java represents IPv4-mapped IPv6 addresses as IPv4.
            val prefix = if (bytes.size == 4 && ':' in parts[0]) rawPrefix - 96 else rawPrefix
            if (prefix !in 0..(bytes.size * Byte.SIZE_BITS)) return null
            for (index in bytes.indices) {
                bytes[index] = masked(bytes[index], (prefix - index * Byte.SIZE_BITS).coerceIn(0, Byte.SIZE_BITS))
            }
            return IpNetwork(InetAddress.getByAddress(bytes), prefix)
        }

        private fun masked(value: Byte, bits: Int) = (value.toInt() and (-1 shl (Byte.SIZE_BITS - bits))).toByte()
    }
}

internal data class SanctionKey(val kind: SanctionKind, val target: SanctionTarget)

internal data class Sanction(val key: SanctionKey, val reason: String?, val expiresAt: Instant?) {
    fun active(now: Instant) = expiresAt == null || expiresAt.isAfter(now)
}

internal data class ModerationRecord(
    val command: String,
    val target: SanctionTarget,
    val label: String,
    val actor: String,
    val time: Instant,
    val reason: String? = null,
    val expiresAt: Instant? = null,
)

internal class SanctionIndex {
    private val exact = ConcurrentHashMap<SanctionKey, Sanction>()
    private val ranges = ConcurrentHashMap<IpNetwork, Sanction>()

    fun get(key: SanctionKey): Sanction? {
        val network = key.target as? SanctionTarget.Range
        return if (network == null) exact[key] else ranges[network.network]
    }

    fun put(sanction: Sanction) {
        val network = sanction.key.target as? SanctionTarget.Range
        if (network == null) exact[sanction.key] = sanction else ranges[network.network] = sanction
    }

    fun remove(key: SanctionKey) {
        val network = key.target as? SanctionTarget.Range
        if (network == null) exact.remove(key) else ranges.remove(network.network)
    }

    fun find(kind: SanctionKind, player: UUID, address: InetAddress?, now: Instant = Instant.now()): Sanction? {
        exact[SanctionKey(kind, SanctionTarget.Account(player))]?.takeIf { it.active(now) }?.let { return it }
        if (address == null) return null
        exact[SanctionKey(kind, SanctionTarget.Ip(address))]?.takeIf { it.active(now) }?.let { return it }
        if (kind != SanctionKind.BAN) return null
        return ranges.entries.asSequence()
            .filter { (network, sanction) -> network.contains(address) && sanction.active(now) }
            .maxByOrNull { it.key.prefix }?.value
    }

    fun matching(kind: SanctionKind, target: SanctionTarget, address: InetAddress?, now: Instant = Instant.now()): List<Sanction> = buildList {
        if (target is SanctionTarget.Range) {
            if (kind == SanctionKind.BAN) ranges[target.network]?.takeIf { it.active(now) }?.let(::add)
            return@buildList
        }
        exact[SanctionKey(kind, target)]?.takeIf { it.active(now) }?.let(::add)
        if (address == null) return@buildList
        if (target is SanctionTarget.Account) {
            exact[SanctionKey(kind, SanctionTarget.Ip(address))]?.takeIf { it.active(now) }?.let(::add)
        }
        if (kind == SanctionKind.BAN) {
            addAll(ranges.entries.filter { (network, sanction) -> network.contains(address) && sanction.active(now) }
                .sortedByDescending { it.key.prefix }.map { it.value })
        }
    }

    fun targetValues(kind: SanctionKind, scope: TargetKind?, now: Instant = Instant.now()): List<String> =
        activeTargets(kind, scope, now).map(SanctionTarget::value)

    fun activeTargets(kind: SanctionKind, scope: TargetKind?, now: Instant = Instant.now()): List<SanctionTarget> = buildList {
        if (scope != TargetKind.RANGE) {
            for ((key, sanction) in exact) {
                if (key.kind == kind && (scope == null || key.target.kind == scope) && sanction.active(now)) {
                    add(key.target)
                }
            }
        }
        if (kind == SanctionKind.BAN && (scope == null || scope == TargetKind.RANGE)) {
            for ((network, sanction) in ranges) if (sanction.active(now)) add(SanctionTarget.Range(network))
        }
    }

    fun allActive(kind: SanctionKind, now: Instant = Instant.now()): List<Sanction> = buildList {
        for ((key, sanction) in exact) {
            if (key.kind == kind && sanction.active(now)) add(sanction)
        }
        if (kind == SanctionKind.BAN) {
            for ((_, sanction) in ranges) {
                if (sanction.active(now)) add(sanction)
            }
        }
    }.sortedWith(compareBy<Sanction>({ it.key.target.kind.ordinal }, { it.key.target.value }))
}
