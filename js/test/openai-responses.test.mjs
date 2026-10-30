// Built-engine regression for OpenAI Responses (patch 17); no live API calls.
// node --test js/test/openai-responses.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const NM = resolve(process.env.DSH_STAGE_NM ?? join(ROOT, 'toolchains/out/engine-build/stage/node_modules'))
const { OwcProfilesAdapter, resolveProfiles } = await import(pathToFileURL(join(NM, '@snnh/dsh-llm-service-lite/lib/index.js')).href)
const { BlockAssembler } = await import(pathToFileURL(join(NM, '@deepseek-ai/dsh-llm/lib/index.js')).href)
const model = 'gpt-6.1-terra'
const user = { role: 'user', content: [{ type: 'text', text: 'Check the device' }] }
const tool = { name: 'device_status', description: 'Read device status', parameters: {
  type: 'object', properties: { verbose: { type: 'boolean' } },
} }
const message = (id, text, phase = 'final_answer') => ({
  id, type: 'message', role: 'assistant', phase,
  content: [{ type: 'output_text', text, annotations: [] }],
})
const event = value => `data: ${JSON.stringify(value)}\n\n`
function response(output) {
  return new Response(event({ type: 'response.completed', response: {
    id: 'resp_test', status: 'completed', output,
    usage: { input_tokens: 20, output_tokens: 10, input_tokens_details: { cached_tokens: 5 } },
  } }), { headers: { 'content-type': 'text/event-stream' } })
}
function setup(t, replies, overrides = {}) {
  // Same profile shape emitted by Providers.kt for the official host.
  const profiles = resolveProfiles({ official: {
    interfaceType: 'openai-responses', baseURL: 'https://api.openai.com/v1',
    extraBody: { store: false }, models: [{
      id: model, contextWindow: 131072, defaults: { maxTokens: 16384 },
      capabilities: { responsesEncryptedReplay: true },
    }], ...overrides,
  } })
  const adapter = new OwcProfilesAdapter({ resolveApiKey: async () => 'test-key' })
  const sent = []
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    sent.push({ url, body: JSON.parse(init.body) })
    assert.ok(replies.length, 'no unexpected extra request')
    return replies.shift()
  })
  async function call(messages = [user]) {
    const chunks = []
    const options = { provider: 'official', model, maxTokens: 16384, system: 'Be helpful.', messages, tools: [tool] }
    for await (const chunk of adapter.request(options, profiles.get('official'), new AbortController().signal, () => {})) chunks.push(chunk)
    return chunks
  }
  return { sent, call }
}
function assertRequest({ url, body }) {
  assert.equal(url, 'https://api.openai.com/v1/responses')
  assert.equal(body.model, model)
  assert.equal(body.stream, true)
  assert.equal(body.store, false)
  assert.equal(body.max_output_tokens, 16384)
  assert.deepEqual(body.reasoning, { effort: 'medium', summary: 'auto' })
  assert.deepEqual(body.include, ['reasoning.encrypted_content'])
  assert.deepEqual(body.tools, [{ type: 'function', ...tool, strict: false }])
  for (const key of ['messages', 'max_tokens', 'max_completion_tokens', 'reasoning_effort', 'stream_options']) {
    assert.ok(!Object.hasOwn(body, key), `no Chat Completions field ${key}`)
  }
}

test('Terra uses Responses with reasoning, tools, and stateless encrypted replay', async t => {
  const { call, sent } = setup(t, [response([message('msg_1', 'Hello')])])
  const chunks = await call()
  assertRequest(sent[0])
  assert.equal(sent[0].body.instructions, 'Be helpful.')
  assert.deepEqual(sent[0].body.input, [{ type: 'message', role: 'user', content: [{ type: 'input_text', text: 'Check the device' }] }])
  assert.deepEqual(chunks.find(c => c.type === 'block-end').block, { type: 'text', text: 'Hello' })
  assert.equal(chunks.at(-1).reason.kind, 'stop')
  assert.equal(chunks.find(c => c.type === 'usage').usage.cacheReadTokens, 5)
})

for (const summary of [[], [{ type: 'summary_text', text: 'Checking the device.' }]]) {
  test(`tool round trip preserves encrypted reasoning (${summary.length ? 'visible summary' : 'no summary'}) and assistant phase`, async t => {
    const reasoning = { id: 'rs_1', type: 'reasoning', summary, encrypted_content: 'opaque-test-payload' }
    const preamble = message('msg_preamble', 'I will check.', 'commentary')
    const functionCall = { id: 'fc_1', type: 'function_call', call_id: 'call_1', name: tool.name, arguments: '{}' }
    const { call, sent } = setup(t, [response([reasoning, preamble, functionCall]), response([message('msg_final', 'Device is online.')])])
    const first = await call()
    assert.equal(first.at(-1).reason.kind, 'tool-calls')
    const assembler = new BlockAssembler()
    for (const chunk of first) assembler.push(chunk)
    const { blocks: content, replay } = assembler.assembled()
    assert.ok(replay, 'the harness retains replay metadata, including empty reasoning blocks')
    const second = await call([user, {
      role: 'assistant', content, source: { provider: 'official', model, replayState: replay },
    }, { role: 'tool', toolCallId: 'call_1', content: [{ type: 'text', text: 'online' }] }])
    assertRequest(sent[1])
    assert.deepEqual(sent[1].body.input.slice(1), [reasoning, preamble, functionCall, { type: 'function_call_output', call_id: 'call_1', output: 'online' }])
    assert.equal(second.at(-1).reason.kind, 'stop')
    assert.equal(second.find(c => c.type === 'block-end').block.text, 'Device is online.')
  })
}

test('SSE function argument deltas produce executable tool calls', async t => {
  const item = { id: 'fc_delta', type: 'function_call', call_id: 'call_delta', name: tool.name, arguments: '' }
  const events = [
    { type: 'response.created', response: { id: 'resp_stream' } },
    { type: 'response.output_item.added', output_index: 0, item },
    { type: 'response.function_call_arguments.delta', item_id: item.id, delta: '{"verbose":' },
    { type: 'response.function_call_arguments.delta', item_id: item.id, delta: 'true}' },
    { type: 'response.output_item.done', output_index: 0, item: { ...item, arguments: '{"verbose":true}' } },
    { type: 'response.completed', response: { id: 'resp_stream', status: 'completed', output: [{ ...item, arguments: '{"verbose":true}' }] } },
  ]
  const { call } = setup(t, [new Response(events.map(event).join(''))])
  const chunks = await call()
  assert.deepEqual(chunks.find(c => c.type === 'block-end').block, {
    type: 'tool-call', id: 'call_delta', name: tool.name, arguments: '{"verbose":true}',
  })
  assert.equal(chunks.at(-1).reason.kind, 'tool-calls')
})

test('SSE text deltas assemble a final answer with its phase', async t => {
  const item = message('msg_stream', 'Hello there')
  const events = [
    { type: 'response.output_item.added', output_index: 0, item: { ...item, content: [] } },
    { type: 'response.output_text.delta', output_index: 0, item_id: item.id, delta: 'Hello ' },
    { type: 'response.output_text.delta', output_index: 0, item_id: item.id, delta: 'there' },
    { type: 'response.output_item.done', output_index: 0, item },
    { type: 'response.completed', response: { status: 'completed', output: [item] } },
  ]
  const { call } = setup(t, [new Response(events.map(event).join(''))])
  const chunks = await call()
  assert.equal(chunks.filter(c => c.type === 'text-delta').map(c => c.text).join(''), 'Hello there')
  assert.equal(chunks.at(-1).reason.kind, 'stop')
  assert.equal(chunks.at(-1).replayState.blocks[0].phase, 'final_answer')
})

test('Chat Completions gateways still use legacy parameters and tool definitions', async t => {
  const wire = { choices: [{ index: 0, delta: { content: 'OK' }, finish_reason: 'stop' }] }
  const { call, sent } = setup(t, [new Response(event(wire) + 'data: [DONE]\n\n')], {
    interfaceType: 'openai-chat-completions', baseURL: 'https://gw.example/v1', extraBody: {}, includeUsage: true,
  })
  await call()
  assert.equal(sent[0].url, 'https://gw.example/v1/chat/completions')
  assert.equal(sent[0].body.max_tokens, 16384)
  assert.deepEqual(sent[0].body.tools, [{ type: 'function', function: tool }])
  assert.deepEqual(sent[0].body.stream_options, { include_usage: true })
})
