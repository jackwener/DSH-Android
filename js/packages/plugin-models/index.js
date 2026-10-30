// The same resolved metadata that validates initialize/reasoningEffort drives the phone picker.
// No HTTP discovery, credentials, prompts or model-family table is added here.
export const name = 'android-models'
export const inject = ['llm']
export const METHOD = 'android/models/reasoning'

export async function reasoningModels(llm, params, signal) {
  if (!Array.isArray(params?.routes) || params.routes.length > 256 || params.routes.some(r =>
    !r || typeof r.provider !== 'string' || !r.provider || typeof r.model !== 'string' || !r.model)) {
    throw new TypeError(`${METHOD} requires at most 256 {provider, model} routes`)
  }
  return { models: await Promise.all(params.routes.map(async ({ provider, model }) => {
    // One unavailable route must not hide every other provider's choices. initialize still
    // reports a selected route's actual failure; an unresolved model offers no effort picker.
    try {
      const info = await llm.resolveModelInfo(provider, model, signal)
      return { provider, id: model, ...(info.reasoning ? { reasoning: info.reasoning } : {}) }
    } catch (error) {
      if (signal?.aborted) throw error
      return { provider, id: model }
    }
  })) }
}

export function apply(ctx) {
  ctx.on('sdk-extensions/resolve', method => method === METHOD
    ? (params, signal) => reasoningModels(ctx.llm, params, signal)
    : undefined)
}
