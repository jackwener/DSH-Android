package io.github.jackwener.dsh.core.provider

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections

class ModelVerifyTest {
    private fun c(status: Int, body: String) = VerifyErrors.classify(status, body)
    private inline fun <reified T> assertIs(v: Any?) = assertTrue("expected ${T::class.simpleName}, got $v", v is T)

    // ── classification, with error bodies in each vendor's shape ──

    @Test fun keyErrors() {
        // DeepSeek
        assertIs<VerifyFailure.KeyInvalid>(c(401, """{"error":{"message":"Authentication Fails, Your api key: ****abcd is invalid","type":"authentication_error","param":null,"code":"invalid_request_error"}}"""))
        // OpenAI
        assertIs<VerifyFailure.KeyInvalid>(c(401, """{"error":{"message":"Incorrect API key provided: sk-inval*****key.","type":"invalid_request_error","param":null,"code":"invalid_api_key"}}"""))
        // SiliconFlow, Kimi, Zhipu, Ark, MiniMax (401 bodies as served)
        assertIs<VerifyFailure.KeyInvalid>(c(401, """{"code":30014,"data":null,"message":"Token is invalid."}"""))
        assertIs<VerifyFailure.KeyInvalid>(c(401, """{"error":{"message":"Invalid Authentication","type":"invalid_authentication_error"}}"""))
        assertIs<VerifyFailure.KeyInvalid>(c(401, """{"error":{"code":"401","message":"令牌已过期或验证不正确"}}"""))
        assertIs<VerifyFailure.KeyInvalid>(c(401, """{"error":{"code":"AuthenticationError","message":"The API key format is incorrect.","param":"","type":"Unauthorized"}}"""))
        assertIs<VerifyFailure.KeyInvalid>(c(401, """{"type":"error","error":{"type":"authorized_error","message":"login fail: Please carry the API secret key in the 'Authorization' field of the request header (1004)","http_code":"401"}}"""))
        // Gemini reports a bad key as 400 INVALID_ARGUMENT.
        assertIs<VerifyFailure.KeyInvalid>(c(400, """[{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}]"""))
        // The server's text is kept.
        assertEquals("令牌已过期或验证不正确", (c(401, """{"error":{"code":"401","message":"令牌已过期或验证不正确"}}""") as VerifyFailure.KeyInvalid).message)
    }

    @Test fun modelNotAvailableKeepsTheServerMessage() {
        val ark = c(404, """{"error":{"code":"ModelNotOpen","message":"Your account 210xxx has not activated the model doubao-seed-2-1-pro. Please activate the model service in the Ark Console.","param":"","type":"Not Found"}}""")
        assertIs<VerifyFailure.ModelNotAvailable>(ark)
        assertTrue((ark as VerifyFailure.ModelNotAvailable).message.startsWith("Your account 210xxx has not activated"))
        assertIs<VerifyFailure.ModelNotAvailable>(c(403, """{"error":{"code":"1220","message":"您无权访问 glm-5.3"}}"""))
        assertIs<VerifyFailure.ModelNotAvailable>(c(403, """{"code":30011,"message":"The model is not available for your account.","data":null}"""))
        assertIs<VerifyFailure.ModelNotAvailable>(c(400, """{"error":{"message":"该模型未开通，请先在控制台开通","type":"invalid_request_error"}}"""))
        assertIs<VerifyFailure.ModelNotAvailable>(c(403, """{"error":{"message":"Forbidden"}}"""))
        assertIs<VerifyFailure.ModelNotAvailable>(c(403, "<html><body>403 Forbidden</body></html>"))
        assertTrue(c(403, "{}").modelSpecific)
    }

    @Test fun modelNotFound() {
        assertIs<VerifyFailure.ModelNotFound>(c(404, """{"error":{"message":"The model `gpt-9` does not exist or you do not have access to it.","type":"invalid_request_error","param":null,"code":"model_not_found"}}"""))
        assertIs<VerifyFailure.ModelNotFound>(c(400, """{"error":{"message":"Model Not Exist","type":"invalid_request_error","param":null,"code":"invalid_request_error"}}"""))
        assertIs<VerifyFailure.ModelNotFound>(c(400, """{"code":20012,"message":"Model does not exist. Please check it carefully.","data":null}"""))
        assertIs<VerifyFailure.ModelNotFound>(c(400, """{"error":{"code":"1211","message":"模型不存在，请检查模型代码。"}}"""))
        assertIs<VerifyFailure.ModelNotFound>(c(404, """{"error":{"message":"Not found the model kimi-x or Permission denied","type":"resource_not_found_error"}}"""))
        assertIs<VerifyFailure.ModelNotFound>(c(404, """{"error":{"code":"InvalidEndpointOrModel.NotFound","message":"The model or endpoint doubao-x does not exist or you do not have access to it.","type":"NotFound"}}"""))
        assertIs<VerifyFailure.ModelNotFound>(c(404, """{"error":{"message":"model \"qwen3:8b\" not found, try pulling it first","type":"api_error"}}"""))
    }

    @Test fun toolsUnsupported() {
        assertIs<VerifyFailure.ToolsUnsupported>(c(400, """{"error":{"message":"registry.ollama.ai/library/gemma3:4b does not support tools","type":"api_error","param":null,"code":null}}"""))
        assertIs<VerifyFailure.ToolsUnsupported>(c(400, """{"object":"error","message":"\"auto\" tool choice requires --enable-auto-tool-choice and --tool-call-parser to be set","type":"BadRequestError","code":400}"""))
        assertIs<VerifyFailure.ToolsUnsupported>(c(400, """{"error":{"message":"deepseek-reasoner does not support Function Calling","type":"invalid_request_error"}}"""))
        assertIs<VerifyFailure.ToolsUnsupported>(c(400, """{"error":{"message":"当前模型不支持工具调用"}}"""))
    }

    @Test fun stopConditions() {
        val balance = c(402, """{"error":{"message":"Insufficient Balance","type":"unknown_error","param":null,"code":"invalid_request_error"}}""")
        assertIs<VerifyFailure.Quota>(balance); assertFalse(balance.modelSpecific)
        assertIs<VerifyFailure.Quota>(c(429, """{"error":{"message":"Your account is suspended due to insufficient balance","type":"exceeded_current_quota_error"}}"""))
        assertIs<VerifyFailure.Quota>(c(429, """{"error":{"code":"1113","message":"您的账户已欠费，请充值后重试。"}}"""))
        val down = c(503, """{"error":{"message":"Service Unavailable"}}""")
        assertIs<VerifyFailure.Server>(down); assertFalse(down.modelSpecific)
        // A bare 404 is the address, not the model.
        val path = c(404, "404 page not found")
        assertEquals(VerifyFailure.Server(404, "404 page not found"), path); assertFalse(path.modelSpecific)
        assertFalse(c(403, """{"error":{"code":"unsupported_country_region_territory","message":"Country, region, or territory not supported"}}""").modelSpecific)
        // An unexplained 400 is about this request for this model: worth trying the next.
        assertTrue(c(400, """{"error":{"message":"Invalid request: temperature out of range"}}""").modelSpecific)
        assertIs<VerifyFailure.Network>(VerifyErrors.ofThrowable(java.net.ConnectException("failed to connect to /192.168.1.5 (port 11434): connect failed: ECONNREFUSED (Connection refused)")))
        assertIs<VerifyFailure.Network>(VerifyErrors.ofThrowable(java.net.SocketTimeoutException("timeout")))
        assertIs<VerifyFailure.KeyInvalid>(VerifyErrors.ofThrowable(HttpStatusException(401, "{}")))
    }

    @Test fun serverMessageShapes() {
        assertEquals("Model disabled.", VerifyErrors.serverMessage("""{"code":30003,"message":"Model disabled.","data":null}"""))
        assertEquals("boom", VerifyErrors.serverMessage("""{"error":"boom"}"""))
        assertEquals("bad", VerifyErrors.serverMessage("""{"base_resp":{"status_code":1004,"status_msg":"bad"}}"""))
        assertEquals("Bad Gateway", VerifyErrors.serverMessage("<html><h1>Bad Gateway</h1></html>"))
        assertEquals(300, VerifyErrors.serverMessage("x".repeat(1000)).length)
    }

    // ── candidates ──

    private val zhipu = presetOf("zhipu")
    @Test fun rankingPrefersMainThenVendorThenToolsThenListOrder() {
        val listed = listOf(RemoteModel("BAAI/bge-m3"), RemoteModel("a-chat"), RemoteModel("b-tools", tools = true), RemoteModel("embedding-3"),
            RemoteModel("glm-4.5"), RemoteModel("glm-5.3"), RemoteModel("cogview-4", chat = false), RemoteModel("whisper-1"))
        assertEquals(listOf("glm-5.3", "glm-4.5", "b-tools"), rankCandidates(listed, zhipu))
        assertEquals(listOf("a-chat", "glm-5.3", "glm-4.5"), rankCandidates(listed, zhipu, main = "a-chat"))
        // What the user ticked is the pool; their order breaks ties.
        assertEquals(listOf("b-tools", "a-chat"), rankCandidates(listed, zhipu, checked = listOf("a-chat", "b-tools", "embedding-3")))
        // Aggregator: no family, tool-capable first, then listing order.
        val sf = presetOf("siliconflow")
        assertEquals(listOf("b-tools", "a-chat", "glm-4.5"), rankCandidates(listed, sf))
        assertEquals(listOf("only"), rankCandidates(emptyList(), null, checked = listOf("only")))
        assertEquals(emptyList<String>(), rankCandidates(listOf(RemoteModel("text-embedding-3-small")), presetOf("openai")))
    }

    @Test fun nonChatNames() {
        listOf("text-embedding-3-large", "BAAI/bge-reranker-v2-m3", "tts-1", "whisper-large-v3", "dall-e-3", "omni-moderation-latest",
            "gpt-4o-realtime-preview", "FunAudioLLM/CosyVoice2-0.5B", "Kwai-Kolors/Kolors", "deepseek-ocr", "qwen-image", "jina-reranker-m0",
            "stabilityai/stable-diffusion-3-5-large", "paraformer-asr", "llama-guard-4").forEach { assertTrue(it, looksNonChat(it)) }
        listOf("gpt-5", "deepseek-ai/DeepSeek-V4-Flash", "Qwen/Qwen3-VL-235B", "glm-5.3", "kimi-k3", "MiniMax-M3", "doubao-seed-2-1-pro-260915",
            "llama3.3:70b", "o4-mini").forEach { assertFalse(it, looksNonChat(it)) }
    }

    @Test fun listingHints() {
        val body = """{"data":[
            {"id":"or/a","architecture":{"output_modalities":["image"]},"supported_parameters":["temperature"]},
            {"id":"or/b","architecture":{"output_modalities":["text"]},"supported_parameters":["tools","tool_choice"]},
            {"id":"t/e","type":"embedding"},
            {"id":"m/c","capabilities":{"completion_chat":true,"function_calling":false}},
            {"id":"f/d","supports_tools":true},
            {"id":"plain"}]}"""
        val m = parseModels(body).associateBy { it.id }
        assertEquals(false, m["or/a"]!!.chat); assertEquals(false, m["or/a"]!!.tools)
        assertEquals(true, m["or/b"]!!.chat); assertEquals(true, m["or/b"]!!.tools)
        assertEquals(false, m["t/e"]!!.chat)
        assertEquals(false, m["m/c"]!!.tools)
        assertEquals(true, m["f/d"]!!.tools)
        assertNull(m["plain"]!!.chat); assertNull(m["plain"]!!.tools)
    }

    // ── reading the answer ──

    @Test fun answerParsing() {
        fun read(s: String) = readProbeAnswer(s.lineSequence())
        assertNull(read("""
            data: {"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"ok"}}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"$PROBE_TOOL","arguments":""}}]}}]}

            data: [DONE]
        """.trimIndent()))
        // A server that ignores stream:true.
        assertNull(read("""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"x","type":"function","function":{"name":"$PROBE_TOOL","arguments":"{}"}}]}}]}"""))
        val text = read("""
            data: {"choices":[{"delta":{"content":"Sure, "}}]}
            data: {"choices":[{"delta":{"content":"I checked."},"finish_reason":"stop"}]}
            data: {"choices":[],"usage":{"prompt_tokens":10}}
            data: [DONE]
        """.trimIndent())
        assertEquals(VerifyFailure.NoToolCall("Sure, I checked."), text)
        assertIs<VerifyFailure.ToolsUnsupported>(read("""data: {"error":{"message":"this model does not support tools","code":"400"}}"""))
        assertIs<VerifyFailure.Server>(read("data: not json"))
    }

    @Test fun toolChoiceRejectionRetriesWithoutIt() {
        val asked = mutableListOf<Boolean>()
        val r = probeModel("u", null, "m") { force -> asked += force; if (force) VerifyFailure.Server(400, "tool_choice is not supported in thinking mode") else null }
        assertNull(r); assertEquals(listOf(true, false), asked)
        asked.clear()
        assertIs<VerifyFailure.ModelNotFound>(probeModel("u", null, "m") { force -> asked += force; VerifyFailure.ModelNotFound("nope") })
        assertEquals(listOf(true), asked)
    }

    // ── the flow ──

    @Test fun flowStopsOnlyForNonModelFailures() {
        val probe = mapOf<String, VerifyFailure?>("a" to VerifyFailure.ModelNotAvailable("未开通"), "b" to VerifyFailure.NoToolCall("hi"), "c" to null, "d" to null)
        val tried = mutableListOf<String>()
        val ok = verifyCandidates(listOf("a", "b", "c", "d"), onTesting = { tried += it }) { probe[it] }
        assertEquals("c", (ok as VerifyOutcome.Passed).model)
        assertEquals(listOf("a", "b"), ok.failed.map { it.first }); assertEquals(listOf("a", "b", "c"), tried)

        val none = verifyCandidates(listOf("a", "b", "x", "c")) { if (it == "c") null else VerifyFailure.ModelNotFound(it) }
        assertIs<VerifyOutcome.NoModelWorked>(none); assertEquals(3, none.failed.size)

        tried.clear()
        val key = verifyCandidates(listOf("a", "b"), onTesting = { tried += it }) { VerifyFailure.KeyInvalid("bad") }
        assertIs<VerifyOutcome.Stopped>(key); assertEquals(listOf("a"), tried)
    }

    @Test fun savePlans() {
        val passed = VerifyOutcome.Passed("b", listOf("a" to VerifyFailure.ModelNotFound("x")))
        assertEquals(SavePlan(true, listOf("a", "c", "b"), listOf("c", "b"), "b"), planSave(passed, listOf("a", "c"), listOf("a", "c", "gone"), makeMain = true))
        assertEquals(SavePlan(true, listOf("b"), listOf("b"), null), planSave(passed, emptyList(), emptyList(), makeMain = false))
        val none = VerifyOutcome.NoModelWorked(listOf("a" to VerifyFailure.NoToolCall(""), "b" to VerifyFailure.ModelNotFound("")))
        assertEquals(SavePlan(true, listOf("a", "b"), emptyList(), null), planSave(none, emptyList(), listOf("a"), makeMain = true))
        assertFalse(planSave(VerifyOutcome.Stopped(VerifyFailure.KeyInvalid(""), emptyList()), listOf("a"), emptyList(), true).save)
    }

    // ── against a local HTTP server ──

    private lateinit var server: HttpServer
    private val requests = Collections.synchronizedList(mutableListOf<Pair<Map<String, String>, String>>())
    /** model → (status, content type, body) */
    private val answers = mutableMapOf<String, Triple<Int, String, String>>()
    private val base get() = "http://127.0.0.1:${server.address.port}/v1"

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            requests += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") } to body
            val model = Json.parseToJsonElement(body).jsonObject["model"]!!.jsonPrimitive.content
            val (status, type, text) = answers[model] ?: Triple(404, "application/json", """{"error":{"message":"The model `$model` does not exist","code":"model_not_found"}}""")
            val bytes = text.toByteArray()
            ex.responseHeaders.add("content-type", type)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After fun stopServer() = server.stop(0)

    private fun sse(vararg frames: String) = frames.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
    private val toolCall = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c","type":"function","function":{"name":"$PROBE_TOOL","arguments":"{}"}}]}}]}"""

    @Test fun realRequestMirrorsTheEngine() {
        answers["good"] = Triple(200, "text/event-stream", sse("""{"choices":[{"delta":{"role":"assistant"}}]}""", toolCall))
        assertNull(probeModel(base, "sk-test", "good"))
        val (headers, body) = requests.single()
        assertEquals("Bearer sk-test", headers["authorization"])
        assertEquals("text/event-stream", headers["accept"])
        assertTrue(headers["user-agent"]!!.startsWith("deepseek-harness"))
        val o = Json.parseToJsonElement(body) as JsonObject
        assertEquals("true", o["stream"]!!.jsonPrimitive.content)
        assertEquals(PROBE_TOOL, o["tools"].toString().let { Regex("\"name\":\"([^\"]+)\"").find(it)!!.groupValues[1] })
        assertTrue(o.containsKey("tool_choice"))
        assertTrue(o["stream_options"].toString().contains("include_usage"))
    }

    @Test fun flowAgainstFakeServer() {
        answers["glm-5.3"] = Triple(403, "application/json", """{"error":{"code":"1220","message":"您无权访问 glm-5.3"}}""")
        answers["chatty"] = Triple(200, "text/event-stream", sse("""{"choices":[{"delta":{"content":"pong"},"finish_reason":"stop"}]}"""))
        answers["works"] = Triple(200, "text/event-stream", sse(toolCall))
        val listed = listOf(RemoteModel("chatty"), RemoteModel("embedding-3"), RemoteModel("glm-5.3"), RemoteModel("works"))
        val candidates = rankCandidates(listed, presetOf("zhipu"))
        assertEquals(listOf("glm-5.3", "chatty", "works"), candidates)
        val out = verifyCandidates(candidates) { probeModel(base, "k", it) }
        assertEquals("works", (out as VerifyOutcome.Passed).model)
        assertIs<VerifyFailure.ModelNotAvailable>(out.failed[0].second)
        assertEquals(VerifyFailure.NoToolCall("pong"), out.failed[1].second)

        // A key problem on the first model stops at once.
        answers["k1"] = Triple(401, "application/json", """{"error":{"message":"Invalid Authentication","type":"invalid_authentication_error"}}""")
        requests.clear()
        assertIs<VerifyOutcome.Stopped>(verifyCandidates(listOf("k1", "works")) { probeModel(base, "k", it) })
        assertEquals(1, requests.size)
    }

    @Test fun unreachableIsNetwork() {
        val port = ServerSocket(0).use { it.localPort }
        val f = probeToolCall("http://127.0.0.1:$port/v1", null, "m", connectTimeoutMs = 2_000)
        assertIs<VerifyFailure.Network>(f)
        assertFalse(f!!.modelSpecific)
    }
}
