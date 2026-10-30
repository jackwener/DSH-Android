import test from 'node:test'
import assert from 'node:assert/strict'
import { withOpenCodeSessionHeader } from '../packages/llm-service-lite/lib/opencode-session.js'

const url = 'https://opencode.ai/zen/go/v1/chat/completions'

test('OpenCode uses the conversation id across turns and keeps concurrent sessions separate', async () => {
  const headers = Object.freeze({ authorization: 'Bearer test-credential', accept: 'text/event-stream' })
  const ids = ['session-one', 'session-two', 'session-one']
  const results = await Promise.all(ids.map(async id => withOpenCodeSessionHeader(url, headers, id)))
  assert.deepEqual(results.map(h => h['x-opencode-session']), ids)
  assert.ok(results.every(h => h.authorization === headers.authorization))
  assert.equal(headers['x-opencode-session'], undefined)
})

test('all supported OpenCode protocols and custom route names are covered by the host', () => {
  for (const target of [url, 'https://opencode.ai/inference/go/openai/v1/responses', 'https://opencode.ai/zen/go/v1/messages', 'https://API.OPENCODE.AI/v1/chat/completions']) {
    assert.equal(withOpenCodeSessionHeader(target, {}, 'session-one')['x-opencode-session'], 'session-one')
  }
})

test('an explicit session header is respected case-insensitively', () => {
  const headers = { 'X-OpenCode-Session': 'configured-id' }
  assert.equal(withOpenCodeSessionHeader(url, headers, 'session-one'), headers)
})

test('non-OpenCode URLs keep their headers unchanged', () => {
  const headers = Object.freeze({ authorization: 'Bearer test-credential' })
  for (const target of ['https://api.deepseek.com/chat/completions', 'http://127.0.0.1:3425/v1/chat/completions', 'https://opencode.ai.evil.test/v1', 'https://opencode.ai@other.test/v1', 'https://other.test/opencode.ai', 'not a URL']) {
    assert.equal(withOpenCodeSessionHeader(target, headers, 'session-one'), headers)
  }
})

test('sessionless or unsafe auxiliary calls get separate header-safe identities', () => {
  const ids = [undefined, '', 'bad\r\nheader', '会话'].map(id => withOpenCodeSessionHeader(url, {}, id)['x-opencode-session'])
  assert.equal(new Set(ids).size, ids.length)
  for (const id of ids) assert.match(id, /^[0-9a-f-]{36}$/)
})
