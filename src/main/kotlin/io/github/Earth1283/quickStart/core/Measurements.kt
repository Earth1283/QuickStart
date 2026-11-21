package io.github.Earth1283.quickStart.core

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAccumulator
import java.util.concurrent.atomic.LongAdder
import kotlin.time.TimeSource

internal class StartupMeasurements(private val enabledAt: TimeSource.Monotonic.ValueTimeMark) {
    @Volatile var bootstrapMillis: Long? = null
    @Volatile var criticalMillis: Long? = null
    @Volatile var readinessMillis: Long? = null
    @Volatile var warmupMillis: Long? = null
    private val firstPlayerReady = AtomicLong(-1)

    fun elapsedMillis(): Long = enabledAt.elapsedNow().inWholeMilliseconds

    fun recordFirstPlayerReady() {
        firstPlayerReady.compareAndSet(-1, elapsedMillis())
    }

    fun lines(): List<Message> = listOf(
        Message("diagnostics.bootstrap", "time" to display(bootstrapMillis)),
        Message("diagnostics.critical", "time" to display(criticalMillis)),
        Message("diagnostics.ready", "time" to display(readinessMillis)),
        Message("diagnostics.warmup", "time" to display(warmupMillis)),
        Message("diagnostics.first-player", "time" to display(firstPlayerReady.get().takeIf { it >= 0 })),
    )

    private fun display(millis: Long?): Message =
        if (millis == null) Message("diagnostics.pending") else Message("diagnostics.elapsed", "millis" to millis)
}

internal class LatencyMeasurements {
    private val calls = LongAdder()
    private val totalNanos = LongAdder()
    private val maximumNanos = LongAccumulator(::maxOf, 0)

    fun record(nanos: Long) {
        totalNanos.add(nanos)
        maximumNanos.accumulate(nanos)
        calls.increment()
    }

    fun describe(): Message {
        val count = calls.sum()
        if (count == 0L) return Message("diagnostics.no-operations")
        return Message("diagnostics.latency", "count" to count, "mean" to totalNanos.sum() / count / 1000,
            "max" to maximumNanos.get() / 1000)
    }
}
