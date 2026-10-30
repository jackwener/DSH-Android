// Run after building the engine; no provider credentials or network are used.
// node js/test/chat-usage.test.mjs [path/to/@snnh/dsh-llm-service-lite/lib/index.js]
import test from 'node:test'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const modulePath = process.argv[2] ?? fileURLToPath(new URL(
  '../../toolchains/out/engine-build/stage/node_modules/@snnh/dsh-llm-service-lite/lib/index.js',
  import.meta.url,
))
const { ConcurrencyLimiter, OwcProfilesAdapter, resolveProfiles } = await import(pathToFileURL(resolve(modulePath)).href)

const choice = (delta, finish_reason = null) => ({ index: 0, delta, finish_reason })
const usage = { prompt_tokens: 12, completion_tokens: 2, prompt_tokens_details: { cached_tokens: 4 } }
const expectedUsage = { inputTokens: 8, outputTokens: 2, totalTokens: 14, cacheReadTokens: 4, cacheWriteTokens: 0 }
const fixtures = [
  {
    name: 'null usage on role, reasoning and text frames preserves the final counts',
    frames: [
      { choices: [choice({ role: 'assistant', content: '' })], usage: null },
      { choices: [choice({ reasoning_content: 'A brief thought.' })], usage: null },
      { choices: [choice({ content: 'OK' })], usage: null },
      { choices: [choice({}, 'stop')], usage: null },
      { choices: [], usage },
    ],
    text: 'OK',
    reasoning: 'A brief thought.',
    finish: 'stop',
    usage: expectedUsage,
  },
  {
    name: 'null usage during fragmented tool calls preserves the call and final counts',
    frames: [
      { choices: [choice({ tool_calls: [{ index: 0, id: 'call_fixture', type: 'function', function: { name: 'echo', arguments: '{"text":' } }] })], usage: null },
      { choices: [choice({ tool_calls: [{ index: 0, function: { arguments: '"OK"}' } }] })], usage: null },
      { choices: [choice({}, 'tool_calls')], usage },
    ],
    text: '',
    reasoning: '',
    finish: 'tool-calls',
    usage: expectedUsage,
    tool: { type: 'tool-call', id: 'call_fixture', name: 'echo', arguments: '{"text":"OK"}' },
  },
  {
    name: 'omitted usage remains optional',
    frames: [{ choices: [choice({ content: 'OK' })] }, { choices: [choice({}, 'stop')] }],
    text: 'OK',
    reasoning: '',
    finish: 'stop',
  },
  {
    name: 'zero token counts remain a valid usage report',
    frames: [
      { choices: [choice({ content: 'OK' })] },
      { choices: [choice({}, 'stop')], usage: { prompt_tokens: 0, completion_tokens: 0 } },
    ],
    text: 'OK',
    reasoning: '',
    finish: 'stop',
    usage: { inputTokens: 0, outputTokens: 0, totalTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 },
  },
]

for (const fixture of fixtures) {
  test(fixture.name, { timeout: 5000 }, async t => {
    const profiles = resolveProfiles({
      fixture: {
        interfaceType: 'openai-chat-completions',
        baseURL: 'https://provider.example/v1',
        includeUsage: true,
        models: [{ id: 'fixture-chat' }],
      },
    })
    const limiter = new ConcurrencyLimiter(1)
    const adapter = new OwcProfilesAdapter({
      profiles: () => profiles,
      limiter: () => limiter,
      resolveApiKey: async () => undefined,
    })
    const fetch = t.mock.method(globalThis, 'fetch', async (url, options) => {
      assert.equal(url, 'https://provider.example/v1/chat/completions')
      assert.deepEqual(JSON.parse(options.body).stream_options, { include_usage: true })
      const body = fixture.frames.map(frame => `data: ${JSON.stringify(frame)}\n\n`).join('') + 'data: [DONE]\n\n'
      return new Response(body, { headers: { 'content-type': 'text/event-stream' } })
    })
    const chunks = []
    for await (const chunk of adapter.stream({
      provider: 'fixture',
      model: 'fixture-chat',
      messages: [{ role: 'user', content: [{ type: 'text', text: 'Reply OK.' }] }],
    })) chunks.push(chunk)

    assert.equal(fetch.mock.callCount(), 1)
    assert.equal(chunks.filter(c => c.type === 'text-delta').map(c => c.text).join(''), fixture.text)
    assert.equal(chunks.filter(c => c.type === 'reasoning-delta').map(c => c.text).join(''), fixture.reasoning)
    assert.deepEqual(chunks.filter(c => c.type === 'finish').map(c => c.reason), [{ kind: fixture.finish }])
    assert.deepEqual(chunks.filter(c => c.type === 'usage').map(c => c.usage), fixture.usage ? [fixture.usage] : [])
    if (fixture.tool) {
      assert.deepEqual(chunks.filter(c => c.type === 'block-end' && c.block.type === 'tool-call').map(c => c.block), [fixture.tool])
    }
  })
}
