const unsafe = new Set(['__proto__', 'constructor', 'prototype'])
const clone = value => structuredClone(value)
const plain = value => value !== null && typeof value === 'object' && !Array.isArray(value)

function safeValue(value, depth = 0) {
  if (depth > 16) throw new Error('Unsafe request depth')
  if (Array.isArray(value)) return value.forEach(item => safeValue(item, depth + 1))
  if (plain(value)) for (const [key, item] of Object.entries(value)) {
    if (unsafe.has(key)) throw new Error('Unsafe request key')
    safeValue(item, depth + 1)
  }
}

function get(value, path) {
  let current = value
  for (const part of path.split('.')) {
    if (unsafe.has(part)) throw new Error('Unsafe state path')
    if (!current || typeof current !== 'object' || !Object.hasOwn(current, part)) return undefined
    current = current[part]
  }
  return current
}

function set(value, path, next) {
  const parts = path.split('.')
  let current = value
  for (const part of parts.slice(0, -1)) {
    if (unsafe.has(part)) throw new Error('Unsafe state path')
    if (!plain(current[part])) current[part] = {}
    current = current[part]
  }
  if (unsafe.has(parts.at(-1))) throw new Error('Unsafe state path')
  current[parts.at(-1)] = clone(next)
}

function sourceValue(condition, request, state) {
  const root = condition.left.source === 'state' ? state : request[condition.left.source]
  return get(root, condition.left.path)
}

function matches(condition, request, state) {
  if (condition.op === 'all') return condition.items.every(item => matches(item, request, state))
  if (condition.op === 'any') return condition.items.some(item => matches(item, request, state))
  if (condition.op === 'not') return !matches(condition.item, request, state)
  const value = sourceValue(condition, request, state)
  switch (condition.op) {
    case 'exists': return value !== undefined
    case 'equal': return value !== undefined && JSON.stringify(value) === JSON.stringify(condition.value)
    case 'type': return value !== undefined && (value === null ? 'null' : Array.isArray(value) ? 'array' : typeof value) === condition.value
    case 'in': return value !== undefined && condition.value.some(item => JSON.stringify(item) === JSON.stringify(value))
    case 'range': return typeof value === 'number' && value >= condition.value.min && value <= condition.value.max
    default: throw new Error('Unknown condition')
  }
}

function render(value, state) {
  if (Array.isArray(value)) return value.map(item => render(item, state))
  if (plain(value)) return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, render(item, state)]))
  if (typeof value !== 'string') return value
  const exact = /^\{\{state\.([A-Za-z0-9_.-]+)\}\}$/.exec(value)
  if (exact) {
    const replacement = get(state, exact[1])
    if (replacement === undefined) throw new Error('Missing state template value')
    return clone(replacement)
  }
  return value.replace(/\{\{state\.([A-Za-z0-9_.-]+)\}\}/g, (_match, key) => {
    const replacement = get(state, key)
    if (replacement === undefined) throw new Error('Missing state template value')
    return String(replacement)
  })
}

function apply(operations, request, state) {
  for (const operation of operations) {
    const current = get(state, operation.path)
    if (operation.operation === 'SET_FROM_REQUEST') {
      const value = get(request.body, operation.requestPath)
      if (value === undefined || value === '[REDACTED]' || value === '[TRUNCATED]') throw new Error('Missing or redacted request field')
      if (current !== undefined && (typeof current !== typeof value || Array.isArray(current) !== Array.isArray(value))) throw new Error('Request field type mismatch')
      set(state, operation.path, value)
    } else if (operation.operation === 'SET') set(state, operation.path, operation.value)
    else if (operation.operation === 'MERGE') set(state, operation.path, { ...(plain(current) ? current : {}), ...operation.value })
    else if (operation.operation === 'INCREMENT') set(state, operation.path, (Number(current) || 0) + operation.value)
    else throw new Error('Unknown state operation')
  }
}

export function evaluateBehavior(model, request, state) {
  const unchanged = () => clone(state)
  safeValue(request)
  safeValue(state)
  if (new TextEncoder().encode(JSON.stringify(request)).length > 65536) {
    return { response: { status: 413, body: { error: 'Request too large' } }, nextState: unchanged(), ruleId: null, evidence: null }
  }
  if (typeof request.path !== 'string' || !request.path.startsWith('/') || /[%\\?#\u0000-\u001f]/.test(request.path)
      || request.path.split('/').some(part => part === '.' || part === '..')) {
    return { response: { status: 400, body: { error: 'Invalid path' } }, nextState: unchanged(), ruleId: null, evidence: null }
  }
  const rule = [...model.rules].sort((a, b) => b.priority - a.priority).find(candidate =>
    candidate.match.method === request.method && candidate.match.path === request.path
      && (!candidate.when || matches(candidate.when, request, state)))
  if (!rule) return { response: { status: 501, body: { error: 'Capability unavailable' } }, nextState: unchanged(), ruleId: null, evidence: null }
  const nextState = unchanged()
  if (rule.response.status >= 200 && rule.response.status < 300) apply(rule.operations, request, nextState)
  const response = { ...rule.response, body: render(rule.response.body, nextState) }
  return { response, nextState, ruleId: rule.id, evidence: rule.evidence }
}
