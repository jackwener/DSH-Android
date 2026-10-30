// Unit tests of @dsh-android/plugin-extensions' pure parts: the pnpmfile gate, boot bookkeeping
// (crash attribution, safe mode, overlay migration, pnpm settings), install-source normalization
// against a fake GitHub/jsDelivr/registry, archives, MCP classification, error mapping.
//   node --test js/test/extensions-unit.test.mjs        (no engine build needed, except where noted)
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync, existsSync, readdirSync } from 'node:fs'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { deflateRawSync } from 'node:zlib'
import { attributeCrash, packagesOnStack, migrateAppLayer, withWorkspaceKeys, prepareBoot, markBoot, paths, readState, BUILTIN_BUNDLES, LEGACY_APP_HEADER } from '../packages/plugin-extensions/boot.js'
import { readTar, readZip, writeTgz, packageRoot } from '../packages/plugin-extensions/archive.js'
import { parseGithub, githubRepoOf, checkBuilt } from '../packages/plugin-extensions/sources.js'
import { classifyText, npxPackage } from '../packages/plugin-extensions/mcp.js'
import { fromManagerResult, parseGateRefusal, userError } from '../packages/plugin-extensions/errors.js'
import { findBinary } from '../packages/plugin-extensions/scan.js'
import { tarballOf } from './fixtures/fake-registry.mjs'

const require = createRequire(import.meta.url)
const gate = require('../packages/plugin-extensions/pnpmfile.cjs')
const tmp = () => mkdtempSync(join(tmpdir(), 'dsh-ext-unit-'))

test('gate: hard native dependencies are refused with a reason', () => {
  const cases = [
    [{ name: 'a', version: '1.0.0', gypfile: true }, 'native-addon'],
    [{ name: 'b', version: '1.0.0', dependencies: { 'node-gyp-build': '^4' } }, 'native-addon'],
    [{ name: 'c', version: '1.0.0', dependencies: { 'better-sqlite3': '^9' } }, 'native-addon'],
    [{ name: 'd', version: '1.0.0', scripts: { install: 'prebuild-install || node-gyp rebuild' } }, 'native-addon'],
    [{ name: 'e', version: '1.0.0', binary: { module_name: 'x', module_path: './lib' } }, 'native-addon'],
    [{ name: 'f', version: '1.0.0', dependencies: { puppeteer: '^22' } }, 'needs-desktop-browser'],
    [{ name: 'esbuild', version: '0.20.0' }, 'native-addon'],
  ]
  for (const [pkg, code] of cases) {
    assert.throws(() => gate.readPackage(structuredClone(pkg)), e => parseGateRefusal(e.message)?.code === code && parseGateRefusal(e.message).pkg === pkg.name, pkg.name)
  }
})

test('gate: optional native / platform packages are dropped, @deepseek-ai/* and sharp become peers', () => {
  const pkg = gate.readPackage({
    name: 'plugin', version: '1.0.0',
    dependencies: { '@deepseek-ai/dsh-tools': '^0.2.0', '@deepseek-ai/cordis': '*', lodash: '^4', sharp: '^0.33', 'cpu-features': '*' },
    optionalDependencies: { 'cpu-features': '*', '@esbuild/linux-x64': '0.20.0', 'fsevents': '^2' },
  })
  // @deepseek-ai/* become peers when the engine ships them (installation-packages.json, generated
  // by build-engine.sh; absent in the source tree).
  const shipped = existsSync(new URL('../packages/plugin-extensions/installation-packages.json', import.meta.url))
  assert.deepEqual(Object.keys(pkg.dependencies), shipped ? ['lodash'] : ['@deepseek-ai/dsh-tools', '@deepseek-ai/cordis', 'lodash'])
  assert.deepEqual(Object.keys(pkg.optionalDependencies), [])
  assert.equal(pkg.peerDependencies.sharp, '^0.33')
  if (shipped) assert.equal(pkg.peerDependencies['@deepseek-ai/dsh-tools'], '^0.2.0')
  // the profile's own manifest is never touched
  const root = { name: 'dsh-profile-android', dependencies: { 'better-sqlite3': '*' } }
  assert.deepEqual(gate.readPackage(structuredClone(root)), root)
})

test('boot: crash stack → the one third-party bundle owning the package', () => {
  const dir = tmp()
  const nm = join(dir, 'profiles/android/node_modules')
  const pkg = (name, deps = {}) => { mkdirSync(join(nm, name), { recursive: true }); writeFileSync(join(nm, name, 'package.json'), JSON.stringify({ name, version: '1.0.0', dependencies: deps })) }
  pkg('plugin-a', { 'shared-lib': '1', 'only-a': '1' }); pkg('plugin-b', { 'shared-lib': '1' }); pkg('shared-lib'); pkg('only-a')
  const bundles = [...BUILTIN_BUNDLES, 'plugin-a', 'plugin-b']
  const stack = (file) => `Error: boom\n    at Timeout._onTimeout (file://${nm}/${file}:3:9)\n    at listOnTimeout (node:internal/timers:588:17)`
  assert.deepEqual(packagesOnStack(stack('only-a/index.js'), nm), ['only-a'])
  assert.deepEqual(attributeCrash(stack('only-a/index.js'), bundles, nm), { bundle: 'plugin-a', package: 'only-a' })
  assert.deepEqual(attributeCrash(stack('plugin-b/lib/x.js'), bundles, nm), { bundle: 'plugin-b', package: 'plugin-b' })
  assert.equal(attributeCrash(stack('shared-lib/index.js'), bundles, nm), undefined, 'shared by two bundles: not attributable')
  assert.equal(attributeCrash('Error: x\n    at node:internal/foo', bundles, nm), undefined)
})

test('boot: safe mode after two unready boots; crash suspension; journal; overlay migration', () => {
  const dir = tmp()
  const env = { DSH_HOME: join(dir, 'dsh'), DSH_ANDROID_CACHE_DIR: join(dir, 'cache') }
  const p = paths(env)
  mkdirSync(p.profile, { recursive: true })
  const manifest = { name: 'dsh-profile-android', private: true, dependencies: { 'plugin-a': '1.0.0' }, dsh: { profile: { bundles: [...BUILTIN_BUNDLES, 'plugin-a'] } } }
  writeFileSync(join(p.profile, 'package.json'), JSON.stringify(manifest))
  const legacy = `${LEGACY_APP_HEADER} (设置 → 模型服务). Rewritten before every engine start; edits here are lost.\n- id: tool-web\n  config: {"search":false}\n`
  writeFileSync(join(p.profile, 'cordis.patch.yml'), legacy)
  // boot 1 (never ready) and boot 2 (never ready)
  let b = prepareBoot(env)
  assert.deepEqual(b.patchFiles, [p.overlay], 'the old generated profile patch became the overlay')
  assert.equal(readFileSync(p.overlay, 'utf8'), legacy)
  assert.match(readFileSync(join(p.profile, 'cordis.patch.yml'), 'utf8'), /^# User layer[\s\S]*\[\]\n$/)
  assert.ok(readdirSync(p.profile).some(f => f.startsWith('cordis.patch.yml.bak-')))
  assert.match(readFileSync(join(p.profile, 'pnpm-workspace.yaml'), 'utf8'), /nodeLinker: hoisted[\s\S]*packageImportMethod: copy[\s\S]*storeDir: /)
  assert.match(readFileSync(join(p.profile, '.pnpmfile.cjs'), 'utf8'), /require\(".*plugin-extensions\/pnpmfile\.cjs"\)/)
  prepareBoot(env)
  assert.equal(readState(p).safeMode.active, false)
  // boot 3: two in a row never became ready → safe mode
  b = prepareBoot(env)
  const s = readState(p)
  assert.equal(s.safeMode.active, true)
  assert.deepEqual(s.safeMode.savedBundles, [...BUILTIN_BUNDLES, 'plugin-a'])
  assert.equal(s.suspended['plugin-a'].reason, 'safe-mode')
  assert.deepEqual(JSON.parse(readFileSync(join(p.profile, 'package.json'), 'utf8')).dsh.profile.bundles, BUILTIN_BUNDLES)
  assert.equal(s.notices[0].kind, 'safe-mode')
  markBoot('ready', p)
  assert.equal(JSON.parse(readFileSync(p.journal, 'utf8')).boots.at(-1).phase, 'ready')

  // a crash attributed to plugin-a suspends it and does not count as a safe-mode failure
  const m2 = { ...manifest }
  writeFileSync(join(p.profile, 'package.json'), JSON.stringify(m2))
  mkdirSync(join(p.profile, 'node_modules/plugin-a'), { recursive: true })
  writeFileSync(join(p.profile, 'node_modules/plugin-a/package.json'), JSON.stringify({ name: 'plugin-a', version: '1.0.0' }))
  writeFileSync(p.crash, JSON.stringify({ message: 'boom', stack: `Error: boom\n    at x (${join(p.profile, 'node_modules/plugin-a/index.js')}:1:1)` }))
  prepareBoot(env)
  const s2 = readState(p)
  assert.equal(s2.suspended['plugin-a'].reason, 'crash')
  assert.equal(s2.suspended['plugin-a'].message, 'boom')
  assert.equal(JSON.parse(readFileSync(p.crash, 'utf8')).attributedTo, 'plugin-a')
  assert.equal(s2.notices.at(-1).kind, 'suspended')
  // forced safe mode (the app's DSH_ANDROID_SAFE_MODE=1) with nothing third-party left: no-op
  prepareBoot({ ...env, DSH_ANDROID_SAFE_MODE: '1' })
})

test('boot: workspace keys keep foreign settings', () => {
  const text = 'packages:\n  - .\n\nnodeLinker: isolated\nallowBuilds:\n  foo: true\n'
  const out = withWorkspaceKeys(text, { nodeLinker: 'hoisted', packageImportMethod: 'copy' })
  assert.match(out, /nodeLinker: hoisted/)
  assert.match(out, /allowBuilds:\n  foo: true/)
  assert.match(out, /packageImportMethod: copy/)
  assert.equal(withWorkspaceKeys(out, { nodeLinker: 'hoisted', packageImportMethod: 'copy' }), out)
  assert.equal(migrateAppLayer(paths({ DSH_HOME: tmp() })), undefined)
})

test('archive: tgz round trip, zip reading, package root', () => {
  const tgz = writeTgz([{ path: 'package.json', data: Buffer.from('{"name":"x"}') }, { path: 'lib/a.js', data: Buffer.from('1') }])
  const entries = readTar(tgz)
  assert.deepEqual(entries.map(e => e.path).sort(), ['package/lib/a.js', 'package/package.json'])
  assert.equal(packageRoot(entries), 'package')
  // a hand-made zip with one deflated and one stored member
  const files = [['repo-main/package.json', Buffer.from('{"name":"z","version":"1.0.0"}'), 8], ['repo-main/index.js', Buffer.from('export {}'), 0]]
  const locals = []; const central = []; let offset = 0
  for (const [name, data, method] of files) {
    const body = method === 8 ? deflateRawSync(data) : data
    const n = Buffer.from(name)
    const lh = Buffer.alloc(30); lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(method, 8); lh.writeUInt32LE(body.length, 18); lh.writeUInt32LE(data.length, 22); lh.writeUInt16LE(n.length, 26)
    const ch = Buffer.alloc(46); ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(method, 10); ch.writeUInt32LE(body.length, 20); ch.writeUInt32LE(data.length, 24); ch.writeUInt16LE(n.length, 28); ch.writeUInt32LE((0o100644 << 16) >>> 0, 38); ch.writeUInt32LE(offset, 42)
    locals.push(lh, n, body); central.push(ch, n); offset += 30 + n.length + body.length
  }
  const cd = Buffer.concat(central)
  const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10); end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16)
  const zip = Buffer.concat([...locals, cd, end])
  const z = readZip(zip)
  assert.equal(z.find(e => e.path.endsWith('package.json')).data.toString(), '{"name":"z","version":"1.0.0"}')
  assert.equal(packageRoot(z), 'repo-main')
  assert.throws(() => readTar(writeTgz([{ path: '../evil', data: Buffer.from('') }])))
})

test('scan: ELF, .node and prebuilds are found; JS is not', () => {
  const dir = tmp()
  mkdirSync(join(dir, 'lib'), { recursive: true })
  writeFileSync(join(dir, 'lib/index.js'), 'module.exports = 1')
  assert.equal(findBinary(dir), undefined)
  writeFileSync(join(dir, 'lib/helper'), Buffer.from([0x7f, 0x45, 0x4c, 0x46, 2, 1, 1]))
  assert.equal(findBinary(dir), 'lib/helper')
})

test('sources: GitHub URL parsing and repository matching', () => {
  assert.deepEqual(parseGithub('https://github.com/Fei2411/DS-Bot'), { owner: 'Fei2411', repo: 'DS-Bot', ref: undefined, dir: '' })
  assert.deepEqual(parseGithub('https://github.com/o/r/tree/v1.2/packages/x'), { owner: 'o', repo: 'r', ref: 'v1.2', dir: 'packages/x' })
  assert.deepEqual(parseGithub('o/r#main'), { owner: 'o', repo: 'r', ref: 'main', dir: '' })
  assert.equal(parseGithub('@scope/name'), undefined)
  assert.equal(githubRepoOf({ url: 'git+https://github.com/Fei2411/DS-Bot.git', directory: 'ds-bot' }), 'fei2411/ds-bot')
  assert.throws(() => checkBuilt([{ path: 'src/index.ts' }], { main: 'dist/index.js', scripts: { build: 'tsc' } }), /needs-build/)
  checkBuilt([{ path: 'dist/index.js' }], { exports: { '.': { types: './dist/index.d.ts', import: './dist/index.js' } } })
})

test('sources: resolution against fake GitHub / jsDelivr / registry endpoints', async () => {
  const { resolveSource } = await import('../packages/plugin-extensions/sources.js')
  const staging = tmp()
  const repoTgz = (files) => writeTgz(Object.entries(files).map(([path, data]) => ({ path, data: Buffer.from(data) })))
  const srcRepo = repoTgz({ 'package.json': JSON.stringify({ name: 'src-plugin', version: '0.1.0', main: 'dist/index.js', scripts: { build: 'tsc' } }), 'src/index.ts': '' })
  const builtRepo = repoTgz({ 'package.json': JSON.stringify({ name: 'built-plugin', version: '0.2.0', main: 'index.js', dsh: { bundle: { patch: './p.yml' } } }), 'index.js': 'export {}', 'p.yml': '[]' })
  const monoRepo = repoTgz({ 'README.md': '', 'packages/inner/package.json': JSON.stringify({ name: 'inner-plugin', version: '1.0.0', main: 'i.js' }), 'packages/inner/i.js': 'export {}' })
  const routes = {
    '/api/repos/o/onnpm': { default_branch: 'main' },
    '/raw/o/onnpm/main/package.json': { name: 'on-npm', version: '1.0.0' },
    '/registry/on-npm/latest': { name: 'on-npm', version: '1.0.0', repository: { url: 'git+https://github.com/O/onNPM.git' }, dsh: { bundle: { patch: './x.yml' } } },
    '/api/repos/o/rel': { default_branch: 'main' },
    '/api/repos/o/rel/releases/latest': { assets: [{ name: 'rel-1.0.0.tgz', browser_download_url: 'https://example.invalid/rel-1.0.0.tgz' }] },
    '/api/repos/o/src': { default_branch: 'main' },
    '/api/repos/o/built': { default_branch: 'main' },
    '/api/repos/o/mono': { default_branch: 'dev' },
  }
  // codeload archives hold `<repo>-<ref>/…`
  const codeload = (tgz, top) => writeTgz(readTar(tgz).map(e => ({ ...e, path: `${top}/${e.path.replace(/^package\//, '')}` })), '')
  const binary = { '/codeload/o/src/tar.gz/main': codeload(srcRepo, 'src-main'), '/codeload/o/built/tar.gz/main': codeload(builtRepo, 'built-main'), '/codeload/o/mono/tar.gz/dev': codeload(monoRepo, 'mono-dev') }
  const server = createServer((req, res) => {
    const u = req.url
    if (binary[u]) { res.writeHead(200); res.end(binary[u]); return }
    if (routes[u]) { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(routes[u])); return }
    res.writeHead(404); res.end('{}')
  })
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  const base = `http://127.0.0.1:${server.address().port}`
  const env = { DSH_ANDROID_GITHUB_API: `${base}/api`, DSH_ANDROID_GITHUB_RAW: `${base}/raw`, DSH_ANDROID_GITHUB_CODELOAD: `${base}/codeload`, DSH_ANDROID_JSDELIVR: `${base}/jsd` }
  const saved = { ...process.env }
  Object.assign(process.env, env)
  try {
    const opts = { registry: `${base}/registry/`, staging }
    assert.deepEqual((await resolveSource('https://github.com/o/onnpm', opts)).spec, 'on-npm')
    const rel = await resolveSource('https://github.com/o/rel', opts)
    assert.equal(rel.kind, 'tarball'); assert.equal(rel.spec, 'https://example.invalid/rel-1.0.0.tgz')
    await assert.rejects(resolveSource('https://github.com/o/src', opts), e => e.code === 'needs-build')
    const built = await resolveSource('https://github.com/o/built', opts)
    assert.equal(built.kind, 'github'); assert.match(built.spec, /^file:.*built-plugin-0\.2\.0-.*\.tgz$/)
    const inner = await resolveSource('https://github.com/o/mono/tree/dev/packages/inner', opts)
    assert.match(inner.spec, /inner-plugin-1\.0\.0/)
    await assert.rejects(resolveSource('git@github.com:o/r.git', opts), e => e.code === 'needs-git')
    await assert.rejects(resolveSource('https://github.com/o/missing', opts), e => e.code === 'needs-git')
    assert.equal((await resolveSource('https://www.npmjs.com/package/@scope/pkg/v/1.2.3', opts)).spec, '@scope/pkg@1.2.3')
    assert.equal((await resolveSource('https://npmmirror.com/package/ds-bot', opts)).spec, 'ds-bot')
    assert.equal((await resolveSource('ds-bot@0.2.4', opts)).kind, 'npm')
    assert.equal((await resolveSource('https://example.com/a/x-1.0.0.tgz', opts)).kind, 'tarball')
    // local .zip → repacked; a zip without package.json → bad-archive
    const zipDir = tmp()
    const tgz = tarballOf({ name: 'local-plugin', version: '3.0.0' }, { 'index.js': '' })
    writeFileSync(join(zipDir, 'p.tgz'), tgz)
    assert.equal((await resolveSource(join(zipDir, 'p.tgz'), opts)).spec, `file:${join(zipDir, 'p.tgz')}`)
    writeFileSync(join(zipDir, 'bad.zip'), Buffer.from('PK\x05\x06' + '\0'.repeat(18), 'latin1'))
    await assert.rejects(resolveSource(join(zipDir, 'bad.zip'), opts), e => e.code === 'bad-archive')
    await assert.rejects(resolveSource('not a thing!', opts), e => e.code === 'bad-input')
  } finally {
    for (const k of Object.keys(env)) delete process.env[k]
    Object.assign(process.env, saved)
    server.closeAllConnections?.(); server.close()
  }
})

test('mcp: pasted configurations are classified', () => {
  const servers = classifyText(JSON.stringify({ mcpServers: {
    fs: { command: 'npx', args: ['-y', '@modelcontextprotocol/server-filesystem', '/tmp'] },
    py: { command: 'uvx', args: ['mcp-server-fetch'] },
    old: { url: 'https://example.com/sse' },
    remote: { type: 'http', url: 'https://example.com/mcp', headers: { Authorization: 'Bearer x' } },
    local: { command: 'node', args: ['/Users/me/server.js'] },
    dock: { command: 'docker', args: ['run', 'x'] },
  } }))
  const by = Object.fromEntries(servers.map(s => [s.id, s]))
  assert.equal(by.fs.kind, 'npm'); assert.equal(by.fs.package, '@modelcontextprotocol/server-filesystem'); assert.deepEqual(by.fs.args, ['/tmp'])
  assert.equal(by.py.error.code, 'mcp-runtime'); assert.equal(by.py.error.params.runtime, 'Python')
  assert.equal(by.old.error.code, 'mcp-sse-only')
  assert.equal(by.remote.supported, true); assert.equal(by.remote.headers.Authorization, 'Bearer x')
  assert.equal(by.local.error.code, 'mcp-local-path')
  assert.equal(by.dock.error.params.runtime, 'Docker')
  assert.deepEqual(npxPackage(['-y', '-p', 'pkg@1', 'bin-name', '--port', '1']), { package: 'pkg@1', args: ['bin-name', '--port', '1'] })
  assert.equal(classifyText('https://mcp.example.com/mcp')[0].kind, 'remote')
})

test('errors: manager results become UserErrors', () => {
  const gateOut = { application: 'failed', error: { code: 'operation-error', diagnostic: ' ERR_PNPM_PNPMFILE_FAIL  Error during pnpmfile execution. DSHA_NATIVE node-pty@1.0.0 native-addon gypfile' } }
  const e = fromManagerResult(gateOut, { name: 'term-plugin' })
  assert.equal(e.code, 'native-addon'); assert.equal(e.params.pkg, 'node-pty@1.0.0'); assert.match(e.message, /term-plugin/)
  const inc = fromManagerResult({ application: 'failed', error: { code: 'incompatible-version', incompatible: [{ name: 'p', version: '1.0.0', runtimeVersion: '0.2.1-rc.5', requirements: { 'engines.dsh': '>=0.2.2' } }] } }, { dsh: '0.2.1-rc.5' })
  assert.equal(inc.code, 'incompatible-version'); assert.equal(inc.params.range, '>=0.2.2'); assert.deepEqual(inc.actions, ['force-install'])
  assert.equal(fromManagerResult({ application: 'failed', packageResult: { kind: 'integrity', output: 'ERR_PNPM_TARBALL_INTEGRITY' }, error: { code: 'operation-error' } }).code, 'integrity')
  assert.equal(fromManagerResult({ application: 'failed', packageResult: { kind: 'pnpm-missing', output: '' }, error: { code: 'operation-error' } }).code, 'installer-broken')
  assert.equal(userError('no-such-code', { reason: 'x' }).message, 'The operation failed: x')
})
