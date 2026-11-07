import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { validateBehaviorV2 } from '../src/behavior-contract.js'

const example = JSON.parse(await readFile(new URL('../../schemas/examples/simulator-behavior-v2.example.json', import.meta.url)))
const copy = () => structuredClone(example)

test('normalizes the v2 example without modifying it', () => {
  const value = copy()
  const normalized = validateBehaviorV2(value)
  assert.deepEqual(normalized, value)
  assert.notEqual(normalized, value)
})

test('rejects ambiguous rules at the same priority', () => {
  const value = copy()
  value.rules.push({ ...value.rules[0], id: 'duplicate' })
  assert.throws(() => validateBehaviorV2(value), /ambiguous/i)
})

test('rejects unknown operators and dangerous nested state keys', () => {
  const value = copy()
  value.rules[0].when = { op: 'EXEC', value: 'anything' }
  assert.throws(() => validateBehaviorV2(value), /condition/i)
  const polluted = copy()
  polluted.initialState = JSON.parse('{"config":{"__proto__":true}}')
  assert.throws(() => validateBehaviorV2(polluted), /unsafe/i)
})

test('rejects double-encoded paths and oversized inputs', () => {
  const value = copy()
  value.rules[0].match.path = '/api/%252e%252e/config'
  assert.throws(() => validateBehaviorV2(value), /path/i)
  const oversized = copy()
  oversized.initialState.data = 'x'.repeat(1024 * 1024 + 1)
  assert.throws(() => validateBehaviorV2(oversized), /size/i)
})

test('preserves explicit null and rejects invalid references', () => {
  const value = copy()
  value.initialState.config.optional = null
  assert.equal(validateBehaviorV2(value).initialState.config.optional, null)
  value.rules[1].operations[0].path = 'config.constructor.value'
  assert.throws(() => validateBehaviorV2(value), /unsafe/i)
})

test('rejects unmodeled fields and invalid condition values', () => {
  const value = copy()
  value.rules[0].script = 'alert(1)'
  assert.throws(() => validateBehaviorV2(value), /field|rule/i)
  const badType = copy()
  badType.rules[1].when.value = 'arbitrary'
  assert.throws(() => validateBehaviorV2(badType), /condition/i)
  const badStatus = copy()
  badStatus.rules[0].response.status = 204
  assert.throws(() => validateBehaviorV2(badStatus), /body|response/i)
})

test('rejects overlap even when predicates differ and checks evidence', () => {
  const value = copy()
  value.rules.push({ ...structuredClone(value.rules[1]), id: 'other', when: { op: 'exists', left: { source: 'body', path: 'enabled' } } })
  assert.throws(() => validateBehaviorV2(value), /ambiguous/i)
  const missingEvidence = copy()
  missingEvidence.rules[0].evidence.observationIds = []
  assert.throws(() => validateBehaviorV2(missingEvidence), /evidence/i)
})
