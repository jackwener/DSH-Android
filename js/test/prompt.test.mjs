// node --test js/test/prompt.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { systemPromptSection } from '../packages/plugin-guard/index.js'

const screen = { name: 'screen_read', group: 'screen', risk: 'read', needsUnlock: true }
const alarm = { name: 'alarm_set', group: 'system', risk: 'write', needsUnlock: false }

test('screen operation section only when screen tools are registered', () => {
  const withScreen = systemPromptSection('zh-CN', [screen, alarm])
  for (const s of ['background screen', 'screen: "main"', 'hand_to_user(reason, kind)', 'No change', 'loop-guard', 'pacing', 'app_guide(app)'])
    assert.ok(withScreen.includes(s), s)
  assert.ok(!withScreen.includes('vscreen_start') && !withScreen.includes('displayId'))
  assert.ok(!systemPromptSection('zh-CN', [alarm]).includes('Operating apps on screen'))
})
