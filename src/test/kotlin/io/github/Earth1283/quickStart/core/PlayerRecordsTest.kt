package io.github.Earth1283.quickStart.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PlayerRecordsTest {
    @Test
    fun `durations format concisely across scales`() {
        assertEquals("0s", formatDuration(Duration.ZERO))
        assertEquals("0s", formatDuration(Duration.ofSeconds(-5)))
        assertEquals("45s", formatDuration(Duration.ofSeconds(45)))
        assertEquals("3m 5s", formatDuration(Duration.ofSeconds(185)))
        assertEquals("2h 4m", formatDuration(Duration.ofMinutes(124)))
        assertEquals("3d 4h 5m", formatDuration(Duration.ofDays(3).plusHours(4).plusMinutes(5).plusSeconds(9)))
    }

    @Test
    fun `toggle setting parser accepts standard values and rejects unknown input`() {
        assertEquals(ToggleSetting.ON, ToggleSetting.parse("on"))
        assertEquals(ToggleSetting.ON, ToggleSetting.parse("TRUE"))
        assertEquals(ToggleSetting.OFF, ToggleSetting.parse("off"))
        assertEquals(ToggleSetting.OFF, ToggleSetting.parse("disable"))
        assertEquals(ToggleSetting.DEFAULT, ToggleSetting.parse("default"))
        assertEquals(ToggleSetting.DEFAULT, ToggleSetting.parse("reset"))
        assertEquals(ToggleSetting.STATUS, ToggleSetting.parse("status"))
        assertNull(ToggleSetting.parse("maybe"))
    }

    @Test
    fun `timestamps and expirations format cleanly without ISO Z suffixes`() {
        val now = Instant.parse("2026-11-22T10:30:00.250Z")
        val target = Instant.parse("2026-11-22T12:00:00.000Z")
        val formattedTime = formatTimestamp(target)
        assertEquals("Nov 22, 2026, 12:00 UTC", formattedTime)
        assertFalse(formattedTime.endsWith("Z"))

        assertEquals("in 1h 30m (Nov 22, 2026, 12:00 UTC)", formatExpiration(target, now))
        assertEquals("Nov 22, 2026, 10:30 UTC", formatExpiration(now, target))
    }
}
