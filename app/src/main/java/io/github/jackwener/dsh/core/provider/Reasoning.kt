package io.github.jackwener.dsh.core.provider

import io.github.jackwener.dsh.core.data.Effort
import kotlinx.serialization.Serializable

@Serializable
data class ReasoningLevel(val id: String, val name: String)

@Serializable
data class ModelReasoning(val efforts: List<ReasoningLevel> = emptyList(), val defaultEffort: String? = null) {
    fun selected(saved: String?): ReasoningLevel? = efforts.firstOrNull { it.id == saved }
        ?: efforts.firstOrNull { it.id == defaultEffort } ?: efforts.firstOrNull()
}

@Serializable
data class ModelMetadata(val provider: String, val id: String, val reasoning: ModelReasoning? = null)

@Serializable
data class ModelsMetadata(val models: List<ModelMetadata> = emptyList())

/** Third-party choices are stored per exact route, separately from the built-in DeepSeek choice. */
@Serializable
data class ProviderEffort(val provider: String, val model: String, val effort: String)

fun ModelChoice.reasoning(metadata: List<ModelMetadata>): ModelReasoning = if (isDeepSeek) {
    ModelReasoning(Effort.entries.map { ReasoningLevel(it.id, it.label) }, Effort.High.id)
} else metadata.firstOrNull { it.provider == provider?.id && it.id == model }?.reasoning ?: ModelReasoning()

fun ModelChoice.savedEffort(native: Effort, saved: List<ProviderEffort>): String? = if (isDeepSeek) native.id
    else saved.firstOrNull { it.provider == provider?.id && it.model == model }?.effort

/** Omit an unsupported or unselected third-party value so its adapter's default still applies. */
fun ModelChoice.requestedEffort(native: Effort, saved: List<ProviderEffort>, metadata: List<ModelMetadata>): String {
    if (isDeepSeek) return native.id
    val value = savedEffort(native, saved) ?: return ""
    return value.takeIf { id -> reasoning(metadata).efforts.any { it.id == id } }.orEmpty()
}
