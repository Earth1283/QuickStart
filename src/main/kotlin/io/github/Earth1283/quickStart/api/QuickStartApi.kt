package io.github.Earth1283.quickStart.api

interface QuickStartApi {
    val readiness: Readiness
    fun modules(): List<ModuleStatus>
}

enum class Readiness { STARTING, READY, DEGRADED, FAILED, STOPPING }

enum class ModuleState { NEW, PREPARING, PREPARED, ACTIVATING, READY, STOPPING, STOPPED, FAILED, DISABLED }

data class ModuleStatus(
    val id: String,
    val state: ModuleState,
    val requiredDependencies: List<String>,
    val optionalDependencies: List<String>,
    val elapsedMillis: Long?,
    val failure: String?,
)
