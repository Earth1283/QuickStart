package io.github.Earth1283.quickStart

import io.github.Earth1283.quickStart.core.Configuration
import io.github.Earth1283.quickStart.core.QuickStartRuntime
import io.github.Earth1283.quickStart.core.StartupStrategy
import java.io.File
import kotlin.time.TimeSource
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin

class QuickStart : JavaPlugin() {
    private var runtime: QuickStartRuntime? = null

    override fun onEnable() {
        val enabledAt = TimeSource.Monotonic.markNow()
        val configFile = File(dataFolder, "config.yml")
        val loaded = runCatching {
            saveDefaultConfig()
            Configuration.load(configFile)
        }
        val config = loaded.getOrElse {
            Configuration.parse(YamlConfiguration()).copy(startup = StartupStrategy.ALL_AT_ONCE, enabledModules = emptySet())
        }
        val instance = QuickStartRuntime(this, configFile, config, loaded.exceptionOrNull(), enabledAt)
        runtime = instance
        instance.start()
    }

    override fun onDisable() {
        runtime?.close()
        runtime = null
    }
}
