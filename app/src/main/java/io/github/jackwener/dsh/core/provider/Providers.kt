package io.github.jackwener.dsh.core.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Third-party model providers (OpenAI-compatible Chat Completions endpoints), served in the engine
 * by the vendored `@snnh/dsh-llm-service-lite` row. See docs/ENGINE-CONTRACT.md §1.1.
 *
 * Presets: base URLs follow magpie's provider table (github.com/yetone/magpie,
 * internal/provider/presets.go, MIT), restricted to each vendor's OpenAI-compatible endpoint.
 * Default model ids are only a starting point; "获取模型列表" asks the endpoint's `/models`.
 * Adding a preset is one line.
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val models: List<String> = emptyList(),
    /** The custom preset leaves the key optional (e.g. a self-hosted gateway without auth). */
    val keyOptional: Boolean = false,
    /** The user edits the address (custom endpoint; HTTPS only). */
    val editableUrl: Boolean = false,
    val keysUrl: String? = null,
)

val PRESETS = listOf(
    ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", listOf("gpt-5"), keysUrl = "https://platform.openai.com/api-keys"),
    ProviderPreset("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", listOf("claude-sonnet-5", "claude-opus-5"), keysUrl = "https://console.anthropic.com/settings/keys"),
    ProviderPreset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", listOf("gemini-3.5-flash"), keysUrl = "https://aistudio.google.com/apikey"),
    ProviderPreset("xai", "xAI Grok", "https://api.x.ai/v1", listOf("grok-4"), keysUrl = "https://console.x.ai"),
    ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", listOf("openrouter/auto"), keysUrl = "https://openrouter.ai/keys"),
    ProviderPreset("kimi", "Kimi (Moonshot)", "https://api.moonshot.cn/v1", listOf("kimi-k3"), keysUrl = "https://platform.moonshot.cn/console/api-keys"),
    ProviderPreset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", listOf("glm-5.3"), keysUrl = "https://open.bigmodel.cn/usercenter/apikeys"),
    ProviderPreset("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf("qwen3.8-max"), keysUrl = "https://bailian.console.aliyun.com/?tab=model#/api-key"),
    ProviderPreset("ark", "火山方舟 / 豆包", "https://ark.cn-beijing.volces.com/api/v3", listOf("doubao-seed-2.1-pro"), keysUrl = "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey"),
    ProviderPreset("minimax", "MiniMax", "https://api.minimaxi.com/v1", listOf("MiniMax-M3.1"), keysUrl = "https://platform.minimaxi.com/user-center/basic-information/interface-key"),
    ProviderPreset("siliconflow", "硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1", keysUrl = "https://cloud.siliconflow.cn/account/ak"),
    ProviderPreset("stepfun", "阶跃星辰 StepFun", "https://api.stepfun.com/v1", listOf("step-5"), keysUrl = "https://platform.stepfun.com/interface-key"),
    ProviderPreset(CUSTOM, "自定义（OpenAI 兼容）", "", keyOptional = true, editableUrl = true),
)

const val CUSTOM = "custom"

fun presetOf(id: String?): ProviderPreset? = PRESETS.firstOrNull { it.id == id }

/** One configured provider = one engine route (`id`), all its models on Chat Completions. */
@Serializable
data class ProviderConfig(
    /** Route id: the preset id, `-2`, `-3`… for repeats. Never a DeepSeek route name. */
    val id: String,
    val preset: String,
    val name: String,
    val baseUrl: String,
    val models: List<String>,
    val enabled: Boolean = true,
    /** A key is stored for it in SecretStore (the value itself never leaves Keystore-encrypted storage except as env). */
    val hasKey: Boolean = false,
    /** User-set context window for every model of this route (null = per model / detected / default). */
    val contextWindow: Long? = null,
    /** User-set output cap (`max_tokens`) for every model of this route. */
    val maxTokens: Int? = null,
    /** Per-model limits: the user's overrides and what `/models` reported. Absent = nothing known. */
    val limits: Map<String, ModelLimits> = emptyMap(),
) {
    /** `apiKeyEnv` the engine resolves per request; set in the `:engine` environment only. */
    val keyEnv: String get() = keyEnvOf(id)
    val secretName: String get() = "provider-$id"
}

fun keyEnvOf(routeId: String) = "DSH_ANDROID_KEY_" + routeId.uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

/** A fresh route id for [preset] that no existing provider (or DeepSeek) owns. */
fun newRouteId(preset: String, existing: Collection<String>): String {
    val base = preset.lowercase().filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { CUSTOM }.let { if (it.startsWith("deepseek")) "x-$it" else it }
    if (base !in existing) return base
    return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in existing }
}

/** One model's limits as stored: user overrides and the values the endpoint's `/models` reported. */
@Serializable
data class ModelLimits(
    val contextWindow: Long? = null,
    val maxTokens: Int? = null,
    val detectedContextWindow: Long? = null,
    val detectedMaxTokens: Int? = null,
) {
    val isEmpty get() = contextWindow == null && maxTokens == null && detectedContextWindow == null && detectedMaxTokens == null
}

/** Where an effective limit came from (shown next to the value). */
enum class LimitSource { DETECTED, MANUAL, DEFAULT }

/** The limits a third-party model actually runs with (UI ring and the engine patch agree on these). */
data class EffectiveLimits(
    val contextWindow: Long,
    val contextSource: LimitSource,
    val maxTokens: Int,
    val maxTokensSource: LimitSource,
    /** compaction-basic `headroomTokens`: dsh compacts at min(80 % window, window − maxTokens − headroom). */
    val compactionHeadroom: Int,
)

/** Defaults and sanity bounds for third-party models (no per-model matrices; see ARCHITECTURE). */
object ThirdParty {
    /** Context window when neither the user nor `/models` says. Conservative: most current models have ≥128K. */
    const val DEFAULT_CONTEXT_WINDOW = 131_072L
    /** Output cap (`max_tokens`) when unset: vendors' own defaults are sometimes tiny (1–4K), truncating tool calls. */
    const val DEFAULT_MAX_TOKENS = 16_384
    /**
     * Compaction headroom. dsh's default (64K) on a 128K window would compact at
     * 128K − 16K − 64K = 48K; with 16K it compacts at ~96K (75 %), still well inside the window.
     * Capped at 1/8 of the window so tiny windows keep a positive threshold.
     */
    const val COMPACTION_HEADROOM = 16_384
    const val MIN_CONTEXT_WINDOW = 4_096L
    const val MAX_CONTEXT_WINDOW = 10_000_000L
    const val MIN_MAX_TOKENS = 256

    /**
     * Resolve one model's limits. Precedence: per-model override, provider-level override, the
     * `/models` value, the default. Bounds: window in [MIN_CONTEXT_WINDOW, MAX_CONTEXT_WINDOW];
     * output cap at most half the window when the user set it, a quarter otherwise (a listed
     * `max_completion_tokens` is the model's ceiling, not a good per-request ask, and a big cap
     * pulls the compaction threshold window − maxTokens − headroom down).
     */
    fun resolve(model: ModelLimits?, providerWindow: Long?, providerMaxTokens: Int?): EffectiveLimits {
        val (rawCtx, ctxSrc) = when {
            model?.contextWindow != null -> model.contextWindow to LimitSource.MANUAL
            providerWindow != null -> providerWindow to LimitSource.MANUAL
            model?.detectedContextWindow != null -> model.detectedContextWindow to LimitSource.DETECTED
            else -> DEFAULT_CONTEXT_WINDOW to LimitSource.DEFAULT
        }
        val ctx = rawCtx.coerceIn(MIN_CONTEXT_WINDOW, MAX_CONTEXT_WINDOW)
        val (rawMax, maxSrc) = when {
            model?.maxTokens != null -> model.maxTokens to LimitSource.MANUAL
            providerMaxTokens != null -> providerMaxTokens to LimitSource.MANUAL
            model?.detectedMaxTokens != null -> model.detectedMaxTokens to LimitSource.DETECTED
            else -> DEFAULT_MAX_TOKENS to LimitSource.DEFAULT
        }
        val cap = (if (maxSrc == LimitSource.MANUAL) ctx / 2 else ctx / 4).toInt()
        val max = rawMax.coerceIn(MIN_MAX_TOKENS, cap)
        return EffectiveLimits(ctx, ctxSrc, max, maxSrc, minOf(COMPACTION_HEADROOM.toLong(), ctx / 8).toInt())
    }
}

fun ProviderConfig.limitsOf(model: String): EffectiveLimits = ThirdParty.resolve(limits[model], contextWindow, maxTokens)

/** What the composer / engine route resolves to. `provider == null` is DeepSeek. */
data class ModelChoice(val provider: ProviderConfig?, val model: String) {
    val isDeepSeek get() = provider == null
    /** Third-party limits; null for DeepSeek. */
    val limits: EffectiveLimits? get() = provider?.limitsOf(model)
    val contextWindow get() = limits?.contextWindow ?: DEEPSEEK_CONTEXT
    /** Where [contextWindow] came from; null for DeepSeek (a fixed fact). */
    val contextSource get() = limits?.contextSource
    companion object { const val DEEPSEEK_CONTEXT = 1_000_000L }
}

val DEEPSEEK_MODELS = listOf("deepseek-flash" to "DeepSeek-V4.1-Flash", "deepseek-v4-pro" to "DeepSeek-V4-Pro")
const val DEFAULT_DEEPSEEK_MODEL = "deepseek-flash"

/**
 * Resolve the saved selection: a third-party (provider, model) only while that provider exists, is
 * enabled and still lists the model; otherwise DeepSeek (the saved DeepSeek model, or flash).
 */
fun resolveChoice(provider: String, model: String, providers: List<ProviderConfig>): ModelChoice {
    if (provider.isNotEmpty()) {
        val p = providers.firstOrNull { it.id == provider && it.enabled }
        if (p != null && model in p.models) return ModelChoice(p, model)
    }
    return ModelChoice(null, model.takeIf { m -> DEEPSEEK_MODELS.any { it.first == m } } ?: DEFAULT_DEEPSEEK_MODEL)
}

/**
 * The user patch layer `$DSH_HOME/profiles/android/cordis.patch.yml` (dsh applies it after every
 * bundle). The app is its only writer and rewrites it before each engine start; it carries no
 * secret, only `apiKeyEnv` names. Values are JSON (a YAML subset), so no quoting rules apply.
 *
 * @param webSearch false turns off the `web_search` tool (it needs a DeepSeek credential: the
 *   API key, or the account login only while the conversation uses the account route).
 */
fun renderProfilePatch(providers: List<ProviderConfig>, webSearch: Boolean): String {
    val active = providers.filter { it.enabled && it.models.isNotEmpty() && it.baseUrl.isNotBlank() }
    val json = Json { prettyPrint = false }
    val sb = StringBuilder()
    sb.append("# Generated by DSH-Android (设置 → 模型服务). Rewritten before every engine start; edits here are lost.\n")
    if (active.isEmpty() && webSearch) return sb.append("[]\n").toString()
    if (active.isNotEmpty()) {
        val providersJson = buildJsonObject {
            putJsonObject("providers") {
                active.forEach { p ->
                    putJsonObject(p.id) {
                        put("displayName", p.name)
                        put("interfaceType", "openai-chat-completions")
                        put("baseURL", p.baseUrl.trim().trimEnd('/'))
                        if (p.hasKey) put("apiKeyEnv", p.keyEnv)
                        put("includeUsage", true)
                        putJsonArray("models") {
                            p.models.forEach { m ->
                                val l = p.limitsOf(m)
                                addJsonObject {
                                    put("id", m)
                                    put("contextWindow", l.contextWindow)
                                    // Capacity and the per-request ask (sent as max_tokens) are the same value.
                                    put("maxTokens", l.maxTokens)
                                    putJsonObject("defaults") { put("maxTokens", l.maxTokens) }
                                }
                            }
                        }
                    }
                }
            }
        }
        sb.append("- id: llm-service-lite\n  config: ").append(json.encodeToString(JsonObject.serializer(), providersJson)).append('\n')
        val compaction = buildJsonObject {
            putJsonArray("modelPolicies") {
                active.forEach { p -> p.models.forEach { m -> addJsonObject { put("provider", p.id); put("model", m); put("headroomTokens", p.limitsOf(m).compactionHeadroom) } } }
            }
        }
        sb.append("- id: compaction-basic\n  config: ").append(json.encodeToString(JsonObject.serializer(), compaction)).append('\n')
    }
    // dsh-base's tool-web config, with search off (a patch replaces the whole config).
    if (!webSearch) sb.append("- id: tool-web\n  config: {\"search\":false,\"fetch\":true,\"searchTimeoutMs\":60000}\n")
    return sb.toString()
}

/** One `/models` entry: the id and whatever limits the endpoint reports for it. */
data class RemoteModel(val id: String, val contextWindow: Long? = null, val maxTokens: Int? = null)

/**
 * `GET {baseUrl}/models` (OpenAI shape `{data:[{id}]}`, or a bare array / `{models:[…]}`).
 * Doubles as the connection test. Runs on the caller's thread (call from Dispatchers.IO).
 * https, or http for a user's LAN gateway (network_security_config permits cleartext).
 */
fun fetchModels(baseUrl: String, key: String?): Result<List<RemoteModel>> = runCatching {
    val url = URL(baseUrl.trim().trimEnd('/') + "/models")
    val c = url.openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        if (!key.isNullOrBlank()) {
            c.setRequestProperty("Authorization", "Bearer $key")
            // Anthropic's listing reads x-api-key (its OpenAI-compatible layer takes the bearer).
            if (url.host.endsWith("anthropic.com")) { c.setRequestProperty("x-api-key", key); c.setRequestProperty("anthropic-version", "2023-06-01") }
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString().take(4_000_000) }.orEmpty()
        if (code !in 200..299) error("HTTP $code ${body.take(160).replace('\n', ' ')}".trim())
        parseModels(body)
    } finally { c.disconnect() }
}

fun parseModelIds(body: String): List<String> = parseModels(body).map { it.id }

/**
 * Context-window fields seen in `/models` listings, in order of preference:
 * OpenRouter / Moonshot / Together / Fireworks `context_length`, Groq `context_window`,
 * vLLM / sglang `max_model_len`, Mistral / LM Studio `max_context_length`, Anthropic
 * `max_input_tokens`, llama.cpp `n_ctx`, Gemini `inputTokenLimit`.
 */
private val CONTEXT_KEYS = listOf("context_length", "context_window", "max_model_len", "max_context_length", "max_input_tokens", "n_ctx", "inputTokenLimit", "contextWindow", "context_size")
/** Output-cap fields: OpenAI-style `max_output_tokens`, Groq / OpenRouter `max_completion_tokens`, Gemini `outputTokenLimit`. */
private val OUTPUT_KEYS = listOf("max_output_tokens", "max_completion_tokens", "outputTokenLimit", "max_tokens", "maxOutputTokens", "maxTokens")
/** Nested objects that carry the same fields (OpenRouter `top_provider`, llama.cpp `meta`, DeepInfra `metadata`). */
private val NESTED_KEYS = listOf("top_provider", "meta", "metadata", "limits")

/** A positive whole number from a JSON number or numeric string (`262144`, `262144.0`, `"262144"`). */
private fun positiveCount(e: JsonElement?): Long? {
    val p = e as? JsonPrimitive ?: return null
    val d = p.content.trim().toDoubleOrNull() ?: return null
    if (!d.isFinite() || d < 1 || d != Math.floor(d) || d > Long.MAX_VALUE / 2) return null
    return d.toLong()
}

private fun firstCount(o: JsonObject, keys: List<String>): Long? {
    keys.forEach { k -> positiveCount(o[k])?.let { return it } }
    NESTED_KEYS.forEach { n -> (o[n] as? JsonObject)?.let { inner -> keys.forEach { k -> positiveCount(inner[k])?.let { return it } } } }
    return null
}

/** Parse a `/models` body into entries (sorted by id, distinct) with any reported limits. */
fun parseModels(body: String): List<RemoteModel> {
    val root = Json.parseToJsonElement(body)
    val entries: List<Pair<String?, JsonElement>> = when {
        root is JsonArray -> root.map { null to it }
        root is JsonObject && root["data"] is JsonArray -> root["data"]!!.jsonArray.map { null to it }
        root is JsonObject && root["models"] is JsonArray -> root["models"]!!.jsonArray.map { null to it }
        root is JsonObject && root["models"] is JsonObject -> root["models"]!!.jsonObject.entries.map { (k, v) -> k to v }
        else -> error("unexpected /models response")
    }
    return entries.mapNotNull { (key, e) ->
        runCatching {
            val o = e as? JsonObject
            val id = o?.let { (it["id"] ?: it["name"] ?: it["model"])?.jsonPrimitive?.content } ?: key ?: e.jsonPrimitive.content
            val ctx = o?.let { firstCount(it, CONTEXT_KEYS) }
            val out = o?.let { firstCount(it, OUTPUT_KEYS) }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            RemoteModel(id.removePrefix("models/"), ctx, out)
        }.getOrNull()
    }.filter { it.id.isNotBlank() }.distinctBy { it.id }.sortedBy { it.id }
}

/** The editor's model list after a successful fetch, and which kept entries the endpoint no longer returned. */
data class OfferedRefresh(val offered: List<String>, val missing: List<String>)

/**
 * After a successful `/models` fetch the list is exactly what the endpoint returned, except that
 * [keep] (models the user checked, or typed in by hand) the endpoint did not return stay on top,
 * flagged as missing, so the user decides about them instead of losing them silently.
 */
fun refreshOffered(current: List<String>, fetched: List<String>, keep: Collection<String>): OfferedRefresh {
    val returned = fetched.toSet()
    val keepSet = keep.toSet()
    val missing = (current.filter { it in keepSet } + keep).filter { it !in returned }.distinct()
    return OfferedRefresh(missing + fetched.distinct(), missing)
}

/** User text for a token count: digits (separators `,` `_` and spaces ignored); blank = null; else invalid. */
fun parseTokenInput(text: String): Result<Long?> {
    val t = text.filter { it != ',' && it != '_' && !it.isWhitespace() }
    if (t.isEmpty()) return Result.success(null)
    val n = t.toLongOrNull()?.takeIf { it > 0 } ?: return Result.failure(IllegalArgumentException(text))
    return Result.success(n)
}
