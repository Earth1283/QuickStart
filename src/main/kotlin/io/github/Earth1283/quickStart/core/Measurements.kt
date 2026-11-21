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

    fun lines(): List<String> = listOf(
        "Bootstrap: ${display(bootstrapMillis)}",
        "Critical initialization: ${display(criticalMillis)}",
        "Application ready after: ${display(readinessMillis)}",
        "Background warmup: ${display(warmupMillis)}",
        "First player feature ready after: ${display(firstPlayerReady.get().takeIf { it >= 0 })}",
    )

    private fun display(millis: Long?): String = millis?.let { "$it ms" } ?: "pending or unused"
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

    fun describe(): String {
        val count = calls.sum()
        if (count == 0L) return "no operations recorded"
        return "$count operations, ${totalNanos.sum() / count / 1000} µs mean, ${maximumNanos.get() / 1000} µs max"
    }
}
