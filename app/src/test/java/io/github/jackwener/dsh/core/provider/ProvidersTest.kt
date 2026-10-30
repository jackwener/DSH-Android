package io.github.jackwener.dsh.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.*

class ProvidersTest {
    private val kimi = ProviderConfig("kimi", "kimi", "Kimi", "https://api.moonshot.cn/v1/", listOf("kimi-k3"), hasKey = true)
    private val custom = ProviderConfig("custom", CUSTOM, "My gateway", "https://gw.example/v1", listOf("a", "b"))

    @Test fun routeIdsAreUniqueAndNeverDeepSeekRoutes() {
        assertEquals("kimi", newRouteId("kimi", emptyList()))
        assertEquals("kimi-2", newRouteId("kimi", listOf("kimi")))
        assertEquals("kimi-3", newRouteId("kimi", listOf("kimi", "kimi-2")))
        assertFalse(newRouteId("deepseek-official", emptyList()).startsWith("deepseek"))
    }

    @Test fun keyEnvIsAValidCredentialRef() {
        assertEquals("DSH_ANDROID_KEY_KIMI_2", keyEnvOf("kimi-2"))
        assertTrue(Regex("^[A-Z_][A-Z0-9_]*$").matches(keyEnvOf("custom-12")))
    }

    @Test fun patchCarriesRoutesWithoutSecrets() {
        val yml = renderProfilePatch(listOf(kimi, custom), webSearch = true)
        assertTrue(yml.contains("- id: llm-service-lite"))
        assertTrue(yml.contains("\"kimi\":{\"displayName\":\"Kimi\",\"interfaceType\":\"openai-chat-completions\",\"baseURL\":\"https://api.moonshot.cn/v1\",\"apiKeyEnv\":\"DSH_ANDROID_KEY_KIMI\""))
        // No key stored → no apiKeyEnv (the request goes unauthenticated, e.g. an open gateway).
        assertFalse(yml.contains("DSH_ANDROID_KEY_CUSTOM"))
        assertTrue(yml.contains("\"maxTokens\":${ThirdParty.MAX_TOKENS}"))
        assertTrue(yml.contains("- id: compaction-basic"))
        assertTrue(yml.contains("{\"provider\":\"custom\",\"model\":\"b\",\"headroomTokens\":${ThirdParty.COMPACTION_HEADROOM}}"))
        assertFalse(yml.contains("tool-web"))
    }

    @Test fun disabledProvidersAndWebSearch() {
        assertTrue(renderProfilePatch(listOf(kimi.copy(enabled = false)), webSearch = true).trimEnd().endsWith("[]"))
        val off = renderProfilePatch(emptyList(), webSearch = false)
        assertTrue(off.contains("- id: tool-web\n  config: {\"search\":false,\"fetch\":true,\"searchTimeoutMs\":60000}"))
        assertFalse(off.contains("llm-service-lite"))
    }

    @Test fun officialOpenAiUsesStatelessResponsesWithEncryptedReplay() {
        // The actual endpoint decides the protocol, even for a custom preset or renamed route.
        for (url in listOf("https://api.openai.com/v1", " https://API.OPENAI.COM/v1/ ")) {
            val p = custom.copy(baseUrl = url, models = listOf("gpt-6.1-terra"), hasKey = true)
            val yml = renderProfilePatch(listOf(p), webSearch = true)
            val config = Json.parseToJsonElement(yml.lineSequence().first { it.startsWith("  config:") }.substringAfter("  config:")).jsonObject
            val route = config.getValue("providers").jsonObject.getValue(p.id).jsonObject
            assertEquals("openai-responses", route.getValue("interfaceType").jsonPrimitive.content)
            assertFalse(route.containsKey("includeUsage"))
            assertFalse(route.getValue("extraBody").jsonObject.getValue("store").jsonPrimitive.boolean)
            assertEquals(p.keyEnv, route.getValue("apiKeyEnv").jsonPrimitive.content)
            val model = route.getValue("models").jsonArray.single().jsonObject
            assertEquals("gpt-6.1-terra", model.getValue("id").jsonPrimitive.content)
            assertTrue(model.getValue("capabilities").jsonObject.getValue("responsesEncryptedReplay").jsonPrimitive.boolean)
            assertEquals(ThirdParty.MAX_TOKENS, model.getValue("defaults").jsonObject.getValue("maxTokens").jsonPrimitive.int)
        }
    }

    @Test fun gatewaysKeepChatCompletionsRegardlessOfPresetOrModelName() {
        for (url in listOf("https://gw.example/v1", "https://api.openai.com.example.org/v1", "http://127.0.0.1:8080/v1")) {
            val p = custom.copy(preset = "openai", baseUrl = url, models = listOf("gpt-6.1-terra"))
            val yml = renderProfilePatch(listOf(p), webSearch = true)
            assertTrue(yml.contains("\"interfaceType\":\"openai-chat-completions\""))
            assertTrue(yml.contains("\"includeUsage\":true"))
            assertFalse(yml.contains("responsesEncryptedReplay"))
            assertFalse(yml.contains("\"store\""))
        }
    }

    @Test fun choiceFallsBackToDeepSeek() {
        assertEquals("kimi-k3", resolveChoice("kimi", "kimi-k3", listOf(kimi)).model)
        assertEquals(kimi, resolveChoice("kimi", "kimi-k3", listOf(kimi)).provider)
        assertNull(resolveChoice("kimi", "kimi-k3", listOf(kimi.copy(enabled = false))).provider)
        assertNull(resolveChoice("kimi", "gone", listOf(kimi)).provider)
        assertEquals(DEFAULT_DEEPSEEK_MODEL, resolveChoice("kimi", "kimi-k3", emptyList()).model)
        assertEquals("deepseek-v4-pro", resolveChoice("", "deepseek-v4-pro", listOf(kimi)).model)
        assertEquals(ThirdParty.CONTEXT_WINDOW, resolveChoice("kimi", "kimi-k3", listOf(kimi)).contextWindow)
    }

    @Test fun modelListShapes() {
        assertEquals(listOf("a", "b"), parseModelIds("""{"object":"list","data":[{"id":"b"},{"id":"a"}]}"""))
        assertEquals(listOf("gemini-x"), parseModelIds("""{"models":[{"name":"models/gemini-x"}]}"""))
        assertEquals(listOf("m"), parseModelIds("""[{"id":"m"}]"""))
    }

    @Test fun presetsAreHttpsOpenAiCompatible() {
        PRESETS.filter { it.id != CUSTOM }.forEach { assertTrue(it.id, it.baseUrl.startsWith("https://")) }
        assertEquals(PRESETS.size, PRESETS.map { it.id }.toSet().size)
    }
}
