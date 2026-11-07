import assert from 'node:assert/strict'
import { test } from 'node:test'
import { evaluateBehavior } from '../src/behavior-interpreter.js'
import { validateBehaviorV2 } from '../src/behavior-contract.js'

function model() {
  const evidence = { type: 'CAPTURED', observationIds: ['observation'] }
  return {
    schemaVersion: 'skilllab.simulator-behavior.v2',
    packageVersion: { id: 'demo', sha256: '0'.repeat(64) },
    scope: { name: 'configuration', criticalRuleIds: ['read', 'save'] },
    initialState: { loggedIn: false, config: { enabled: false } },
    verificationCases: [],
    rules: [
      { id: 'unauthorized', priority: 20, match: { method: 'GET', path: '/api/config' },
        when: { op: 'equal', left: { source: 'state', path: 'loggedIn' }, value: false },
        response: { status: 401, body: { ok: false } }, operations: [], evidence },
      { id: 'read', priority: 10, match: { method: 'GET', path: '/api/config' },
        response: { status: 200, body: { enabled: '{{state.config.enabled}}' } }, operations: [], evidence },
      { id: 'save', priority: 10, match: { method: 'PUT', path: '/api/config' },
        when: { op: 'type', left: { source: 'body', path: 'enabled' }, value: 'boolean' },
        response: { status: 200, body: { enabled: '{{state.config.enabled}}' } },
        operations: [{ operation: 'SET_FROM_REQUEST', path: 'config.enabled', requestPath: 'enabled' }], evidence },
      { id: 'invalid', priority: 1, match: { method: 'PUT', path: '/api/config' },
        response: { status: 400, body: { ok: false } }, operations: [], evidence }
    ]
  }
}

test('selects state and request branches, then reads saved state in both directions', () => {
  const contract = validateBehaviorV2(model())
  let state = structuredClone(contract.initialState)
  assert.equal(evaluateBehavior(contract, { method: 'GET', path: '/api/config' }, state).response.status, 401)
  state.loggedIn = true
  const saved = evaluateBehavior(contract, { method: 'PUT', path: '/api/config', body: { enabled: true } }, state)
  assert.equal(saved.response.body.enabled, true)
  assert.equal(state.config.enabled, false)
  state = saved.nextState
  assert.equal(evaluateBehavior(contract, { method: 'GET', path: '/api/config' }, state).response.body.enabled, true)
  const invalid = evaluateBehavior(contract, { method: 'PUT', path: '/api/config', body: { enabled: 'yes' } }, state)
  assert.equal(invalid.response.status, 400)
  assert.deepEqual(invalid.nextState, state)
  state = evaluateBehavior(contract, { method: 'PUT', path: '/api/config', body: { enabled: false } }, state).nextState
  assert.equal(evaluateBehavior(contract, { method: 'GET', path: '/api/config' }, state).response.body.enabled, false)
})

test('fails closed on absent fields, redaction markers, unsafe objects and unknown paths', () => {
  const contract = validateBehaviorV2(model())
  const state = { loggedIn: true, config: { enabled: false } }
  for (const body of [{}, { enabled: '[REDACTED]' }]) {
    const result = evaluateBehavior(contract, { method: 'PUT', path: '/api/config', body }, state)
    assert.notEqual(result.response.status, 200)
    assert.equal(state.config.enabled, false)
  }
  assert.throws(() => evaluateBehavior(contract, { method: 'PUT', path: '/api/config',
    body: JSON.parse('{"__proto__":{"polluted":true}}') }, state), /unsafe/i)
  assert.equal(evaluateBehavior(contract, { method: 'GET', path: '/api/missing' }, state).response.status, 501)
  assert.equal(evaluateBehavior(contract, { method: 'GET', path: '/api/%252e%252e/config' }, state).response.status, 400)
})

test('rejects response rendering errors without mutating input state', () => {
  const contract = validateBehaviorV2(model())
  contract.rules[2].response.body = { enabled: '{{state.missing}}' }
  const state = { loggedIn: true, config: { enabled: false } }
  assert.throws(() => evaluateBehavior(contract, { method: 'PUT', path: '/api/config', body: { enabled: true } }, state), /missing/i)
  assert.equal(state.config.enabled, false)
})
