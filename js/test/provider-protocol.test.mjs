// Patch 16 (llm-service-lite: `"usage": null` frames, parse errors are not network errors, 404 names
// itself) end to end: the real OwcProfilesAdapter against synthetic node:http SSE servers. Runs
// against a built stage tree:
//   DSH_STAGE_NM=<WORK_DIR>/stage/node_modules node --test js/test/provider-protocol.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { createServer as createNetServer } from 'node:net'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const AGENT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const NM = resolve(process.env.DSH_STAGE_NM ?? join(AGENT, 'toolchains/out/engine-build/stage/node_modules'))
const lite = await import(pathToFileURL(join(NM, '@snnh/dsh-llm-service-lite/lib/index.js')).href)
const { OwcProfilesAdapter, ConcurrencyLimiter, resolveProfiles, classifyTransport, notFoundMessage } = lite

/** An HTTP server answering every request with `handler`; counts requests. */
async function serve(handler) {
  const server = createServer((req, res) => { server.hits += 1; req.resume(); req.on('end', () => handler(req, res)) })
  server.hits = 0
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  return { server, origin: `http://127.0.0.1:${server.address().port}`, close: () => new Promise(r => server.close(r)) }
}

/** Streams `frames` (objects → JSON, strings verbatim) as SSE, then `[DONE]`. */
const sse = frames => (_req, res) => {
  res.writeHead(200, { 'content-type': 'text/event-stream' })
  for (const f of frames) res.write(`data: ${typeof f === 'string' ? f : JSON.stringify(f)}\n\n`)
  res.end('data: [DONE]\n\n')
}

function adapterFor(baseURL) {
  const profiles = resolveProfiles({ t: { interfaceType: 'openai-chat-completions', baseURL, apiKey: 'sk-test', includeUsage: true, models: [{ id: 'm' }] } })
  const limiter = new ConcurrencyLimiter(1)
  return { profile: profiles.get('t'), adapter: new OwcProfilesAdapter({ profiles: () => profiles, limiter: () => limiter, resolveApiKey: async p => p.apiKey }) }
}

/** Runs one turn; resolves `{ chunks }` or `{ error, chunks }`. */
async function turn(baseURL) {
  const { adapter } = adapterFor(baseURL)
  const chunks = []
  try {
    for await (const c of adapter.stream({ provider: 't', model: 'm', messages: [{ role: 'user', content: [{ type: 'text', text: 'hi' }] }] })) chunks.push(c)
    return { chunks }
  } catch (error) { return { error, chunks } }
}

test('#20/#28: usage:null on every content frame, real usage on the last; error:null and delta:null tolerated', async () => {
  const s = await serve(sse([
    { choices: [{ index: 0, delta: { role: 'assistant', content: '' }, finish_reason: null }], usage: null },
    { choices: [{ index: 0, delta: { content: 'O' }, finish_reason: null }], usage: null, error: null },
    { choices: [{ index: 0, delta: { content: 'K' }, finish_reason: null }], usage: null },
    { choices: [{ index: 0, delta: null, finish_reason: 'stop' }], usage: null },
    { choices: [], usage: { prompt_tokens: 12, completion_tokens: 2, prompt_tokens_details: null } },
  ]))
  try {
    const { error, chunks } = await turn(`${s.origin}/v1`)
    assert.equal(error, undefined, error?.stack)
    assert.equal(chunks.filter(c => c.type === 'text-delta').map(c => c.text).join(''), 'OK')
    const usage = chunks.find(c => c.type === 'usage')?.usage
    assert.deepEqual(usage, { inputTokens: 12, outputTokens: 2, totalTokens: 14, cacheReadTokens: 0, cacheWriteTokens: 0 })
    assert.deepEqual(chunks.at(-1), { type: 'finish', reason: { kind: 'stop' } })
  } finally { await s.close() }
})

test('usage omitted entirely or non-object: no usage chunk, turn still succeeds', async () => {
  const s = await serve(sse([
    { choices: [{ index: 0, delta: { content: 'hi' } }], usage: 'n/a' },
    { choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] },
  ]))
  try {
    const { error, chunks } = await turn(`${s.origin}/v1`)
    assert.equal(error, undefined, error?.stack)
    assert.equal(chunks.some(c => c.type === 'usage'), false)
    assert.equal(chunks.at(-1).type, 'finish')
  } finally { await s.close() }
})

test('malformed JSON frame: MALFORMED_RESPONSE, one request, not retryable, fast', async () => {
  const s = await serve(sse([{ choices: [{ index: 0, delta: { content: 'x' } }] }, '{"choices": [ nope']))
  try {
    const started = Date.now()
    const { error } = await turn(`${s.origin}/v1`)
    assert.equal(error?.code, 'MALFORMED_RESPONSE', error?.stack)
    assert.equal(s.server.hits, 1)
    assert.ok(Date.now() - started < 2000)
    const { profile } = adapterFor(`${s.origin}/v1`)
    assert.ok(profile.retryPolicy.retryableCodes.includes('TRANSPORT'))
    assert.ok(!profile.retryPolicy.retryableCodes.includes('MALFORMED_RESPONSE'))
  } finally { await s.close() }
})

test('a TypeError thrown while translating is a parse error, not TRANSPORT', () => {
  const e = classifyTransport(new TypeError("Cannot read properties of null (reading 'prompt_tokens')"), false, 'https://integrate.api.nvidia.com/v1')
  assert.equal(e.code, 'MALFORMED_RESPONSE')
  assert.equal(e.message, "llm-service-lite: could not parse the provider's response: TypeError: Cannot read properties of null (reading 'prompt_tokens')")
  assert.equal(classifyTransport(new RangeError('Invalid array length')).code, 'MALFORMED_RESPONSE')
  // undici fetch failures stay TRANSPORT whatever their cause (the app's offline wait keys on it) ...
  assert.equal(classifyTransport(new TypeError('fetch failed')).code, 'TRANSPORT')
  assert.equal(classifyTransport(new TypeError('fetch failed', { cause: Object.assign(new Error('getaddrinfo ENOTFOUND x'), { code: 'ENOTFOUND' }) })).code, 'TRANSPORT')
  // ... and so does a body-read TypeError over a coded socket error.
  const terminated = new TypeError('terminated', { cause: Object.assign(new Error('other side closed'), { code: 'UND_ERR_SOCKET' }) })
  assert.equal(classifyTransport(terminated, false, 'https://h/v1').message, 'llm-service-lite: provider transport failed: UND_ERR_SOCKET h:443 (socket closed)')
  // A coded non-network TypeError (bad URL) is a request failure, not a parse error.
  assert.equal(classifyTransport(Object.assign(new TypeError('Invalid URL'), { code: 'ERR_INVALID_URL' })).code, 'UNKNOWN')
})

test('#28: a transport failure without cause still names the error, never a bare host:port', () => {
  const e = classifyTransport(new TypeError('fetch failed'), false, 'https://integrate.api.nvidia.com/v1')
  assert.equal(e.message, 'llm-service-lite: provider transport failed: integrate.api.nvidia.com:443 (TypeError: fetch failed)')
})

test('#28: 404 on a bare-origin base URL says HTTP 404 and suggests /v1; not retryable', async () => {
  const s = await serve((_req, res) => { res.writeHead(404, { 'content-type': 'text/plain' }); res.end() })
  try {
    const { error } = await turn(s.origin)
    assert.equal(error?.code, 'INVALID_REQUEST', error?.stack)
    assert.equal(error.message, `llm-service-lite: HTTP 404 Not Found from ${s.origin}; the base URL has no path, and OpenAI-compatible APIs usually live under /v1: try ${s.origin}/v1`)
    assert.equal(s.server.hits, 1)
  } finally { await s.close() }
  assert.equal(notFoundMessage('404 page not found', 'https://gw.example/openai/v1?key=sk-x'), 'llm-service-lite: HTTP 404 Not Found from https://gw.example: 404 page not found; check the base URL and the model id')
})

test('refused port end to end: still TRANSPORT with the errno', async () => {
  const srv = createNetServer(); await new Promise(r => srv.listen(0, '127.0.0.1', r))
  const { port } = srv.address(); await new Promise(r => srv.close(r))
  const { error } = await turn(`http://127.0.0.1:${port}/v1`)
  assert.equal(error?.code, 'TRANSPORT')
  assert.equal(error.message, `llm-service-lite: provider transport failed: ECONNREFUSED 127.0.0.1:${port} (connection refused)`)
})
