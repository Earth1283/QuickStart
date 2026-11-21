package io.github.Earth1283.quickStart.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WarmupTest {
    @Test
    fun `join pauses new work and last departure resumes it`() = runBlocking {
        withTimeout(5_000) {
            val players = MutableStateFlow(0)
            val started = Channel<String>()
            val release = Channel<Unit>()
            val settings = WarmupSettings(true, 1, 1, true)
            val warmup = launch {
                warmModules(listOf("homes", "warps"), settings, players) {
                    started.send(it)
                    release.receive()
                }
            }
            assertEquals("homes", started.receive())
            players.value = 1
            release.send(Unit)
            assertNull(withTimeoutOrNull(100) { started.receive() })
            players.value = 0
            assertEquals("warps", started.receive())
            release.send(Unit)
            warmup.join()
        }
    }
}
