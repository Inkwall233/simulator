const unsafeKeys = new Set(['__proto__', 'prototype', 'constructor'])
const operators = new Set(['equal', 'exists', 'type', 'in', 'range'])
const operations = new Set(['SET', 'SET_FROM_REQUEST', 'MERGE', 'INCREMENT'])
const evidenceTypes = new Set(['CAPTURED', 'INFERRED_PENDING', 'INFERRED_CONFIRMED'])

function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function fail(message) { throw new Error(message) }
function onlyKeys(value, allowed, label) {
  if (Object.keys(value).some(key => !allowed.includes(key))) fail(`Invalid ${label} field`)
}

function inspect(value, depth = 0) {
  if (depth > 16) fail('Behavior exceeds nesting depth')
  if (Array.isArray(value)) return value.forEach(item => inspect(item, depth + 1))
  if (object(value)) {
    for (const [key, item] of Object.entries(value)) {
      if (unsafeKeys.has(key)) fail('Unsafe object key')
      inspect(item, depth + 1)
    }
  }
}

function path(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$/.test(value)
      || value.split('.').some(part => unsafeKeys.has(part))) fail('Unsafe state path')
}

function condition(value, depth = 0) {
  if (!object(value) || depth > 8) fail('Invalid condition')
  if (['all', 'any'].includes(value.op)) {
    onlyKeys(value, ['op', 'items'], 'condition')
    if (!Array.isArray(value.items) || !value.items.length) fail('Invalid condition group')
    value.items.forEach(item => condition(item, depth + 1))
  } else if (value.op === 'not') {
    onlyKeys(value, ['op', 'item'], 'condition')
    condition(value.item, depth + 1)
  } else if (operators.has(value.op)) {
    onlyKeys(value, ['op', 'left', 'value'], 'condition')
    if (!object(value.left) || !['state', 'body', 'query'].includes(value.left.source)) fail('Invalid condition source')
    path(value.left.path)
    onlyKeys(value.left, ['source', 'path'], 'condition')
    if (value.op !== 'exists' && !Object.hasOwn(value, 'value')) fail('Invalid condition value')
    if (value.op === 'type' && !['string', 'number', 'boolean', 'object', 'array', 'null'].includes(value.value)) fail('Invalid condition type')
    if (value.op === 'in' && (!Array.isArray(value.value) || value.value.length > 100)) fail('Invalid condition membership')
    if (value.op === 'range' && (!object(value.value) || !Number.isFinite(value.value.min) || !Number.isFinite(value.value.max) || value.value.min > value.value.max)) fail('Invalid condition range')
  } else fail('Unknown condition operator')
}

function canonicalPath(value) {
  if (typeof value !== 'string' || !value.startsWith('/') || /[%\\?#\u0000-\u001f]/.test(value)
      || value.split('/').some(part => part === '..' || part === '.')) fail('Invalid request path')
  return value
}

export function validateBehaviorV2(payload) {
  if (!object(payload)) fail('Invalid behavior')
  const bytes = Buffer.byteLength(JSON.stringify(payload), 'utf8')
  if (bytes > 1024 * 1024) fail('Behavior size limit exceeded')
  inspect(payload)
  onlyKeys(payload, ['schemaVersion', 'packageVersion', 'scope', 'initialState', 'rules', 'verificationCases'], 'behavior')
  if (payload.schemaVersion !== 'skilllab.simulator-behavior.v2'
      || !object(payload.packageVersion) || typeof payload.packageVersion.id !== 'string'
      || !/^[a-f0-9]{64}$/i.test(payload.packageVersion.sha256)
      || !object(payload.scope) || !payload.scope.name || !Array.isArray(payload.scope.criticalRuleIds)
      || !object(payload.initialState) || !Array.isArray(payload.rules)
      || !payload.rules.length || payload.rules.length > 200 || !Array.isArray(payload.verificationCases)) fail('Invalid v2 contract')
  onlyKeys(payload.packageVersion, ['id', 'sha256'], 'package')
  onlyKeys(payload.scope, ['name', 'criticalRuleIds'], 'scope')
  const ids = new Set()
  for (const rule of payload.rules) {
    if (!object(rule)) fail('Invalid behavior rule')
    onlyKeys(rule, ['id', 'priority', 'match', 'when', 'response', 'operations', 'evidence'], 'rule')
    if (!object(rule) || typeof rule.id !== 'string' || !rule.id || ids.has(rule.id)
        || !Number.isInteger(rule.priority) || !object(rule.match)
        || !/^[A-Z]+$/.test(rule.match.method) || !object(rule.response)
        || !Number.isInteger(rule.response.status) || rule.response.status < 200 || rule.response.status > 599
        || !Object.hasOwn(rule.response, 'body') || !Array.isArray(rule.operations)
        || !object(rule.evidence) || !evidenceTypes.has(rule.evidence.type)
        || !Array.isArray(rule.evidence.observationIds)) fail('Invalid behavior rule')
    canonicalPath(rule.match.path)
    onlyKeys(rule.match, ['method', 'path'], 'match')
    onlyKeys(rule.response, ['status', 'contentType', 'body', 'headers'], 'response')
    if ([204, 205, 304].includes(rule.response.status) && rule.response.body !== null) fail('Bodyless response must have null body')
    ids.add(rule.id)
    if (rule.when !== undefined) condition(rule.when)
    for (const operation of rule.operations) {
      if (!object(operation) || !operations.has(operation.operation)) fail('Invalid state operation')
      onlyKeys(operation, ['operation', 'path', 'requestPath', 'value'], 'operation')
      path(operation.path)
      if (operation.operation === 'SET_FROM_REQUEST') path(operation.requestPath)
      else if (!Object.hasOwn(operation, 'value')) fail('Missing operation value')
    }
    onlyKeys(rule.evidence, ['type', 'observationIds', 'note'], 'evidence')
    if (rule.evidence.type === 'CAPTURED' && !rule.evidence.observationIds.length) fail('Captured evidence is required')
    if (rule.evidence.observationIds.some(id => typeof id !== 'string' || !id)) fail('Invalid evidence id')
    if (rule.evidence.type !== 'CAPTURED' && (!rule.evidence.note || typeof rule.evidence.note !== 'string')) fail('Inference needs a note')
  }
  for (const id of payload.scope.criticalRuleIds) if (!ids.has(id)) fail('Unknown critical rule')
  for (let index = 0; index < payload.rules.length; index++) {
    const current = payload.rules[index]
    for (const other of payload.rules.slice(index + 1)) {
      if (current.priority === other.priority && current.match.method === other.match.method
          && current.match.path === other.match.path
          && (!current.when || !other.when || JSON.stringify(current.when) === JSON.stringify(other.when)
            || current.when.op === 'exists' || other.when.op === 'exists'
            || current.when.op === 'type' || other.when.op === 'type')) {
        fail('Ambiguous behavior rules')
      }
    }
  }
  return JSON.parse(JSON.stringify(payload))
}
