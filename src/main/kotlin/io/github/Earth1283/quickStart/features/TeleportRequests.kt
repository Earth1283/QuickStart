package io.github.Earth1283.quickStart.features

import io.github.Earth1283.quickStart.core.Position
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class TeleportRequest<P>(
    val id: UUID,
    val from: P,
    val to: P,
    val bringHere: Boolean,
    val expiresAt: Long,
    val destination: Position?,
)

internal sealed interface RequestSend<out P> {
    data class Sent<P>(val request: TeleportRequest<P>) : RequestSend<P>
    data object Cooldown : RequestSend<Nothing>
    data object Disconnected : RequestSend<Nothing>
}

internal data class PendingRequest<P>(val request: TeleportRequest<P>, val secondsRemaining: Long)

internal class TeleportRequests<P>(private val active: (P) -> Boolean, private val clock: () -> Long = System::nanoTime) {
    private val outgoing = mutableMapOf<P, TeleportRequest<P>>()
    private val incoming = mutableMapOf<P, LinkedHashMap<P, TeleportRequest<P>>>()
    private val lastSent = mutableMapOf<P, Long>()

    @Synchronized
    fun send(from: P, to: P, bringHere: Boolean, cooldown: Int, expiration: Int, destination: Position? = null): RequestSend<P> {
        val now = clock()
        if (!active(from) || !active(to)) return RequestSend.Disconnected
        val previous = lastSent[from]
        if (previous != null && now - previous < TimeUnit.SECONDS.toNanos(cooldown.toLong())) return RequestSend.Cooldown
        removeOutgoing(from)
        val request = TeleportRequest(UUID.randomUUID(), from, to, bringHere, now + TimeUnit.SECONDS.toNanos(expiration.toLong()), destination)
        outgoing[from] = request
        incoming.getOrPut(to, ::LinkedHashMap)[from] = request
        lastSent[from] = now
        return RequestSend.Sent(request)
    }

    fun take(recipient: P, fromMatches: (P) -> Boolean): TeleportRequest<P>? =
        takeMatching(recipient) { fromMatches(it.from) }

    fun takeById(recipient: P, id: UUID): TeleportRequest<P>? =
        takeMatching(recipient) { it.id == id }

    @Synchronized
    private fun takeMatching(recipient: P, matches: (TeleportRequest<P>) -> Boolean): TeleportRequest<P>? {
        val now = clock()
        val recipientActive = active(recipient)
        val requests = incoming[recipient]?.reversed()?.values?.iterator() ?: return null
        while (requests.hasNext()) {
            val request = requests.next()
            val valid = recipientActive && isLive(request, now)
            val selected = valid && matches(request)
            if (!valid || selected) {
                requests.remove()
                outgoing.remove(request.from)
            }
            if (selected) {
                if (incoming[recipient]?.isEmpty() == true) incoming.remove(recipient)
                return request
            }
        }
        if (incoming[recipient]?.isEmpty() == true) incoming.remove(recipient)
        return null
    }

    @Synchronized
    fun pending(recipient: P): List<PendingRequest<P>> {
        val requests = incoming[recipient] ?: return emptyList()
        val now = clock()
        val recipientActive = active(recipient)
        return buildList {
            val iterator = requests.reversed().values.iterator()
            while (iterator.hasNext()) {
                val request = iterator.next()
                if (recipientActive && isLive(request, now)) {
                    val remaining = request.expiresAt - now
                    add(PendingRequest(request, TimeUnit.NANOSECONDS.toSeconds(remaining - 1) + 1))
                } else {
                    iterator.remove()
                    outgoing.remove(request.from)
                }
            }
            if (requests.isEmpty()) incoming.remove(recipient)
        }
    }

    private fun isLive(request: TeleportRequest<P>, now: Long): Boolean =
        now - request.expiresAt < 0 && active(request.from)

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
