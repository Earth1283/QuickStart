package io.github.Earth1283.quickStart.core

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationTest {
    @Test
    fun `empty settings use defaults`() {
        val configuration = parse("")
        assertEquals(StartupStrategy.ADHD, configuration.startup)
        assertEquals(Configuration.featureIds.toSet(), configuration.enabledModules)
        assertEquals(5, configuration.features.homeLimit)
        assertEquals(2, configuration.warmup.workers(0))
        assertEquals(0, configuration.warmup.workers(1))
    }

    @Test
    fun `feature and performance settings are typed`() {
        val configuration = parse("""
            startup: all_at_once
            modules:
              spawn: false
            homes:
              limit: 16
            performance:
              warmup:
                empty-server-workers: 3
                active-server-workers: 2
                pause-optional-on-join: false
        """.trimIndent())
        assertEquals(StartupStrategy.ALL_AT_ONCE, configuration.startup)
        assertFalse("spawn" in configuration.enabledModules)
        assertEquals(16, configuration.features.homeLimit)
        assertEquals(2, configuration.warmup.workers(32))
    }

    @Test
    fun `invalid keys identify their location`() {
        val inputs = mapOf(
            "startup: soon" to "startup must be LAZY, ADHD, or ALL_AT_ONCE",
            "homes: off" to "homes must be a configuration section",
            "homes: {limit: 0}" to "homes.limit must be an integer from 1 to 1000",
            "modules: {homes: 'false'}" to "modules.homes must be true or false",
            "storage: {backend: postgres}" to "storage.backend must be sqlite",
        )
        for ((input, expected) in inputs) {
            assertEquals(expected, assertFailsWith<ConfigurationException> { parse(input) }.message)
        }
    }

    @Test
    fun `action cooldowns inherit the shared value and support independent overrides`() {
        val inherited = parse("teleportation: {cooldown-seconds: 12}").features
        assertEquals(12, inherited.requestSendCooldownSeconds)
        assertEquals(12, inherited.requestTeleportCooldownSeconds)
        assertEquals(12, inherited.backCooldownSeconds)
        assertFalse(inherited.strictTpahere)

        val overrides = parse("""
            teleportation:
              cooldown-seconds: 12
              request-send-cooldown-seconds: 3
              request-teleport-cooldown-seconds: 4
              back-cooldown-seconds: 0
              strict-tpahere: true
        """.trimIndent()).features
        assertEquals(3, overrides.requestSendCooldownSeconds)
        assertEquals(4, overrides.requestTeleportCooldownSeconds)
        assertEquals(0, overrides.backCooldownSeconds)
        assertTrue(overrides.strictTpahere)
    }

    private fun parse(content: String): Configuration =
        Configuration.parse(YamlConfiguration().apply { loadFromString(content) })
}
