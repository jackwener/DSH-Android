package io.github.jackwener.dsh.core.provider

import io.github.jackwener.dsh.core.data.Effort
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReasoningTest {
    private val go = ProviderConfig("go", CUSTOM, "Go", "https://opencode.ai/zen/go/v1", listOf("deepseek-v4.1-flash"))
    private val choice = ModelChoice(go, go.models.single())
    private val declared = ModelReasoning(listOf("low", "high", "max").map { ReasoningLevel(it, it.replaceFirstChar(Char::uppercase)) }, "high")
    private val metadata = listOf(ModelMetadata(go.id, choice.model, declared))

    @Test fun thirdPartyPickerUsesAdapterChoicesAndItsDefault() {
        assertEquals(listOf("low", "high", "max"), choice.reasoning(metadata).efforts.map { it.id })
        assertEquals("high", choice.reasoning(metadata).selected(null)?.id)
        // Merely displaying the adapter default does not send a native DeepSeek setting to Go.
        assertEquals("", choice.requestedEffort(Effort.Max, emptyList(), metadata))
        for (id in listOf("low", "high", "max")) {
            val saved = listOf(ProviderEffort(go.id, choice.model, id))
            assertEquals(id, choice.requestedEffort(Effort.High, saved, metadata))
            assertEquals(id, choice.reasoning(metadata).selected(choice.savedEffort(Effort.High, saved))?.id)
        }
    }

    @Test fun choicesDoNotLeakAcrossProvidersOrModels() {
        val saved = listOf(ProviderEffort("other", choice.model, "max"), ProviderEffort(go.id, "another-model", "low"))
        assertEquals("", choice.requestedEffort(Effort.Max, saved, metadata))
        assertTrue(choice.reasoning(listOf(ModelMetadata("other", choice.model, declared))).efforts.isEmpty())
    }

    @Test fun unsupportedSavedChoiceFallsBackWithoutSendingAnInvalidValue() {
        val saved = listOf(ProviderEffort(go.id, choice.model, "off"))
        assertEquals("", choice.requestedEffort(Effort.Off, saved, metadata))
        assertEquals("high", declared.selected("off")?.id)
        assertEquals("", choice.requestedEffort(Effort.High, saved, emptyList()))
        assertTrue(choice.reasoning(emptyList()).efforts.isEmpty())
    }

    @Test fun nativeRouteKeepsItsExistingFourChoices() {
        val native = ModelChoice(null, DEFAULT_DEEPSEEK_MODEL)
        assertEquals(Effort.entries.map { it.id }, native.reasoning(emptyList()).efforts.map { it.id })
        assertEquals("off", native.requestedEffort(Effort.Off, emptyList(), emptyList()))
    }

    @Test fun adapterDefinedIdsSurviveMetadataAndPreferenceRoundTrips() {
        val json = Json { ignoreUnknownKeys = true }
        val wire = """{"models":[{"provider":"go","id":"deepseek-v4.1-flash","reasoning":{"efforts":[{"id":"medium","name":"Medium"},{"id":"adaptive","name":"Adaptive"}],"defaultEffort":"adaptive"},"futureField":true}]}"""
        val info = json.decodeFromString(ModelsMetadata.serializer(), wire).models
        val saved = ProviderEffort(go.id, choice.model, "medium")
        val restored = json.decodeFromString(ProviderEffort.serializer(), json.encodeToString(ProviderEffort.serializer(), saved))
        assertEquals("adaptive", choice.reasoning(info).selected(null)?.id)
        assertEquals("medium", choice.requestedEffort(Effort.High, listOf(restored), info))
    }
}
