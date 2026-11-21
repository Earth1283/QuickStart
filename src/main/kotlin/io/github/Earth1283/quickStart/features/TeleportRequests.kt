package io.github.Earth1283.quickStart.features

import java.util.concurrent.TimeUnit

internal data class TeleportRequest<P>(val from: P, val to: P, val bringHere: Boolean, val expiresAt: Long)

internal enum class RequestStatus { SENT, COOLDOWN, DISCONNECTED }

internal class TeleportRequests<P>(private val active: (P) -> Boolean, private val clock: () -> Long = System::nanoTime) {
    private val requests = linkedMapOf<P, TeleportRequest<P>>()
    private val lastSent = mutableMapOf<P, Long>()

    @Synchronized
    fun send(from: P, to: P, bringHere: Boolean, cooldown: Int, expiration: Int): RequestStatus {
        val now = clock()
        purge(now)
        if (!active(from) || !active(to)) return RequestStatus.DISCONNECTED
        val previous = lastSent[from]
        if (previous != null && now - previous < TimeUnit.SECONDS.toNanos(cooldown.toLong())) return RequestStatus.COOLDOWN
        requests.remove(from)
        requests[from] = TeleportRequest(from, to, bringHere, now + TimeUnit.SECONDS.toNanos(expiration.toLong()))
        lastSent[from] = now
        return RequestStatus.SENT
    }

    @Synchronized
    fun take(recipient: P, fromMatches: (P) -> Boolean): TeleportRequest<P>? {
        purge(clock())
        val request = requests.values.lastOrNull { it.to == recipient && fromMatches(it.from) } ?: return null
        requests.remove(request.from)
        return request
    }

    @Synchronized
    fun disconnect(player: P) {
        requests.remove(player)
        requests.values.removeIf { it.to == player }
        lastSent.remove(player)
    }

    @Synchronized
    fun clear() {
        requests.clear()
        lastSent.clear()
    }

    private fun purge(now: Long) {
        requests.values.removeIf { now - it.expiresAt >= 0 || !active(it.from) || !active(it.to) }
    }
}
