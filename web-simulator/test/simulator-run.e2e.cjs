const assert = require('node:assert/strict')
const { chromium } = require('../../ai-worker/node_modules/playwright')

;(async () => {
  const browser = await chromium.launch({ headless: true })
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
  let loggedIn = false
  let nextRun = 1
  const ended = []
  let active = null
  let failEnd = true
  await page.route('**/api/simulator/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const send = (status, body) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (path.endsWith('/auth/me')) return send(loggedIn ? 200 : 401, loggedIn ? { userId: 'maker' } : { error: 'Login required' })
    if (path.endsWith('/auth/login')) { loggedIn = true; return send(200, { userId: 'maker' }) }
    if (path.endsWith('/runs') && route.request().method() === 'POST') {
      active = { runId: 'run-' + nextRun++, versionId: 'version', runUrl: 'http://example.invalid/start', expiresAt: new Date(Date.now() + 600000).toISOString(), heartbeatMs: 30000 }
      return send(200, active)
    }
    if (path.endsWith('/end')) {
      if (failEnd) { failEnd = false; return send(503, { error: 'Worker unavailable' }) }
      ended.push(active?.runId); active = null; return route.fulfill({ status: 204 })
    }
    if (path.endsWith('/heartbeat')) return send(200, { expiresAt: new Date(Date.now() + 600000).toISOString() })
    if (path.endsWith('/runs/' + active?.runId)) return send(200, active)
    return send(404, { error: 'Unknown run' })
  })
  try {
    await page.goto('http://127.0.0.1:5173/simulators/version')
    await page.getByRole('heading', { name: '制作者登录' }).waitFor()
    assert.equal(await page.getByRole('button', { name: '启动模拟器' }).count(), 0)
    await page.getByPlaceholder('用户名').fill('maker')
    await page.getByPlaceholder('密码').fill('secret')
    await page.getByRole('button', { name: '登录' }).click()
    await page.getByRole('button', { name: '启动模拟器' }).click()
    assert.equal(await page.locator('iframe').getAttribute('src'), 'http://example.invalid/start')
    assert.equal(await page.getByText('正在加载设备页面').count(), 1)
    await page.evaluate(() => window.postMessage({ type: 'SKILLLAB_PREVIEW_EVENT', previewId: 'run-1',
      schemaVersion: 'skilllab.simulator-behavior.v2', event: 'SERVICE_WORKER_READY', detail: { controlled: true } }, '*'))
    assert.equal(await page.getByText('正在加载设备页面').count(), 1)
    await page.reload()
    await page.locator('iframe').waitFor()
    assert.equal(await page.locator('iframe').getAttribute('src'), 'http://example.invalid/start')
    await page.getByRole('button', { name: '结束运行' }).click()
    await page.getByRole('alert').getByText('Worker unavailable').waitFor()
    assert.equal(await page.locator('iframe').count(), 1)
    await page.getByRole('button', { name: '结束运行' }).click()
    await page.locator('iframe').waitFor({ state: 'detached' })
    assert.deepEqual(ended, ['run-1'])
    await page.getByRole('button', { name: '启动模拟器' }).click()
    assert.equal(await page.locator('iframe').getAttribute('src'), 'http://example.invalid/start')
    assert.equal(active.runId, 'run-2')
    assert.equal(await page.getByText(/AI|评分|练习/).count(), 0)
    console.log('Simulator run shell E2E passed')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
