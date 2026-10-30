package io.github.jackwener.dsh.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {
    private val kimi = ProviderConfig("kimi", "kimi", "Kimi", "https://api.moonshot.cn/v1/", listOf("kimi-k3"), hasKey = true)
    private val custom = ProviderConfig("custom", CUSTOM, "My gateway", "https://gw.example/v1", listOf("a", "b"))

    @Test fun routeIdsAreUniqueAndNeverDeepSeekRoutes() {
        assertEquals("kimi", newRouteId("kimi", emptyList()))
        assertEquals("kimi-2", newRouteId("kimi", listOf("kimi")))
        assertEquals("kimi-3", newRouteId("kimi", listOf("kimi", "kimi-2")))
        assertFalse(newRouteId("deepseek-official", emptyList()).startsWith("deepseek"))
    }

    @Test fun bareOriginGetsV1OnlyWhenItHelps() {
        assertEquals("https://integrate.api.nvidia.com/v1", v1Candidate("https://integrate.api.nvidia.com/ "))
        assertEquals("http://192.168.1.5:8080/v1", v1Candidate("http://192.168.1.5:8080"))
        for (u in listOf("https://api.openai.com/v1", "https://gw.example/openai", "https://h?x=1", "ftp://h", "not a url", ""))
            assertNull(u, v1Candidate(u))

        val asked = mutableListOf<String>()
        val nim: (String, String?) -> Result<List<String>> = { url, _ ->
            asked += url
            if (url.endsWith("/v1")) Result.success(listOf("nvidia/m")) else Result.failure(IllegalStateException("HTTP 404"))
        }
        assertEquals(FetchedModels(listOf("nvidia/m"), "https://integrate.api.nvidia.com/v1"), fetchModelsResolving("https://integrate.api.nvidia.com/", "k", nim).getOrThrow())
        assertEquals(listOf("https://integrate.api.nvidia.com", "https://integrate.api.nvidia.com/v1"), asked)
        // Not a 404, or the base already has a path: no second request, the original failure stands.
        asked.clear()
        val down: (String, String?) -> Result<List<String>> = { url, _ -> asked += url; Result.failure(IllegalStateException("HTTP 401 unauthorized")) }
        assertEquals("HTTP 401 unauthorized", fetchModelsResolving("https://h", null, down).exceptionOrNull()?.message)
        assertEquals("HTTP 404", fetchModelsResolving("https://h/api", null, nim).exceptionOrNull()?.message)
        assertEquals(listOf("https://h", "https://h/api"), asked)
        // /v1 fails too: the original 404 is reported.
        val gone: (String, String?) -> Result<List<String>> = { url, _ -> Result.failure(IllegalStateException(if (url.endsWith("/v1")) "HTTP 500" else "HTTP 404")) }
        assertEquals("HTTP 404", fetchModelsResolving("https://h", null, gone).exceptionOrNull()?.message)
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
