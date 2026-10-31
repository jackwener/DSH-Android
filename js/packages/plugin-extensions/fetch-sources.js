// One fetch for files that several hosts serve identically: a GitHub file (jsDelivr's CDNs,
// raw.githubusercontent.com) or a file of a published npm package (jsDelivr, unpkg, npmmirror).
//
// The phone is often on a network where one of those hosts is slow or unreachable (mainland China:
// cdn.jsdelivr.net resolves to Fastly addresses that may time out while gcore / testingcf answer;
// raw.githubusercontent.com is often blocked). `fetchFirst` starts the candidates in order, the next
// one `staggerMs` after the previous (or at once when it fails), aborts a candidate that sends no
// response headers within `headerTimeoutMs`, and resolves with the first ok response; the rest are
// aborted. The source that answered is remembered per family and asked first next time.
// A failure throws SourcesError, whose `attempts` name every source tried and why it failed.
//
// Every request goes through the global fetch, so the proxy dispatcher dsh-http-proxy installs from
// the system proxy (EngineProxy) applies to every candidate.

/** jsDelivr's equivalent CDNs, in the order tried (cdn = Fastly + Cloudflare; gcore; testingcf = Cloudflare). */
export const JSDELIVR_HOSTS = Object.freeze(['https://cdn.jsdelivr.net', 'https://fastly.jsdelivr.net', 'https://gcore.jsdelivr.net', 'https://testingcf.jsdelivr.net'])
export const GITHUB_RAW = 'https://raw.githubusercontent.com'
export const UNPKG = 'https://unpkg.com'
export const NPMMIRROR_FILES = 'https://registry.npmmirror.com'

const trim = s => String(s).replace(/\/+$/, '')

/** Hosts of the GitHub-file family; `env` overrides (tests, a private mirror) replace the defaults. */
export function githubFileHosts(env = process.env) {
  const jsd = env.DSH_ANDROID_JSDELIVR ? [trim(env.DSH_ANDROID_JSDELIVR)] : [...JSDELIVR_HOSTS]
  const raw = trim(env.DSH_ANDROID_GITHUB_RAW || GITHUB_RAW)
  return { jsdelivr: jsd, raw }
}

/**
 * The GitHub file a URL names: `<jsDelivr>/gh/<owner>/<repo>@<ref>/<path>`,
 * `<raw>/<owner>/<repo>/<ref>/<path>` or `https://github.com/<owner>/<repo>/(raw|blob)/<ref>/<path>`;
 * undefined for any other URL.
 */
export function parseGithubFile(text, env = process.env) {
  let url
  try { url = new URL(text) } catch { return undefined }
  if (url.search !== '' || url.hash !== '') return undefined
  const hosts = githubFileHosts(env)
  const origin = `${url.protocol}//${url.host}`
  const under = base => {
    const b = new URL(base)
    if (`${b.protocol}//${b.host}` !== origin) return undefined
    const prefix = b.pathname.replace(/\/+$/, '')
    return url.pathname.startsWith(prefix + '/') ? url.pathname.slice(prefix.length) : undefined
  }
  const jsdHosts = [...new Set([...hosts.jsdelivr, ...JSDELIVR_HOSTS])]
  for (const base of jsdHosts) {
    const rest = under(base)
    const m = rest && /^\/gh\/([^/@]+)\/([^/@]+)@([^/]+)\/(.+)$/.exec(rest)
    if (m) return { owner: m[1], repo: m[2], ref: decodeURIComponent(m[3]), path: m[4] }
  }
  for (const base of new Set([hosts.raw, GITHUB_RAW])) {
    const rest = under(base)
    const m = rest && /^\/([^/]+)\/([^/]+)\/([^/]+)\/(.+)$/.exec(rest)
    if (m) return { owner: m[1], repo: m[2], ref: decodeURIComponent(m[3]), path: m[4] }
  }
  if (/^(www\.)?github\.com$/i.test(url.hostname)) {
    const m = /^\/([^/]+)\/([^/]+)\/(?:raw|blob)\/([^/]+)\/(.+)$/.exec(url.pathname)
    if (m) return { owner: m[1], repo: m[2], ref: decodeURIComponent(m[3]), path: m[4] }
  }
  return undefined
}

/** Equivalent URLs of one GitHub file: every jsDelivr CDN, then raw.githubusercontent.com. */
export function githubFileUrls({ owner, repo, ref, path }, env = process.env) {
  const hosts = githubFileHosts(env)
  const p = String(path).replace(/^\/+/, '')
  const r = String(ref).split('/').map(encodeURIComponent).join('/')
  return [...hosts.jsdelivr.map(h => `${h}/gh/${owner}/${repo}@${r}/${p}`), `${hosts.raw}/${owner}/${repo}/${r}/${p}`]
}

/**
 * Sources for [url]: its GitHub-file mirrors (the URL itself first) or just [url].
 * @returns { family, urls }
 */
export function sourcesFor(url, env = process.env) {
  const gh = parseGithubFile(url, env)
  if (gh === undefined) return { family: undefined, urls: [url] }
  return { family: 'github', urls: [...new Set([url, ...githubFileUrls(gh, env)])] }
}

/** Equivalent URLs of one file of a published npm package (version or tag; `latest` by default). */
export function npmFileUrls(name, version, path, env = process.env) {
  const p = String(path).replace(/^\.?\/+/, '')
  const v = version ?? 'latest'
  const jsd = env.DSH_ANDROID_JSDELIVR ? [trim(env.DSH_ANDROID_JSDELIVR)] : [JSDELIVR_HOSTS[0], JSDELIVR_HOSTS[2], JSDELIVR_HOSTS[3]]
  return [
    ...jsd.map(h => `${h}/npm/${name}@${v}/${p}`),
    `${trim(env.DSH_ANDROID_UNPKG || UNPKG)}/${name}@${v}/${p}`,
    `${trim(env.DSH_ANDROID_NPMMIRROR_FILES || NPMMIRROR_FILES)}/${name}/${v}/files/${p}`,
  ]
}

// ── the remembered source per family ────────────────────────────────────────

const preferred = new Map()
let onPreferred = () => {}

const originOf = url => { try { const u = new URL(url); return `${u.protocol}//${u.host}` } catch { return url } }
const hostOf = url => { try { return new URL(url).host } catch { return url } }

/** Restore remembered sources (`{ family: origin }`) and get told when one changes. */
export function usePreferences(saved, onChange) {
  preferred.clear()
  for (const [k, v] of Object.entries(saved ?? {})) if (typeof v === 'string') preferred.set(k, v)
  onPreferred = typeof onChange === 'function' ? onChange : () => {}
}
export const preferences = () => Object.fromEntries(preferred)

function remember(family, url) {
  if (family === undefined) return
  const origin = originOf(url)
  if (preferred.get(family) === origin) return
  preferred.set(family, origin)
  try { onPreferred(preferences()) } catch {}
}

/** [urls] with the family's remembered origin first (stable otherwise). */
export function ordered(urls, family) {
  const origin = family === undefined ? undefined : preferred.get(family)
  if (origin === undefined) return [...urls]
  return [...urls.filter(u => originOf(u) === origin), ...urls.filter(u => originOf(u) !== origin)]
}

// ── failures ────────────────────────────────────────────────────────────────

/** Root cause of a fetch rejection, as `CODE: message` (undici wraps it in `fetch failed`). */
function rootOf(error) {
  let e = error
  for (let i = 0; i < 5 && e?.cause !== undefined && e.cause !== e; i++) e = e.cause
  if (e instanceof AggregateError && e.errors?.length) e = e.errors.find(x => x?.code) ?? e.errors[0]
  return e
}

/** A short machine-stable reason: timeout | dns | refused | reset | tls | http-<status> | aborted | network. */
export function reasonOf(error) {
  if (error?.status !== undefined) return `http-${error.status}`
  if (error?.name === 'HeaderTimeoutError') return 'timeout'
  const root = rootOf(error)
  const code = String(root?.code ?? '')
  const text = `${code} ${root?.name ?? ''} ${root?.message ?? ''} ${error?.message ?? ''}`
  if (/UND_ERR_CONNECT_TIMEOUT|ETIMEDOUT|UND_ERR_HEADERS_TIMEOUT|TimeoutError|timed? ?out/i.test(text)) return 'timeout'
  if (/ENOTFOUND|EAI_AGAIN|EAI_NODATA/.test(text)) return 'dns'
  if (/ECONNREFUSED/.test(text)) return 'refused'
  if (/ECONNRESET|EPIPE|UND_ERR_SOCKET|other side closed|terminated/i.test(text)) return 'reset'
  if (/CERT|certificate|SSL|TLS|self.signed/i.test(text)) return 'tls'
  if (/ENETUNREACH|EHOSTUNREACH/.test(text)) return 'unreachable'
  if (error?.name === 'AbortError') return 'aborted'
  return 'network'
}

const detailOf = error => {
  const root = rootOf(error)
  return String(root?.code ? `${root.code}: ${root.message}` : root?.message ?? error?.message ?? error).split('\n')[0].slice(0, 200)
}

export class SourcesError extends Error {
  /** @param attempts [{ url, host, reason, detail }] in the order tried */
  constructor(attempts) {
    super(`no source answered: ${attempts.map(a => `${a.host} (${a.reason}${a.detail && a.reason !== a.detail ? `: ${a.detail}` : ''})`).join('; ')}`)
    this.name = 'SourcesError'
    this.attempts = attempts
  }
}

class HeaderTimeoutError extends Error {
  constructor(ms) { super(`no response within ${ms} ms`); this.name = 'HeaderTimeoutError' }
}

// ── the fetch ───────────────────────────────────────────────────────────────

/**
 * GET the first of [urls] that answers 2xx.
 * @param options.family remembered-source key ('github', 'npm-file'); none = no memory
 * @param options.init fetch init (its `signal` bounds the whole operation, body included)
 * @param options.headerTimeoutMs per source: connect + TLS + response headers (default 6 s)
 * @param options.staggerMs start the next source this long after the previous one (default 2 s)
 * @param options.fetchImpl fetch (tests)
 * @returns { response, url, attempts }
 * @throws SourcesError when every source failed; the caller's abort reason when aborted
 */
export function fetchFirst(urls, options = {}) {
  const { family, init = {}, headerTimeoutMs = 6000, staggerMs = 2000, fetchImpl = fetch } = options
  const list = ordered([...new Set(urls)], family)
  const outer = init.signal
  const attempts = []
  return new Promise((resolve, reject) => {
    if (list.length === 0) { reject(new SourcesError([])); return }
    if (outer?.aborted) { reject(outer.reason); return }
    const controllers = new Set()
    let next = 0
    let running = 0
    let settled = false
    let stagger
    const stop = () => { clearTimeout(stagger); outer?.removeEventListener('abort', onOuterAbort) }
    const onOuterAbort = () => {
      if (settled) return
      settled = true
      stop()
      for (const c of controllers) c.abort(outer.reason)
      reject(outer.reason)
    }
    outer?.addEventListener('abort', onOuterAbort, { once: true })
    const failed = (attempt, error) => {
      attempt.reason = reasonOf(error)
      attempt.detail = error?.status !== undefined ? `HTTP ${error.status}` : detailOf(error)
      running--
      if (settled) return
      if (next < list.length) launch()
      else if (running === 0) { settled = true; stop(); reject(new SourcesError(attempts)) }
    }
    const launch = () => {
      clearTimeout(stagger)
      if (settled || next >= list.length) return
      const url = list[next++]
      const attempt = { url, host: hostOf(url) }
      attempts.push(attempt)
      running++
      const own = new AbortController()
      controllers.add(own)
      const timer = setTimeout(() => own.abort(new HeaderTimeoutError(headerTimeoutMs)), headerTimeoutMs)
      const signal = outer ? AbortSignal.any([outer, own.signal]) : own.signal
      Promise.resolve().then(() => fetchImpl(url, { ...init, signal })).then(response => {
        clearTimeout(timer)
        if (settled) { void response.body?.cancel().catch(() => {}); return }
        if (!response.ok) {
          void response.body?.cancel().catch(() => {})
          failed(attempt, Object.assign(new Error(`HTTP ${response.status}`), { status: response.status }))
          return
        }
        settled = true
        stop()
        attempt.reason = 'ok'
        for (const c of controllers) if (c !== own) c.abort(new Error('another source answered'))
        remember(family, url)
        resolve({ response, url, attempts })
      }, error => {
        clearTimeout(timer)
        controllers.delete(own)
        failed(attempt, own.signal.aborted && own.signal.reason instanceof HeaderTimeoutError ? own.signal.reason : error)
      })
      if (next < list.length) stagger = setTimeout(launch, staggerMs)
    }
    launch()
  })
}

/** fetchFirst for one URL: its GitHub mirrors when it names a GitHub file, else the URL alone. */
export function fetchMirrored(url, options = {}) {
  const { family, urls } = sourcesFor(url, options.env)
  return fetchFirst(urls, { family, ...options })
}

/** The text of the first source that answers; undefined when none does. */
export async function fetchText(urls, options = {}) {
  try {
    const { response } = await fetchFirst(urls, options)
    return await response.text()
  } catch (error) {
    if (error instanceof SourcesError) return undefined
    throw error
  }
}
