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
        assertEquals(3, configuration.features.teleportWarmupSeconds)
        assertTrue(configuration.features.respectBedSpawn)
        assertTrue(configuration.features.safeTeleport)
        assertEquals(2, configuration.warmup.workers(0))
        assertEquals(0, configuration.warmup.workers(1))
    }

    @Test
    fun `feature and performance settings are typed`() {
        val configuration = parse("""
            startup: all_at_once
            modules:
              spawn: false
              seen: false
            homes:
              limit: 16
            spawn:
              respect-bed-spawn: false
            teleportation:
              safe-teleport: false
            performance:
              warmup:
                empty-server-workers: 3
                active-server-workers: 2
                pause-optional-on-join: false
        """.trimIndent())
        assertEquals(StartupStrategy.ALL_AT_ONCE, configuration.startup)
        assertFalse("spawn" in configuration.enabledModules)
        assertFalse("seen" in configuration.enabledModules)
        assertEquals(16, configuration.features.homeLimit)
        assertFalse(configuration.features.respectBedSpawn)
        assertFalse(configuration.features.safeTeleport)
        assertEquals(2, configuration.warmup.workers(32))
    }

    @Test
    fun `invalid keys identify their location`() {
        val inputs = mapOf(
            "startup: soon" to "startup must be LAZY, ADHD, or ALL_AT_ONCE",
            "homes: off" to "homes must be a configuration section",
            "homes: {limit: 0}" to "homes.limit must be an integer from 1 to 1000",
            "teleportation: {warmup-seconds: -1}" to "teleportation.warmup-seconds must be an integer from 0 to 3600",
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
              warmup-seconds: 0
              strict-tpahere: true
        """.trimIndent()).features
        assertEquals(3, overrides.requestSendCooldownSeconds)
        assertEquals(4, overrides.requestTeleportCooldownSeconds)
        assertEquals(0, overrides.backCooldownSeconds)
        assertEquals(0, overrides.teleportWarmupSeconds)
        assertTrue(overrides.strictTpahere)
    }

    @Test
    fun `bundled default config has no warnings or missing expected keys`() {
        val bundled = checkNotNull(javaClass.getResourceAsStream("/config.yml"))
            .reader(Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
        val result = Configuration.parseWithDiagnostics(bundled)
        assertTrue(result.warnings.isEmpty(), "Unexpected warnings: ${result.warnings}")
        assertTrue(result.missingKeys.isEmpty(), "Unexpected missing keys: ${result.missingKeys}")
    }

    @Test
    fun `multiple config errors are collected together`() {
        val failure = assertFailsWith<ConfigurationException> {
            parse("""
                startup: soon
                homes:
                  limit: 0
                moderation:
                  min-ipv4-cidr-prefix: 99
                  require-reason-for:
                    - notacommand
            """.trimIndent())
        }
        assertEquals(4, failure.errors.size)
        assertTrue(failure.errors.any { "startup" in it })
        assertTrue(failure.errors.any { "homes.limit" in it })
        assertTrue(failure.errors.any { "min-ipv4-cidr-prefix" in it })
        assertTrue(failure.errors.any { "notacommand" in it })
    }

    @Test
    fun `unknown keys produce warnings with typo suggestions and missing keys are reported`() {
        val yaml = YamlConfiguration().apply {
            loadFromString("""
                startup: ADHD
                warps:
                  per-warp-permission: true
                custom-typo: 123
            """.trimIndent())
        }
        val result = Configuration.parseWithDiagnostics(yaml)
        assertTrue(result.warnings.any {
            "warps.per-warp-permission" in it && "warps.per-warp-permissions" in it
        }, "Expected suggestion warning, got: ${result.warnings}")
        assertTrue(result.warnings.any { "custom-typo" in it })
        assertTrue("homes.limit" in result.missingKeys)
        assertFalse("startup" in result.missingKeys)
    }

    @Test
    fun `moderation settings parse custom values and diff reports changes`() {
        val defaults = parse("").features
        val custom = parse("""
            homes:
              limit: 12
            moderation:
              broadcast-to-staff: false
              log-to-console: false
              max-reason-length: 512
              require-reason-for:
                - ban
                - tempban
                - kick
              min-ipv4-cidr-prefix: 20
              min-ipv6-cidr-prefix: 48
              muted-commands:
                - msg
                - /party
              reason-presets:
                - Spam
                - Griefing
        """.trimIndent()).features

        assertFalse(custom.moderation.broadcastToStaff)
        assertFalse(custom.moderation.logToConsole)
        assertEquals(512, custom.moderation.maxReasonLength)
        assertEquals(setOf("ban", "tempban", "kick"), custom.moderation.requireReasonFor)
        assertEquals(20, custom.moderation.minIpv4CidrPrefix)
        assertEquals(48, custom.moderation.minIpv6CidrPrefix)
        assertEquals(setOf("msg", "party"), custom.moderation.mutedCommands)
        assertEquals(listOf("Spam", "Griefing"), custom.moderation.reasonPresets)

        val diff = defaults.diff(custom)
        val changedKeys = diff.map(SettingChange::key).toSet()
        assertTrue("homes.limit" in changedKeys)
        assertTrue("moderation.broadcast-to-staff" in changedKeys)
        assertTrue("moderation.min-ipv4-cidr-prefix" in changedKeys)
        assertTrue("moderation.muted-commands" in changedKeys)
        assertTrue(defaults.diff(defaults).isEmpty())
    }

    private fun parse(content: String): Configuration =
        Configuration.parse(YamlConfiguration().apply { loadFromString(content) })
}
