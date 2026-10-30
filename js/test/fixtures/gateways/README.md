# Gateway replay fixtures

Raw response bodies from OpenAI-compatible chat-completions endpoints, replayed byte for byte by
[`../../gateway-replay.test.mjs`](../../gateway-replay.test.mjs) against the real vendored adapter
(`@snnh/dsh-llm-service-lite` with patches 14, 16, 17 and 18 applied in a built stage tree).

## Adding a case

Two files per case:

- `<case>.<sse|json|txt>`: the response body exactly as the endpoint sent it (SSE frames, comment
  lines, CRLF, missing `[DONE]` and all). Scrub ids, request ids, account ids and keys; keep the shape.
- `<case>.expect.json`:

```jsonc
{
  "provider": "who sent it (endpoint)",
  "source": "live capture <date>, <model>, <settings>" | "from docs: <url / description of the documented format or reported quirk>",
  "body": "<case>.sse",                  // file name of the body
  "model": "model id sent",              // also selects the adapter's model family facts
  "basePath": "/v1",                     // optional, path part of the base URL ("" = bare origin)
  "capabilities": { },                   // optional model capabilities for the route
  "http": { "status": 200, "contentType": "text/event-stream", "headers": { } },
  "expect": {
    "text": "assembled answer text",
    "reasoning": "assembled thinking text",
    "toolCalls": [{ "name": "get_weather", "arguments": { "city": "Paris" }, "id": "optional exact id" }],
    "usage": { "inputTokens": 0, "outputTokens": 0, "totalTokens": 0, "cacheReadTokens": 0, "cacheWriteTokens": 0 },  // null = must be absent
    "finish": "stop | tool-calls | max-tokens",
    "replayFields": ["reasoning_content"],  // where the thinking was recorded to arrive (replay state)
    "error": { "code": "AUTH", "status": 401, "messageIncludes": "...", "retryAfterMs": 2000 }  // instead of the above
  },
  "echo": {                               // optional: follow-up request after this turn (+ tool results)
    "reasoning_content": "exact text" | true,  // any top-level assistant-message field, deep-equal
    "content": [ ],                        // e.g. Mistral thinking parts
    "extra_content": [ ],                  // per tool call, null where none
    "absent": ["reasoning"]                // fields that must not be sent
  }
}
```

Every field of `expect` is optional; only what is stated is checked. Every successful case also
checks chunk-stream invariants (one terminal `finish`, every block started once and ended once,
no delta outside its block, unique call ids). Every `echo` case also checks that the same history
sent to another provider route, or to the same route after its base URL changed, carries no
thinking at all.

`source` must start with `live capture` or `from docs`. Live captures are recorded once against
the real endpoint and scrubbed; "from docs" bodies are written from the provider's documented
streaming / error format or from a quirk reported in client issue trackers, and are only as good
as that description. "(synthetic)" marks a shape inferred from how a class of gateway behaves, not
from its own documentation. Replace a "from docs" body with a live capture whenever one is
available.

The DeepSeek captures were taken on 2026-10-30 from `https://api.deepseek.com/v1` with model
`deepseek-flash`. The same session also showed that DeepSeek accepted a thinking-mode tool
follow-up *without* `reasoning_content` (HTTP 200; that turn had no thinking), and that it ignores
unknown assistant fields (`reasoning`, `reasoning_details`).

## Cases

| Case | Endpoint | Source | Checks |
|---|---|---|---|
| `ark-401` | Volcano Engine Ark (ark.cn-beijing.volces.com/api/v3) | from docs | error AUTH |
| `ark-403-access-denied` | Volcano Engine Ark (ark.cn-beijing.volces.com/api/v3) | from docs | error MODEL_NOT_AVAILABLE |
| `ark-403-overdue` | Volcano Engine Ark (ark.cn-beijing.volces.com/api/v3) | from docs | error QUOTA |
| `ark-404-model-not-activated` | Volcano Engine Ark (ark.cn-beijing.volces.com/api/v3) | from docs | error MODEL_NOT_AVAILABLE |
| `ark-429` | Volcano Engine Ark (ark.cn-beijing.volces.com/api/v3) | from docs | error RATE_LIMIT |
| `ark-reasoning-tools` | Volcano Engine Ark (ark.cn-beijing.volces.com/api/v3) | from docs | reasoning, 1 tool call(s), echo |
| `azure-400-content-filter` | Azure OpenAI (<resource>.openai.azure.com/openai/v1) | from docs | error INVALID_REQUEST |
| `azure-401` | Azure OpenAI (<resource>.openai.azure.com/openai/v1) | from docs | error AUTH |
| `azure-429-retry-after` | Azure OpenAI (<resource>.openai.azure.com/openai/v1) | from docs | error RATE_LIMIT |
| `azure-content-filter-finish` | Azure OpenAI (<resource>.openai.azure.com/openai/v1) | from docs | text |
| `azure-filter-frames` | Azure OpenAI (<resource>.openai.azure.com/openai/v1) | from docs | text |
| `deepseek-400-bad-tool-schema` | DeepSeek official (api.deepseek.com/v1) | **live capture** | error INVALID_REQUEST |
| `deepseek-400-model-not-found` | DeepSeek official (api.deepseek.com/v1) | **live capture** | error INVALID_REQUEST |
| `deepseek-401` | DeepSeek official (api.deepseek.com/v1) | **live capture** | error AUTH |
| `deepseek-chat-stream` | DeepSeek official (api.deepseek.com/v1) | **live capture** | text |
| `deepseek-thinking-followup` | DeepSeek official (api.deepseek.com/v1) | **live capture** | text |
| `deepseek-thinking-tool-call-nonstream` | DeepSeek official (api.deepseek.com/v1) | **live capture** | reasoning, 1 tool call(s), echo |
| `deepseek-thinking-tool-call` | DeepSeek official (api.deepseek.com/v1) | **live capture** | reasoning, 1 tool call(s), echo |
| `gemini-400-key` | Google Gemini OpenAI compatibility (generativelanguage.googleapis.com/v1beta/openai) | from docs | error INVALID_REQUEST |
| `gemini-openai-compat-parallel-no-index` | Google Gemini OpenAI compatibility (generativelanguage.googleapis.com/v1beta/openai) | from docs | 2 tool call(s), echo |
| `glm-400-model` | Zhipu GLM (open.bigmodel.cn/api/paas/v4) | from docs | error INVALID_REQUEST |
| `glm-401` | Zhipu GLM (open.bigmodel.cn/api/paas/v4) | from docs | error AUTH |
| `glm-403-no-permission` | Zhipu GLM (open.bigmodel.cn/api/paas/v4) | from docs | error MODEL_NOT_AVAILABLE |
| `glm-429-arrears` | Zhipu GLM (open.bigmodel.cn/api/paas/v4) | from docs | error QUOTA |
| `glm-429-concurrency` | Zhipu GLM (open.bigmodel.cn/api/paas/v4) | from docs | error RATE_LIMIT |
| `glm-empty-arguments` | Zhipu GLM (open.bigmodel.cn/api/paas/v4) | from docs | 1 tool call(s) |
| `glm-network-error` | Zhipu GLM (open.bigmodel.cn/api/paas/v4) | from docs | error SERVER |
| `glm-sensitive` | Zhipu GLM (open.bigmodel.cn/api/paas/v4) | from docs | text |
| `glm-thinking-tools` | Zhipu GLM (open.bigmodel.cn/api/paas/v4) | from docs | reasoning, 1 tool call(s), echo |
| `kimi-400-model` | Moonshot Kimi (api.moonshot.cn/v1) | from docs | error INVALID_REQUEST |
| `kimi-401` | Moonshot Kimi (api.moonshot.cn/v1) | from docs | error AUTH |
| `kimi-429-quota` | Moonshot Kimi (api.moonshot.cn/v1) | from docs | error QUOTA |
| `kimi-429-rate` | Moonshot Kimi (api.moonshot.cn/v1) | from docs | error RATE_LIMIT |
| `kimi-thinking-tools-usage-in-choice` | Moonshot Kimi (api.moonshot.cn/v1) | from docs | reasoning, 1 tool call(s), echo |
| `lmstudio-200-unexpected-endpoint` | LM Studio (http://localhost:1234/v1) | from docs | error INVALID_REQUEST |
| `lmstudio-404-model` | LM Studio (http://localhost:1234/v1) | from docs | error INVALID_REQUEST |
| `lmstudio-reasoning-tools` | LM Studio (http://localhost:1234/v1) | from docs | reasoning, 1 tool call(s), echo |
| `magpie-401-gateway-key` | magpie-style local gateway (127.0.0.1:3425/v1, translating an Anthropic upstream) | from docs (synthetic) | error AUTH |
| `magpie-502-upstream` | magpie-style local gateway (127.0.0.1:3425/v1, translating an Anthropic upstream) | from docs (synthetic) | error TRANSPORT |
| `magpie-anthropic-upstream-no-done` | magpie-style local gateway (127.0.0.1:3425/v1, translating an Anthropic upstream) | from docs (synthetic) | reasoning, 1 tool call(s), text, echo |
| `minimax-reasoning-details-tools` | MiniMax (api.minimax.io/v1, reasoning_split: true) | from docs | reasoning, 1 tool call(s), echo |
| `mistral-magistral-text` | Mistral (api.mistral.ai/v1) | from docs | reasoning, text |
| `mistral-magistral-thinking-parts` | Mistral (api.mistral.ai/v1) | from docs | reasoning, 1 tool call(s), echo |
| `nim-401` | NVIDIA NIM (integrate.api.nvidia.com/v1) | from docs | error AUTH |
| `nim-404-bare-origin` | NVIDIA NIM (integrate.api.nvidia.com/v1) | from docs | error INVALID_REQUEST |
| `nim-404-function` | NVIDIA NIM (integrate.api.nvidia.com/v1) | from docs | error INVALID_REQUEST |
| `nim-429` | NVIDIA NIM (integrate.api.nvidia.com/v1) | from docs | error RATE_LIMIT |
| `nim-reasoning-tools` | NVIDIA NIM (integrate.api.nvidia.com/v1) | from docs | reasoning, 1 tool call(s), echo |
| `ollama-400-tools-unsupported` | Ollama (OpenAI compatibility, http://localhost:11434/v1) | from docs | error INVALID_REQUEST |
| `ollama-404-model` | Ollama (OpenAI compatibility, http://localhost:11434/v1) | from docs | error INVALID_REQUEST |
| `ollama-500-oom` | Ollama (OpenAI compatibility, http://localhost:11434/v1) | from docs | error SERVER |
| `ollama-reasoning-tools-stop` | Ollama (OpenAI compatibility, http://localhost:11434/v1) | from docs | reasoning, 1 tool call(s), echo |
| `openai-400-context` | OpenAI (api.openai.com/v1) | from docs | error CONTEXT_WINDOW_EXCEEDED |
| `openai-401` | OpenAI (api.openai.com/v1) | from docs | error AUTH |
| `openai-403-forbidden` | generic OpenAI-compatible gateway | from docs (synthetic) | error AUTH |
| `openai-403-project-model-access` | OpenAI (api.openai.com/v1) | from docs | error MODEL_NOT_AVAILABLE |
| `openai-404-model` | OpenAI (api.openai.com/v1) | from docs | error INVALID_REQUEST |
| `openai-429-insufficient-quota` | OpenAI (api.openai.com/v1) | from docs | error QUOTA |
| `openai-429-rate-limit` | OpenAI (api.openai.com/v1) | from docs | error RATE_LIMIT |
| `openai-500` | OpenAI (api.openai.com/v1) | from docs | error SERVER |
| `openai-include-usage-parallel-tools` | OpenAI (api.openai.com/v1) | from docs | 2 tool call(s) |
| `openai-length` | OpenAI (api.openai.com/v1) | from docs | text |
| `openai-text-include-usage` | OpenAI (api.openai.com/v1) | from docs | text |
| `openrouter-401` | OpenRouter (openrouter.ai/api/v1) | from docs | error AUTH |
| `openrouter-402` | OpenRouter (openrouter.ai/api/v1) | from docs | error QUOTA |
| `openrouter-404-no-tool-endpoints` | OpenRouter (openrouter.ai/api/v1) | from docs | error INVALID_REQUEST |
| `openrouter-429-upstream` | OpenRouter (openrouter.ai/api/v1) | from docs | error RATE_LIMIT |
| `openrouter-midstream-error` | OpenRouter (openrouter.ai/api/v1) | from docs | error SERVER |
| `openrouter-reasoning-details-tools` | OpenRouter (openrouter.ai/api/v1) | from docs | reasoning, 1 tool call(s), echo |
| `openrouter-reasoning-text` | OpenRouter (openrouter.ai/api/v1) | from docs | reasoning, text |
| `quirk-index-reused-parallel` | generic OpenAI-compatible gateway quirk | from docs | 2 tool call(s) |
| `quirk-name-every-frame` | generic OpenAI-compatible gateway quirk | from docs | 1 tool call(s) |
| `quirk-no-tool-call-id` | generic OpenAI-compatible gateway quirk | from docs | 1 tool call(s) |
| `quirk-unknown-finish-reason` | generic OpenAI-compatible gateway quirk | from docs | text |
| `qwen-400-arrearage` | Alibaba DashScope compatible-mode (dashscope.aliyuncs.com/compatible-mode/v1) | from docs | error QUOTA |
| `qwen-400-model-not-activated` | Alibaba DashScope compatible-mode (dashscope.aliyuncs.com/compatible-mode/v1) | from docs (synthetic) | error MODEL_NOT_AVAILABLE |
| `qwen-403-model-access-denied` | Alibaba DashScope compatible-mode (dashscope.aliyuncs.com/compatible-mode/v1) | from docs | error MODEL_NOT_AVAILABLE |
| `qwen-thinking-tools` | Alibaba DashScope compatible-mode (dashscope.aliyuncs.com/compatible-mode/v1) | from docs | reasoning, 1 tool call(s), echo |
| `sglang-400` | SGLang (OpenAI-compatible server) | from docs | error INVALID_REQUEST |
| `sglang-reasoning-tools` | SGLang (OpenAI-compatible server) | from docs | reasoning, 1 tool call(s), echo |
| `siliconflow-400-model` | SiliconFlow (api.siliconflow.cn/v1) | from docs | error INVALID_REQUEST |
| `siliconflow-401` | SiliconFlow (api.siliconflow.cn/v1) | from docs | error AUTH |
| `siliconflow-403-balance` | SiliconFlow (api.siliconflow.cn/v1) | from docs | error QUOTA |
| `siliconflow-403-model-not-available` | SiliconFlow (api.siliconflow.cn/v1) | from docs | error MODEL_NOT_AVAILABLE |
| `siliconflow-429` | SiliconFlow (api.siliconflow.cn/v1) | from docs | error RATE_LIMIT |
| `siliconflow-503` | SiliconFlow (api.siliconflow.cn/v1) | from docs | error SERVER |
| `siliconflow-reasoning-tools` | SiliconFlow (api.siliconflow.cn/v1) | from docs | reasoning, 1 tool call(s), echo |
| `vllm-400-context` | vLLM (OpenAI-compatible server) | from docs | error CONTEXT_WINDOW_EXCEEDED |
| `vllm-400-tools-unsupported` | vLLM (OpenAI-compatible server) | from docs | error INVALID_REQUEST |
| `vllm-404-model` | vLLM (OpenAI-compatible server) | from docs | error INVALID_REQUEST |
| `vllm-midstream-error` | vLLM (OpenAI-compatible server) | from docs | error SERVER |
| `vllm-reasoning-content-tools` | vLLM (OpenAI-compatible server) | from docs | reasoning, 1 tool call(s), echo |
| `vllm-reasoning-field-tools` | vLLM (OpenAI-compatible server) | from docs | reasoning, 1 tool call(s), echo |
