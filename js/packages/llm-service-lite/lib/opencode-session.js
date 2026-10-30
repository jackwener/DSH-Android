// Android integration for the vendored adapter; lib/index.js stays verbatim upstream.
import { randomUUID } from 'node:crypto'

/** Add Go's routing header at the request boundary, using the harness conversation id. */
export function withOpenCodeSessionHeader(url, headers, sessionId) {
  let host
  try { host = new URL(url).hostname } catch { return headers }
  if (host !== 'opencode.ai' && !host.endsWith('.opencode.ai')) return headers
  // Keep an explicitly configured header, including its original casing.
  if (Object.keys(headers).some(name => name.toLowerCase() === 'x-opencode-session')) return headers
  // Auxiliary calls may have no conversation id. Give them an isolated identity rather
  // than sharing one global bucket, and never put control characters on the wire.
  const id = typeof sessionId === 'string' && /^[\x21-\x7e]+$/.test(sessionId) ? sessionId : randomUUID()
  return { ...headers, 'x-opencode-session': id }
}
