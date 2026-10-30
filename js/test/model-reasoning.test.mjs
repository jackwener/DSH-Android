import test from 'node:test'
import assert from 'node:assert/strict'
import { apply, METHOD, reasoningModels } from '../packages/plugin-models/index.js'

test('model metadata comes from the owning adapter, including arbitrary effort ids', async () => {
  const signal = new AbortController().signal
  const calls = []
  const reasoning = { efforts: [{ id: 'medium', name: 'Medium' }, { id: 'adaptive', name: 'Adaptive' }], defaultEffort: 'adaptive' }
  const llm = { async resolveModelInfo(provider, model, actualSignal) {
    calls.push([provider, model]); assert.equal(actualSignal, signal)
    return { provider, id: model, reasoning, privateField: 'must not reach the client' }
  } }
  const result = await reasoningModels(llm, { routes: [{ provider: 'custom', model: 'unknown-name' }] }, signal)
  assert.deepEqual(result, { models: [{ provider: 'custom', id: 'unknown-name', reasoning }] })
  assert.deepEqual(calls, [['custom', 'unknown-name']])
})

test('a plain or unavailable model offers no made-up reasoning levels', async () => {
  const llm = { async resolveModelInfo(_p, model) { if (model === 'broken') throw Error('not registered'); return {} } }
  assert.deepEqual(await reasoningModels(llm, { routes: [{ provider: 'p', model: 'plain' }, { provider: 'p', model: 'broken' }] }), {
    models: [{ provider: 'p', id: 'plain' }, { provider: 'p', id: 'broken' }],
  })
})

test('invalid requests are rejected and cancellation is preserved', async () => {
  for (const params of [null, {}, { routes: 'x' }, { routes: [{ provider: '', model: 'm' }] }, { routes: Array(257).fill({ provider: 'p', model: 'm' }) }])
    await assert.rejects(reasoningModels({}, params), TypeError)
  const controller = new AbortController(); controller.abort()
  const failure = Error('cancelled')
  await assert.rejects(reasoningModels({ resolveModelInfo: async () => { throw failure } }, { routes: [{ provider: 'p', model: 'm' }] }, controller.signal), e => e === failure)
})

test('SDK extension composes with existing account and session-mode methods', async () => {
  let resolver
  const ctx = { llm: { resolveModelInfo: async () => ({}) }, on(event, handler) { assert.equal(event, 'sdk-extensions/resolve'); resolver = handler } }
  apply(ctx)
  assert.equal(resolver('account/state'), undefined)
  assert.equal(resolver('android/session/mode'), undefined)
  assert.deepEqual(await resolver(METHOD)({ routes: [] }), { models: [] })
})
