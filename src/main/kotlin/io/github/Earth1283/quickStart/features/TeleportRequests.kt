package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.Position
import java.util.concurrent.TimeUnit

internal data class TeleportRequest<P>(
    val from: P,
    val to: P,
    val bringHere: Boolean,
    val expiresAt: Long,
    val destination: Position?,
)

internal enum class RequestStatus { SENT, COOLDOWN, DISCONNECTED }

internal class TeleportRequests<P>(private val active: (P) -> Boolean, private val clock: () -> Long = System::nanoTime) {
    private val outgoing = mutableMapOf<P, TeleportRequest<P>>()
    private val incoming = mutableMapOf<P, LinkedHashMap<P, TeleportRequest<P>>>()
    private val lastSent = mutableMapOf<P, Long>()

    @Synchronized
    fun send(from: P, to: P, bringHere: Boolean, cooldown: Int, expiration: Int, destination: Position? = null): RequestStatus {
        val now = clock()
        if (!active(from) || !active(to)) return RequestStatus.DISCONNECTED
        val previous = lastSent[from]
        if (previous != null && now - previous < TimeUnit.SECONDS.toNanos(cooldown.toLong())) return RequestStatus.COOLDOWN
        removeOutgoing(from)
        val request = TeleportRequest(from, to, bringHere, now + TimeUnit.SECONDS.toNanos(expiration.toLong()), destination)
        outgoing[from] = request
        incoming.getOrPut(to, ::LinkedHashMap)[from] = request
        lastSent[from] = now
        return RequestStatus.SENT
    }

    @Synchronized
    fun take(recipient: P, fromMatches: (P) -> Boolean): TeleportRequest<P>? {
        val requests = incoming[recipient] ?: return null
        val now = clock()
        val recipientActive = active(recipient)
        // Check expiry on lookup. Replacement and disconnect also remove both index entries.
        val newestFirst = requests.reversed().values.iterator()
        while (newestFirst.hasNext()) {
            val request = newestFirst.next()
            val valid = recipientActive && now - request.expiresAt < 0 && active(request.from)
            val matches = valid && fromMatches(request.from)
            if (!valid || matches) {
                newestFirst.remove()
                outgoing.remove(request.from)
                if (requests.isEmpty()) incoming.remove(recipient)
            }
            if (matches) return request
        }
        return null
    }

    @Synchronized
    fun disconnect(player: P) {
        removeOutgoing(player)
        incoming.remove(player)?.keys?.forEach(outgoing::remove)
        lastSent.remove(player)
    }

    @Synchronized
    fun clear() {
        outgoing.clear()
        incoming.clear()
        lastSent.clear()
    }

    private fun removeOutgoing(sender: P) {
        val request = outgoing.remove(sender) ?: return
        val requests = incoming.getValue(request.to)
        requests.remove(sender)
        if (requests.isEmpty()) incoming.remove(request.to)
    }
}
