import assert from 'node:assert/strict'
import { test } from 'node:test'
import { selectEvidence } from '../src/capture-evidence.js'

const event = (overrides = {}) => ({
  id: 'observation-1', sequence: 3, method: 'PUT', url: 'http://device.test/api/config?mode=quick&token=secret',
  requestBody: '{"enabled":true,"password":"secret","nested":{"token":"secret"}}',
  requestContentType: 'application/json', responseSample: { ok: true, token: 'secret' },
  status: 200, contentType: 'application/json', startedAt: '2026-11-07T00:00:00Z', ...overrides
})

test('selects ordered, explicitly allowed fields without secrets', () => {
  const scope = { queryFields: ['mode', 'token'], bodyFields: ['enabled', 'password', 'nested.token'] }
  const selected = selectEvidence(event(), scope)
  assert.equal(selected.sequence, 3)
  assert.equal(selected.path, '/api/config')
  assert.deepEqual(selected.query, { mode: 'quick' })
  assert.deepEqual(selected.body, { enabled: true })
  assert.deepEqual(selected.responseSample, { ok: true })
  assert.equal(JSON.stringify(selected).includes('secret'), false)
  assert.equal(selected.redacted, true)
})

test('refuses missing and truncated request bodies as rule evidence', () => {
  const scope = { bodyFields: ['enabled'] }
  assert.equal(selectEvidence(event({ requestBody: null }), scope).bodyUsable, false)
  const tooLarge = selectEvidence(event({ requestBody: 'x'.repeat(65537) }), scope)
  assert.equal(tooLarge.truncated, true)
  assert.deepEqual(tooLarge.body, {})
  assert.equal(tooLarge.bodyUsable, false)
  assert.equal(selectEvidence(event({ requestBody: null, requestTruncated: true }), scope).truncated, true)
  assert.equal(selectEvidence(event({ requestBody: '{"enabled":"[REDACTED]"}' }), scope).bodyUsable, false)
})

test('does not collapse repeated observations', () => {
  const records = [event(), event({ id: 'observation-2', sequence: 4 })].map(item => selectEvidence(item, {}))
  assert.deepEqual(records.map(item => item.id), ['observation-1', 'observation-2'])
  assert.deepEqual(records.map(item => item.sequence), [3, 4])
})
