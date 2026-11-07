import { evaluateBehavior } from './behavior-interpreter.js'
import { withRunState } from './preview-runtime-store.js'

let previewConfig = null
let runtimeState = {}
let configPromise = null
let requestQueue = Promise.resolve()

self.addEventListener('install', function (event) {
  event.waitUntil(loadConfig().then(function () {
    return self.skipWaiting()
  }))
})

self.addEventListener('activate', function (event) {
  event.waitUntil(loadConfig().then(function () {
    return self.clients.claim()
  }))
})

self.addEventListener('fetch', function (event) {
  const requestUrl = new URL(event.request.url)
  if (requestUrl.origin !== self.location.origin || requestUrl.pathname.startsWith('/__skilllab/')) return
  const result = requestQueue.then(function () { return handleRequest(event.request, requestUrl) })
  requestQueue = result.catch(function () {})
  event.respondWith(result)
})

async function loadConfig() {
  if (configPromise) return configPromise
  configPromise = fetch('/__skilllab/config.json', { cache: 'no-store' })
    .then(function (response) {
      if (!response.ok) throw new Error('Preview configuration is unavailable')
      return response.json()
    })
    .then(function (config) {
      previewConfig = config
      runtimeState = cloneValue(config?.draft?.stateModel?.initial || {})
      return config
    })
    .catch(function (error) {
      configPromise = null
      throw error
    })
  return configPromise
}

async function handleRequest(request, requestUrl) {
  try {
    const config = await loadConfig()
    const method = request.method.toUpperCase()
    if (config.schemaVersion === 'skilllab.simulator-behavior.v2') {
      const active = await fetch('/__skilllab/config.json', { cache: 'no-store' })
      if (!active.ok) return jsonResponse({ ok: false, error: 'Preview is no longer active' }, 410, null)
      const input = {
        method, path: requestUrl.pathname, query: Object.fromEntries(requestUrl.searchParams),
        body: await readRequestBody(request)
      }
      const result = await withRunState(config.previewId, config.draft.initialState,
        state => evaluateBehavior(config.draft, input, state))
      notifyClients(result.ruleId ? 'RULE_HIT' : 'UNMATCHED_REQUEST', {
        ruleId: result.ruleId, method, path: requestUrl.pathname, status: result.response.status
      })
      return responseForBehavior(result.response)
    }
    const rules = Array.isArray(config?.draft?.apiMocks?.rules) ? config.draft.apiMocks.rules : []
    const rule = rules.find(function (candidate) {
      return candidate?.match?.method === method && candidate?.match?.path === requestUrl.pathname
    })
    if (rule) {
      const active = await fetch('/__skilllab/config.json', { cache: 'no-store' })
      if (!active.ok) return jsonResponse({ ok: false, error: 'Preview is no longer active' }, 410, null)
      const requestBody = await readRequestBody(request)
      return mockResponse(rule, method, requestUrl.pathname, config, requestBody)
    }
    if (looksLikeApiRequest(method, requestUrl.pathname)) {
      notifyClients('UNMATCHED_REQUEST', {
        method,
        path: requestUrl.pathname,
        status: 501
      })
      return jsonResponse({
        ok: false,
        error: 'No approved Mock rule matches this request',
        method,
        path: requestUrl.pathname
      }, 501, null)
    }
    return fetch(request)
  } catch (error) {
    notifyClients('MOCK_RUNTIME_ERROR', {
      message: String(error && error.message ? error.message : error).slice(0, 300)
    })
    return jsonResponse({ ok: false, error: 'Mock runtime failed' }, 503, null)
  }
}

function responseForBehavior(result) {
  const type = result.contentType || 'application/json; charset=utf-8'
  const body = [204, 205, 304].includes(result.status) ? null
    : /application\/json/i.test(type) ? JSON.stringify(result.body) : String(result.body)
  return new Response(body, { status: result.status, headers: { 'Cache-Control': 'no-store', 'Content-Type': type } })
}

async function readRequestBody(request) {
  if (['GET', 'HEAD'].includes(request.method)) return null
  const text = await request.text()
  if (text.length > 65536) throw new Error('Mock request exceeds 64 KiB')
  if (!text) return null
  if ((request.headers.get('Content-Type') || '').includes('application/json')) return JSON.parse(text)
  if ((request.headers.get('Content-Type') || '').includes('application/x-www-form-urlencoded')) {
    return Object.fromEntries(new URLSearchParams(text))
  }
  return null
}

function mockResponse(rule, method, path, config, requestBody) {
  const previousState = cloneValue(runtimeState)
  let response
  try {
    if (rule.status >= 200 && rule.status < 300) {
      applyTransitions(config?.draft?.stateModel?.transitions, rule.id, requestBody)
    }
    const body = resolveStateTokens(cloneValue(rule.body))
    response = responseForRule(rule, body)
  } catch (error) {
    runtimeState = previousState
    throw error
  }
  notifyClients('RULE_HIT', {
    ruleId: rule.id,
    method,
    path,
    status: rule.status,
    requiresReview: rule.requiresReview === true
  })
  return response
}

function responseForRule(rule, body) {
  const contentType = typeof rule.contentType === 'string' && rule.contentType
    ? rule.contentType
    : 'application/json; charset=utf-8'
  const headers = {
    'Cache-Control': 'no-store',
    'Content-Type': contentType,
    'X-SkillLab-Mock-Rule': rule.id
  }
  if ([204, 205, 304].includes(rule.status) || rule.match.method === 'HEAD') {
    return new Response(null, { status: rule.status, headers })
  }
  if (/application\/json/i.test(contentType)) {
    return new Response(JSON.stringify(body === undefined ? null : body), {
      status: rule.status,
      headers
    })
  }
  return new Response(typeof body === 'string' ? body : JSON.stringify(body === undefined ? null : body), {
    status: rule.status,
    headers
  })
}

function jsonResponse(body, status, ruleId) {
  const headers = {
    'Cache-Control': 'no-store',
    'Content-Type': 'application/json; charset=utf-8'
  }
  if (ruleId) headers['X-SkillLab-Mock-Rule'] = ruleId
  return new Response(JSON.stringify(body), { status, headers })
}

function looksLikeApiRequest(method, pathname) {
  return method !== 'GET' && method !== 'HEAD'
    || /^\/(?:api|cgi-bin|rpc|v1|v2)(?:\/|$)/i.test(pathname)
}

function applyTransitions(transitions, ruleId, requestBody) {
  if (!Array.isArray(transitions)) return
  for (const transition of transitions) {
    if (!transition || transition.onRuleId !== ruleId || typeof transition.path !== 'string') continue
    if (transition.operation === 'SET_FROM_REQUEST') {
      const value = getObjectPath(requestBody, transition.requestPath)
      if (value === undefined) throw new Error('Required request field is missing')
      const current = getStatePath(transition.path)
      if (current !== undefined && (typeof current !== typeof value || Array.isArray(current) !== Array.isArray(value))) {
        throw new Error('Request field does not match the configured state type')
      }
      setStatePath(transition.path, cloneValue(value))
    } else if (transition.operation === 'SET') {
      setStatePath(transition.path, cloneValue(transition.value))
    } else if (transition.operation === 'MERGE') {
      const current = getStatePath(transition.path)
      const patch = transition.value && typeof transition.value === 'object' && !Array.isArray(transition.value)
        ? transition.value
        : {}
      setStatePath(transition.path, Object.assign({}, isPlainObject(current) ? current : {}, cloneValue(patch)))
    } else if (transition.operation === 'INCREMENT') {
      const increment = Number.isFinite(Number(transition.value)) ? Number(transition.value) : 1
      const current = Number(getStatePath(transition.path)) || 0
      setStatePath(transition.path, current + increment)
    }
  }
}

function resolveStateTokens(value) {
  if (Array.isArray(value)) return value.map(resolveStateTokens)
  if (isPlainObject(value)) {
    const result = {}
    for (const key of Object.keys(value)) result[key] = resolveStateTokens(value[key])
    return result
  }
  if (typeof value !== 'string') return value
  const exact = value.match(/^\{\{state\.([A-Za-z0-9_.-]+)\}\}$/)
  if (exact) return cloneValue(getStatePath(exact[1]))
  return value.replace(/\{\{state\.([A-Za-z0-9_.-]+)\}\}/g, function (_match, statePath) {
    const stateValue = getStatePath(statePath)
    return stateValue === undefined || stateValue === null ? '' : String(stateValue)
  })
}

function getStatePath(statePath) {
  return getObjectPath(runtimeState, statePath)
}

function getObjectPath(value, objectPath) {
  if (typeof objectPath !== 'string') throw new Error('Invalid object path')
  let current = value
  for (const part of objectPath.split('.')) {
    if (['__proto__', 'prototype', 'constructor'].includes(part)) throw new Error('Unsafe object path')
    if (!current || typeof current !== 'object' || !Object.prototype.hasOwnProperty.call(current, part)) return undefined
    current = current[part]
  }
  return current
}

function setStatePath(statePath, value) {
  const parts = statePath.split('.')
  if (parts.some(function (part) { return !part || ['__proto__', 'prototype', 'constructor'].includes(part) })) {
    throw new Error('Unsafe state path')
  }
  let current = runtimeState
  for (let index = 0; index < parts.length - 1; index += 1) {
    const part = parts[index]
    if (!isPlainObject(current[part])) current[part] = {}
    current = current[part]
  }
  current[parts[parts.length - 1]] = value
}

function notifyClients(event, detail) {
  self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (clients) {
    for (const client of clients) client.postMessage({ type: 'SKILLLAB_MOCK_EVENT', event, detail })
  })
}

function cloneValue(value) {
  if (value === undefined) return undefined
  return JSON.parse(JSON.stringify(value))
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
