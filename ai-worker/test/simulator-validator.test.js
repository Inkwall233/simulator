import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFile } from 'node:fs/promises'
import { validateSimulator } from '../src/simulator-validator.js'

const example = JSON.parse(await readFile(new URL('../../schemas/examples/simulator-behavior-v2.example.json', import.meta.url)))

test('validates confirmed read, write and read-back assertions', () => {
  const behavior = structuredClone(example)
  behavior.verificationCases = [{ id: 'config-save', steps: [
    { method: 'GET', path: '/api/config', expectedStatus: 200, expectedBody: { enabled: false }, expectedRuleId: 'read-config' },
    { method: 'PUT', path: '/api/config', body: { enabled: true }, expectedStatus: 200, expectedBody: { ok: true, enabled: true }, expectedRuleId: 'save-config' },
    { method: 'GET', path: '/api/config', reloadBefore: true, expectedStatus: 200, expectedBody: { enabled: true }, expectedRuleId: 'read-config' }
  ], expectedState: { config: { enabled: true } } }]
  const report = validateSimulator({ behavior })
  assert.equal(report.status, 'PASSED')
  assert.deepEqual(report.hitRuleIds.sort(), ['read-config', 'save-config'])
})

test('blocks missing assertions and pending inferences', () => {
  const behavior = structuredClone(example)
  behavior.verificationCases = []
  behavior.rules[1].evidence = { type: 'INFERRED_PENDING', observationIds: [], note: '需要核实' }
  const report = validateSimulator({ behavior })
  assert.equal(report.status, 'BLOCKED')
  assert.ok(report.blockers.some(item => item.code === 'NO_ASSERTIONS'))
  assert.ok(report.blockers.some(item => item.code === 'INFERENCE_PENDING'))
  assert.ok(report.blockers.some(item => item.code === 'CRITICAL_UNCOVERED'))
})

test('blocks mismatched response and state assertions', () => {
  const behavior = structuredClone(example)
  behavior.verificationCases = [{ id: 'wrong', steps: [
    { method: 'GET', path: '/api/config', expectedStatus: 200, expectedBody: { enabled: true }, expectedRuleId: 'read-config' }
  ], expectedState: { config: { enabled: true } } }]
  const report = validateSimulator({ behavior })
  assert.equal(report.status, 'BLOCKED')
  assert.ok(report.blockers.some(item => item.code === 'ASSERTION_FAILED'))
  assert.ok(report.blockers.some(item => item.code === 'STATE_MISMATCH'))
})

test('rejects an unmatched request as a claimed success', () => {
  const behavior = structuredClone(example)
  behavior.verificationCases = [{ id: 'unsafe', steps: [
    { method: 'GET', path: '/api/unknown', expectedStatus: 501, expectedBody: { error: 'Capability unavailable' }, expectedRuleId: 'read-config' }
  ], expectedState: behavior.initialState }]
  const report = validateSimulator({ behavior })
  assert.equal(report.status, 'BLOCKED')
  assert.ok(report.blockers.some(item => item.code === 'INVALID_STEP'))
})
