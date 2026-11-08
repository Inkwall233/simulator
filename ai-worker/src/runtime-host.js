import { mkdir, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'

const runIdPattern = /^[a-f0-9]{32}$/

export async function createRunRegistry(directory) {
  const root = path.resolve(directory)
  await mkdir(root, { recursive: true })
  const runs = new Map()
  const usedIds = new Set()
  for (const name of await readdir(root)) {
    if (!name.endsWith('.json') || !runIdPattern.test(name.slice(0, -5))) continue
    try {
      const record = JSON.parse(await readFile(path.join(root, name), 'utf8'))
      if (validRecord(record) && record.runId + '.json' === name) { runs.set(record.runId, record); usedIds.add(record.runId) }
    } catch { }
  }

  async function persist(record) {
    const target = path.join(root, record.runId + '.json')
    const temporary = target + '.tmp'
    await writeFile(temporary, JSON.stringify(record), { mode: 0o600 })
    await rename(temporary, target)
    runs.set(record.runId, record)
  }

  return {
    async register(record) {
      if (!validRecord(record) || usedIds.has(record.runId)) throw new Error('Invalid or reused run registration')
      await persist(record)
      usedIds.add(record.runId)
    },
    async heartbeat(runId, expiresAt) {
      const record = runs.get(runId)
      if (!record || !Number.isFinite(expiresAt) || expiresAt <= Date.now() || expiresAt > record.maxExpiresAt) {
        throw new Error('Run lease is invalid')
      }
      await persist({ ...record, expiresAt })
    },
    async end(runId) {
      if (!runIdPattern.test(runId)) throw new Error('Invalid run id')
      const record = runs.get(runId)
      runs.delete(runId)
      await rm(path.join(root, runId + '.json'), { force: true })
      if (record) {
        const packagePath = path.resolve(record.sourceDirectory)
        if (path.basename(packagePath) === runId && path.dirname(packagePath) === path.resolve(root)) {
          await rm(packagePath, { recursive: true, force: true })
        }
      }
    },
    async resolve(host, now = Date.now()) {
      const normalized = normalizeHost(host)
      for (const [runId, record] of runs) {
        if (record.host !== normalized) continue
        if (record.expiresAt <= now || record.maxExpiresAt <= now) {
          runs.delete(runId)
          await rm(path.join(root, runId + '.json'), { force: true })
          return null
        }
        return structuredClone(record)
      }
      return null
    },
    async cleanupExpired(now = Date.now()) {
      for (const [runId, record] of runs) {
        if (record.expiresAt > now && record.maxExpiresAt > now) continue
        runs.delete(runId)
        await rm(path.join(root, runId + '.json'), { force: true })
        const packagePath = path.resolve(record.sourceDirectory)
        if (path.basename(packagePath) === runId && path.dirname(packagePath) === path.resolve(root)) {
          await rm(packagePath, { recursive: true, force: true })
        }
      }
    }
  }
}

export async function resolveRun(registry, host, now = Date.now()) {
  return registry.resolve(host, now)
}

function validRecord(record) {
  return record && typeof record === 'object' && runIdPattern.test(record.runId)
    && typeof record.host === 'string' && record.host === normalizeHost(record.host)
    && record.host.startsWith(record.runId + '.')
    && Number.isFinite(record.expiresAt) && Number.isFinite(record.maxExpiresAt)
    && record.expiresAt <= record.maxExpiresAt && record.draft && typeof record.draft === 'object'
}

function normalizeHost(host) {
  return String(host || '').trim().toLowerCase()
}
