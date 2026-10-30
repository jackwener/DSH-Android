// node --test js/test/mode.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { RECORD_TYPE, apply, categoryOf, excludes, modeSection, normalizeMode, recordedModeId, restricts, withoutHiddenToolSections } from '../packages/plugin-mode/index.js'

const DEVICE = {
  file_list: { name: 'file_list', group: 'files' },
  screen_tap: { name: 'screen_tap', group: 'screen' },
  alarm_set: { name: 'alarm_set', group: 'system' },
}
const BUILTIN = ['bash', 'job_list', 'read', 'glob', 'grep', 'write', 'edit', 'web_search', 'web_fetch', 'subagent', 'skill', 'ask_user_question', 'todo_write']

const CHAT = { id: 'chat', name: '只聊天', prompt: 'Just talk.', deviceGroups: [], tools: ['web'], skills: false }
const CODE = { id: 'code', name: '编程', prompt: 'You are a coding assistant.', deviceGroups: ['files', 'clipboard'], tools: ['shell', 'files_read', 'files_write', 'web', 'subagent'], skills: true }

test('categories cover the built-in tools a mode can switch', () => {
  assert.equal(categoryOf('bash'), 'shell')
  assert.equal(categoryOf('job_output'), 'shell')
  assert.equal(categoryOf('terminal_send'), 'shell')
  assert.equal(categoryOf('read'), 'files_read')
  assert.equal(categoryOf('grep'), 'files_read')
  assert.equal(categoryOf('edit'), 'files_write')
  assert.equal(categoryOf('web_fetch'), 'web')
  assert.equal(categoryOf('subagent'), 'subagent')
  assert.equal(categoryOf('subagent_fork'), 'subagent')
  assert.equal(categoryOf('ask_user_question'), undefined)
  assert.equal(categoryOf('file_list'), undefined)
})

test('mode payloads are validated and normalized', () => {
  const m = normalizeMode({ ...CODE, tools: ['shell', 'future_category'] })
  assert.deepEqual([...m.tools], ['shell'])
  assert.deepEqual([...m.deviceGroups], ['files', 'clipboard'])
  assert.equal(m.skills, true)
  const d = normalizeMode({ id: 'default' })
  assert.equal(d.deviceGroups, null)
  assert.equal(d.tools, null)
  assert.equal(restricts(d), false)
  assert.equal(modeSection(d), '')
  assert.throws(() => normalizeMode({ id: '' }), TypeError)
  assert.throws(() => normalizeMode({ id: 'x', tools: 'shell' }), TypeError)
  assert.throws(() => normalizeMode({ id: 'x', skills: 'no' }), TypeError)
  assert.throws(() => normalizeMode([]), TypeError)
})

test('excludes() applies device groups, categories and the skills switch', () => {
  const chat = normalizeMode(CHAT)
  const of = n => DEVICE[n]
  for (const n of ['bash', 'read', 'write', 'subagent', 'skill', 'file_list', 'screen_tap']) assert.ok(excludes(chat, n, of), n)
  for (const n of ['web_search', 'web_fetch', 'ask_user_question', 'todo_write']) assert.ok(!excludes(chat, n, of), n)
  const code = normalizeMode(CODE)
  for (const n of ['screen_tap', 'alarm_set']) assert.ok(excludes(code, n, of), n)
  for (const n of ['bash', 'write', 'skill', 'file_list', 'web_search']) assert.ok(!excludes(code, n, of), n)
  assert.ok(!excludes(undefined, 'bash', of))
})

test('the mode section carries the prompt and the limitation note', () => {
  const text = modeSection(normalizeMode(CHAT))
  assert.ok(text.startsWith('# Conversation mode: 只聊天'))
  assert.ok(text.includes('Just talk.'))
  assert.ok(text.includes('not available here'))
  const promptOnly = modeSection(normalizeMode({ id: 'p', name: 'P', prompt: 'Be brief.' }))
  assert.ok(promptOnly.includes('Be brief.') && !promptOnly.includes('not available here'))
})

/** A minimal Cordis-like context with the parts plugin-mode touches. */
function harness() {
  const handlers = new Map()
  const guards = []
  const agentsList = []
  const records = []
  const on = (event, fn) => { handlers.set(event, [...handlers.get(event) ?? [], fn]); return () => {} }
  const globalNames = () => [...BUILTIN, ...Object.keys(DEVICE)]
  const ctx = {
    on,
    get(name) {
      if (name === 'agents') return { list: () => agentsList }
      if (name === 'androidBridge') return { descriptorOf: n => DEVICE[n] }
      return undefined
    },
    tools: {
      view: () => ({ restrictableNames: new Set(globalNames()) }),
      schemas: () => globalNames().map(name => ({ name })),
      guard: g => { guards.push(g); return () => {} },
    },
  }
  const makeAgent = (id, parent) => {
    const scope = { restrictions: [], sections: [] }
    const log = []
    const agent = {
      id,
      session: {
        id,
        header: parent === undefined ? {} : { parentSession: parent },
        get seq() { return log.length },
        eventAt: i => log[i],
        log,
      },
      scope,
      ctx: {
        tools: { restrict: f => { const entry = { deny: [...f.deny] }; scope.restrictions.push(entry); return () => { scope.restrictions.splice(scope.restrictions.indexOf(entry), 1) } } },
        systemPrompt: { section: s => { scope.sections.push(s); return () => { scope.sections.splice(scope.sections.indexOf(s), 1) } } },
      },
    }
    agentsList.push(agent)
    return agent
  }
  const appendRecord = (session, type, data) => { records.push({ session: session.id, type, data }); session.log.push({ type, ignorable: true, data }) }
  apply(ctx, { appendRecord })
  const method = name => handlers.get('sdk-extensions/resolve').map(h => h(name)).find(Boolean)
  const emit = (event, ...args) => { for (const h of handlers.get(event) ?? []) h(...args) }
  const guard = exec => guards.map(g => g(exec)).find(r => r !== undefined)
  return { method, emit, guard, makeAgent, records }
}

test('a mode sent before the agent exists applies when it is created; subagents follow the root', async () => {
  const h = harness()
  await h.method('android/session/mode')({ sessionId: 's1', mode: CHAT })
  const root = h.makeAgent('s1')
  h.emit('agent/created', { agent: root })
  assert.equal(root.scope.restrictions.length, 1)
  const denied = root.scope.restrictions[0].deny
  for (const n of ['bash', 'read', 'write', 'skill', 'subagent', 'file_list', 'screen_tap', 'alarm_set']) assert.ok(denied.includes(n), n)
  for (const n of ['web_search', 'web_fetch', 'ask_user_question']) assert.ok(!denied.includes(n), n)
  assert.equal(root.scope.sections.length, 1)
  assert.equal(root.scope.sections[0].interpolate, false)
  assert.ok(root.scope.sections[0].text.includes('只聊天'))
  await new Promise(r => setImmediate(r))
  assert.deepEqual(h.records, [{ session: 's1', type: RECORD_TYPE, data: { id: 'chat', name: '只聊天' } }])

  const child = h.makeAgent('s1-child', 's1')
  h.emit('agent/created', { agent: child })
  assert.deepEqual(child.scope.restrictions[0].deny, denied)
  assert.equal(h.records.length, 1, 'subagents do not record')

  // Backstop guard for the root and the subagent; other conversations are untouched.
  assert.match(h.guard({ name: 'bash', agent: child }), /not available in this conversation's mode \(只聊天\)/)
  assert.equal(h.guard({ name: 'web_fetch', agent: root }), undefined)
  const other = h.makeAgent('s2')
  assert.equal(h.guard({ name: 'bash', agent: other }), undefined)
})

test('a resumed agent gets the mode when the app sends it; re-sending is idempotent; null clears', async () => {
  const h = harness()
  const agent = h.makeAgent('r1')
  h.emit('agent/created', { agent })
  assert.equal(agent.scope.restrictions.length, 0)
  await h.method('android/session/mode')({ sessionId: 'r1', mode: CODE })
  assert.equal(agent.scope.restrictions.length, 1)
  assert.deepEqual(agent.scope.restrictions[0].deny, ['alarm_set', 'screen_tap'])
  await h.method('android/session/mode')({ sessionId: 'r1', mode: CODE })
  assert.equal(agent.scope.restrictions.length, 1)
  assert.equal(agent.scope.sections.length, 1)
  await new Promise(r => setImmediate(r))
  assert.equal(h.records.length, 1, 'the same mode is recorded once')
  await h.method('android/session/mode')({ sessionId: 'r1', mode: null })
  assert.equal(agent.scope.restrictions.length, 0)
  assert.equal(agent.scope.sections.length, 0)
  assert.equal(h.guard({ name: 'screen_tap', agent }), undefined)
})

test('the default mode restricts nothing and is not recorded in a fresh log', async () => {
  const h = harness()
  await h.method('android/session/mode')({ sessionId: 'd1', mode: { id: 'default', name: '默认' } })
  const agent = h.makeAgent('d1')
  h.emit('agent/created', { agent })
  assert.equal(agent.scope.restrictions.length, 0)
  assert.equal(agent.scope.sections.length, 0)
  await new Promise(r => setImmediate(r))
  assert.equal(h.records.length, 0, 'default is recorded only as a change')
})

test('recordedModeId reads the newest selection record', () => {
  const log = [{ type: RECORD_TYPE, ignorable: true, data: { id: 'a' } }, { type: 'turn/start' }, { type: RECORD_TYPE, ignorable: true, data: { id: 'b' } }]
  assert.equal(recordedModeId({ seq: log.length, eventAt: i => log[i] }), 'b')
  assert.equal(recordedModeId({ seq: 0, eventAt: () => undefined }), undefined)
})

test('bad requests are rejected', async () => {
  const h = harness()
  assert.throws(() => h.method('android/session/mode')({ mode: CHAT }), TypeError)
  assert.throws(() => h.method('android/session/mode')({ sessionId: 'x', mode: { id: 3 } }), TypeError)
})

test('guidance sections of hidden tools are dropped', () => {
  const sections = ['harness:identity', 'tool:bash', 'tool:jobs', 'tool:web_fetch', 'tool:subagent', 'tool:subagent:depth', 'android:mode'].map(name => ({ name, text: name }))
  const kept = withoutHiddenToolSections(sections, normalizeMode(CHAT)).map(s => s.name)
  assert.deepEqual(kept, ['harness:identity', 'tool:web_fetch', 'android:mode'])
  assert.equal(withoutHiddenToolSections(sections, normalizeMode({ id: 'default' })), sections)
  assert.equal(withoutHiddenToolSections(sections, undefined), sections)
})
