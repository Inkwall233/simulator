import assert from 'node:assert/strict'
import { test } from 'node:test'
import { spawn } from 'node:child_process'
import { mkdtemp, mkdir, writeFile, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import vm from 'node:vm'
import { chromium } from 'playwright'

test('Mock runtime handles bodyless responses and rejects unsafe state paths', async () => {
  const context = vm.createContext({
    self: { addEventListener() {}, clients: { matchAll: async () => [] } },
    Response, URL, fetch, console
  })
  const source = await readFile(new URL('../src/preview-mock-sw.js', import.meta.url), 'utf8')
  vm.runInContext(source.replace(/^import .*\n/, ''), context)
  for (const status of [204, 205, 304]) {
    const response = vm.runInContext(`responseForRule({id:'r',status:${status},match:{method:'GET'}}, {ok:true})`, context)
    assert.equal(response.status, status)
    assert.equal(await response.text(), '')
  }
  assert.throws(() => vm.runInContext("setStatePath('__proto__.polluted', true)", context), /Unsafe/)
  assert.equal(vm.runInContext('({}).polluted', context), undefined)
  vm.runInContext("runtimeState = {enabled:false}; applyTransitions([{onRuleId:'save',operation:'SET_FROM_REQUEST',path:'enabled',requestPath:'enabled'}], 'save', {enabled:true})", context)
  assert.equal(vm.runInContext('runtimeState.enabled', context), true)
  assert.throws(() => vm.runInContext("applyTransitions([{onRuleId:'save',operation:'SET_FROM_REQUEST',path:'enabled',requestPath:'enabled'}], 'save', {enabled:'yes'})", context), /type/)
  assert.equal(vm.runInContext('runtimeState.enabled', context), true)
  vm.runInContext("mockResponse({id:'save',status:500,match:{method:'POST'},body:{}}, 'POST', '/api/config', {draft:{stateModel:{transitions:[{onRuleId:'save',operation:'SET',path:'enabled',value:false}]}}}, {})", context)
  assert.equal(vm.runInContext('runtimeState.enabled', context), true)
})

test('isolated preview boots controlled, mocks initial requests and blocks unknown APIs', { timeout: 60000 }, async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'skilllab-preview-'))
  const id = 'a'.repeat(32)
  const directory = path.join(root, id)
  await mkdir(directory)
  await writeFile(path.join(directory, 'index.html'), '<!doctype html><html><head><title>Preview test</title></head><body><h1>Device</h1><script>fetch("/api/device").then(r=>r.json()).then(v=>document.body.dataset.device=v.name)</script></body></html>')
  const port = 23000 + Math.floor(Math.random() * 10000)
  const worker = spawn(process.execPath, ['src/server.js'], {
    cwd: new URL('..', import.meta.url), windowsHide: true,
    env: { ...process.env, PORT: String(port), AI_WORKER_SHARED_SECRET: 'preview-test-secret', AI_PREVIEW_ROOT: root }
  })
  let logs = ''
  worker.stdout.on('data', data => { logs += data })
  worker.stderr.on('data', data => { logs += data })
  const base = `http://127.0.0.1:${port}`
  const post = (route, body) => fetch(base + route, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-SkillLab-Worker-Key': 'preview-test-secret' },
    body: JSON.stringify(body)
  })
  let browser
  try {
    let ready = false
    for (let attempt = 0; attempt < 100; attempt++) {
      if (await fetch(base + '/health').then(r => r.ok).catch(() => false)) { ready = true; break }
      await new Promise(resolve => setTimeout(resolve, 100))
    }
    assert.ok(ready, logs)
    assert.equal((await fetch(base + '/internal/previews', { method: 'POST' })).status, 401)
    const payload = {
      previewId: id, sourceDirectory: directory, expiresAt: new Date(Date.now() + 60000).toISOString(),
      draft: {
        schemaVersion: 'skilllab.simulator-draft.v1',
        apiMocks: { rules: [
          { id: 'read', match: { method: 'GET', path: '/api/device' }, status: 200, body: { name: '{{state.name}}' } },
          { id: 'save', match: { method: 'PUT', path: '/api/device' }, status: 200, body: { name: '{{state.name}}' } }
        ] },
        stateModel: { initial: { name: 'Mock camera' }, transitions: [
          { onRuleId: 'save', operation: 'SET_FROM_REQUEST', path: 'name', requestPath: 'name' }
        ] }
      }
    }
    assert.equal((await post('/internal/previews', { ...payload, sourceDirectory: root })).status, 400)
    const registered = await post('/internal/previews', payload)
    assert.equal(registered.status, 201)
    const preview = await registered.json()
    assert.match(preview.previewUrl, /\/__skilllab\/start$/)
    assert.equal((await fetch(base + '/index.html')).status, 404)
    browser = await chromium.launch({ headless: true })
    const page = await browser.newPage()
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    await page.goto(preview.previewUrl)
    await page.waitForFunction(() => document.body?.dataset.device === 'Mock camera')
    assert.equal(await page.evaluate(() => Boolean(navigator.serviceWorker.controller)), true)
    assert.deepEqual(await page.evaluate(async () => {
      await fetch('/api/device', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({name:'Updated camera'}) })
      return fetch('/api/device').then(r => r.json())
    }), { name: 'Updated camera' })
    await page.reload()
    await page.waitForFunction(() => document.body?.dataset.device === 'Updated camera')
    const missing = await page.evaluate(async () => {
      const response = await fetch('/api/missing')
      return { status: response.status, body: await response.json() }
    })
    assert.equal(missing.status, 501)
    assert.equal(missing.body.ok, false)
    const headers = await page.evaluate(async () => {
      const response = await fetch('/__skilllab/config.json')
      return response.headers.get('Content-Security-Policy')
    })
    assert.match(headers, /connect-src 'self'/)
    assert.deepEqual(errors, [])
    assert.equal((await post('/internal/previews/' + id + '/close', {})).status, 200)
    assert.equal((await fetch(base + '/', { headers: { Host: `${id}.localhost:${port}` } })).status, 404)
  } finally {
    await browser?.close()
    worker.kill()
    await new Promise(resolve => worker.exitCode !== null ? resolve() : worker.once('exit', resolve))
    await rm(root, { recursive: true, force: true })
  }
})

test('v2 preview executes conditional write and read-back in the browser', { timeout: 60000 }, async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'skilllab-v2-'))
  const id = 'b'.repeat(32)
  await mkdir(path.join(root, id))
  await writeFile(path.join(root, id, 'index.html'), '<!doctype html><html><head></head><body>Device</body></html>')
  const behavior = JSON.parse(await readFile(new URL('../../schemas/examples/simulator-behavior-v2.example.json', import.meta.url)))
  const port = 33000 + Math.floor(Math.random() * 10000)
  const worker = spawn(process.execPath, ['src/server.js'], { cwd: new URL('..', import.meta.url), windowsHide: true,
    env: { ...process.env, PORT: String(port), AI_WORKER_SHARED_SECRET: 'preview-test-secret', AI_PREVIEW_ROOT: root } })
  const base = `http://127.0.0.1:${port}`
  let browser
  try {
    let ready = false
    for (let attempt = 0; attempt < 100; attempt++) {
      if (await fetch(base + '/health').then(r => r.ok).catch(() => false)) { ready = true; break }
      await new Promise(resolve => setTimeout(resolve, 100))
    }
    assert.ok(ready)
    const created = await fetch(base + '/internal/previews', { method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-SkillLab-Worker-Key': 'preview-test-secret' },
      body: JSON.stringify({ previewId: id, sourceDirectory: path.join(root, id),
        expiresAt: new Date(Date.now() + 60000).toISOString(), draft: behavior }) })
    assert.equal(created.status, 201, await created.text().catch(() => ''))
    browser = await chromium.launch({ headless: true })
    const page = await browser.newPage()
    await page.goto(`http://${id}.localhost:${port}/__skilllab/start`)
    await page.waitForFunction(() => Boolean(navigator.serviceWorker.controller))
    const results = await page.evaluate(async () => {
      const read = () => fetch('/api/config').then(response => response.json())
      const initial = await read()
      const saved = await fetch('/api/config', { method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: true }) }).then(response => response.json())
      return { initial, saved, after: await read() }
    })
    assert.equal(results.initial.enabled, false)
    assert.equal(results.saved.enabled, true)
    assert.equal(results.after.enabled, true)
  } finally {
    await browser?.close()
    worker.kill()
    await new Promise(resolve => worker.exitCode !== null ? resolve() : worker.once('exit', resolve))
    await rm(root, { recursive: true, force: true })
  }
})
