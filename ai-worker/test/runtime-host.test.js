import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { createRunRegistry, resolveRun } from '../src/runtime-host.js'

test('run registry persists leases across host restart and rejects expiry and forged hosts', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'skilllab-runs-'))
  try {
    const registry = await createRunRegistry(root)
    const runId = 'a'.repeat(32)
    const host = `${runId}.localhost:3001`
    await registry.register({ runId, host, projectId: 'p1', expiresAt: Date.now() + 60000, maxExpiresAt: Date.now() + 120000,
      sourceDirectory: root, draft: { schemaVersion: 'skilllab.simulator-behavior.v2', rules: [], initialState: {}, scope: {}, verificationCases: [], packageVersion: {} } })
    const restarted = await createRunRegistry(root)
    assert.equal((await resolveRun(restarted, `${runId}.localhost:3001`)).runId, runId)
    assert.equal(await resolveRun(restarted, 'attacker.localhost:3001'), null)
    assert.equal(await restarted.resolve(host, Date.now() + 120001), null)
    assert.equal(await restarted.resolve(host), null)
    const freshId = 'b'.repeat(32)
    await restarted.register({ runId: freshId, host: `${freshId}.localhost:3001`, projectId: 'p1', expiresAt: Date.now() + 60000,
      maxExpiresAt: Date.now() + 120000, sourceDirectory: root, draft: { schemaVersion: 'skilllab.simulator-behavior.v2' } })
    await restarted.end(freshId)
    assert.equal(await restarted.resolve(`${freshId}.localhost:3001`), null)
    await assert.rejects(restarted.register({ runId: runId, host, projectId: 'p1', expiresAt: Date.now() + 60000,
      maxExpiresAt: Date.now() + 120000, sourceDirectory: root, draft: {} }), /reused/)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
