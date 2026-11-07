const sensitive = /(?:pass(?:word|wd)?|token|secret|authoriz(?:ation|e)|cookie|credential|api.?key|session|auth)/i
const limit = 65536

function safeField(name) {
  return typeof name === 'string' && /^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$/.test(name)
    && !name.split('.').some(part => sensitive.test(part) || ['__proto__', 'constructor', 'prototype'].includes(part))
}

function selectedFields(raw, fields) {
  const result = {}
  let redacted = false
  for (const field of fields || []) {
    if (!safeField(field)) { redacted = true; continue }
    const parts = field.split('.')
    let value = raw
    for (const part of parts) value = value && typeof value === 'object' ? value[part] : undefined
    if (value === undefined) continue
    if (typeof value === 'string' && (/\[REDACTED\]|\[TRUNCATED\]/i.test(value) || value.length > 1000)) {
      redacted = true
      continue
    }
    if (value && typeof value === 'object') { redacted = true; continue }
    result[field] = value
  }
  return { result, redacted }
}

function safeResponse(value, depth = 0) {
  if (depth > 8) return undefined
  if (Array.isArray(value)) return value.slice(0, 100).map(item => safeResponse(item, depth + 1))
  if (value && typeof value === 'object') {
    const output = {}
    for (const [key, item] of Object.entries(value).slice(0, 100)) {
      if (sensitive.test(key) || ['__proto__', 'constructor', 'prototype'].includes(key)) continue
      const safe = safeResponse(item, depth + 1)
      if (safe !== undefined) output[key] = safe
    }
    return output
  }
  if (typeof value === 'string') return /\[REDACTED\]|\[TRUNCATED\]/i.test(value) ? undefined : value.slice(0, 1000)
  return value
}

export function selectEvidence(event, scope = {}) {
  const url = new URL(event.url)
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Invalid evidence origin')
  const query = Object.fromEntries(url.searchParams)
  const selectedQuery = selectedFields(query, scope.queryFields)
  const requestBody = event.requestBody
  const truncated = event.requestTruncated === true || Boolean(requestBody && new TextEncoder().encode(requestBody).length > limit)
  let rawBody = null
  if (!truncated && typeof requestBody === 'string') {
    if ((event.requestContentType || '').includes('application/json')) {
      try { rawBody = JSON.parse(requestBody) } catch { rawBody = null }
    } else if ((event.requestContentType || '').includes('application/x-www-form-urlencoded')) {
      rawBody = Object.fromEntries(new URLSearchParams(requestBody))
    }
  }
  const selectedBody = selectedFields(rawBody, scope.bodyFields)
  const response = safeResponse(event.responseSample)
  const body = truncated ? {} : selectedBody.result
  return {
    id: event.id, sequence: event.sequence, method: event.method, path: url.pathname,
    query: selectedQuery.result, body, bodyUsable: !truncated && rawBody !== null
      && !selectedBody.redacted && (scope.bodyFields || []).every(field => Object.hasOwn(body, field)),
    status: event.status, contentType: event.contentType, responseSample: response ?? null,
    startedAt: event.startedAt, truncated, redacted: selectedQuery.redacted || selectedBody.redacted
  }
}
