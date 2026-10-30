// Run after js/build-engine.sh: exercise the actual patched adapter without credentials or native bindings.
//   node --test js/test/host-opencode-session.mjs
// E2E_ENGINE_DIR may point at an extracted engine.zip; otherwise use the build staging tree.
import test from 'node:test'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { reasoningModels } from '../packages/plugin-models/index.js'

const root = fileURLToPath(new URL('../..', import.meta.url))
const engine = resolve(process.env.E2E_ENGINE_DIR ?? `${root}/toolchains/out/engine-build/stage`)
const { OwcProfilesAdapter, ConcurrencyLimiter, resolveProfiles } = await import(pathToFileURL(`${engine}/node_modules/@snnh/dsh-llm-service-lite/lib/index.js`))

function adapter(baseURL, headers = {}) {
  const profiles = resolveProfiles({ custom: { interfaceType: 'openai-chat-completions', baseURL, headers, includeUsage: true, models: [{ id: 'deepseek-v4.1-flash', contextWindow: 131072 }] } })
  const limiter = new ConcurrencyLimiter(3)
  return new OwcProfilesAdapter({ profiles: () => profiles, limiter: () => limiter, resolveApiKey: async () => 'test-credential' })
}

function reply() {
  const events = [
    // OpenAI-compatible streams use null until the final accounting frame arrives.
    { choices: [{ index: 0, delta: { content: 'GO_OK' }, finish_reason: null }], usage: null },
    { choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: null },
    { choices: [], usage: { prompt_tokens: 12, completion_tokens: 3, total_tokens: 15 } },
    { choices: [], usage: null },
  ]
  return new Response(events.map(e => `data: ${JSON.stringify(e)}\n\n`).join('') + 'data: [DONE]\n\n', { headers: { 'content-type': 'text/event-stream' } })
}

async function run(client, sessionId, reasoningEffort) {
  const chunks = []
  for await (const chunk of client.stream({ provider: 'custom', model: 'deepseek-v4.1-flash', sessionId, ...(reasoningEffort ? { reasoningEffort } : {}), messages: [{ role: 'user', content: [{ type: 'text', text: 'Reply GO_OK.' }] }], tools: [] })) chunks.push(chunk)
  assert.equal(chunks.filter(c => c.type === 'text-delta').map(c => c.text).join(''), 'GO_OK')
  assert.equal(chunks.at(-1).type, 'finish')
  const accounting = chunks.filter(c => c.type === 'usage')
  assert.equal(accounting.length, 1)
  assert.equal(accounting[0].usage.inputTokens, 12)
  assert.equal(accounting[0].usage.outputTokens, 3)
}

test('built adapter sends Go session headers on the real fetch path and consumes SSE', async t => {
  const requests = []
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push({ url, headers: new Headers(init.headers), body: JSON.parse(init.body) })
    return reply()
  })
  const client = adapter('https://opencode.ai/zen/go/v1')
  await Promise.all([run(client, 'session-one'), run(client, 'session-two')])
  await run(client, 'session-one')
  assert.deepEqual(requests.map(r => r.headers.get('x-opencode-session')), ['session-one', 'session-two', 'session-one'])
  assert.ok(requests.every(r => r.headers.get('authorization') === 'Bearer test-credential' && r.body.model === 'deepseek-v4.1-flash' && r.body.stream === true))
})

test('built adapter keeps explicit headers and leaves other providers alone', async t => {
  const requests = []
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push(new Headers(init.headers))
    return reply()
  })
  await run(adapter('https://opencode.ai/zen/go/v1', { 'X-OpenCode-Session': 'configured-id' }), 'session-one')
  await run(adapter('https://other.example/v1'), 'session-two')
  assert.equal(requests[0].get('x-opencode-session'), 'configured-id')
  assert.equal(requests[1].has('x-opencode-session'), false)
})

test('Go Flash picker metadata and the actual built request path agree for every selectable effort', async t => {
  const requests = []
  t.mock.method(globalThis, 'fetch', async (_url, init) => {
    requests.push({ headers: new Headers(init.headers), body: JSON.parse(init.body) }); return reply()
  })
  const client = adapter('https://opencode.ai/zen/go/v1')
  const metadata = await reasoningModels({ resolveModelInfo: (...args) => client.resolveModel(...args) }, {
    routes: [{ provider: 'custom', model: 'deepseek-v4.1-flash' }],
  })
  const reasoning = metadata.models[0].reasoning
  assert.deepEqual(reasoning.efforts.map(e => e.id), ['low', 'high', 'max'])
  assert.equal(reasoning.defaultEffort, 'high')
  for (const { id } of reasoning.efforts) await run(client, 'same-conversation', id)
  assert.deepEqual(requests.map(r => r.body.reasoning_effort), ['low', 'high', 'max'])
  assert.ok(requests.every(r => r.body.thinking?.type === 'enabled' && r.headers.get('x-opencode-session') === 'same-conversation'))
})
