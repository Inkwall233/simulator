import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createServer } from 'node:http'
import { chromium } from 'playwright'
import { readFile } from 'node:fs/promises'

test('run state persists and concurrent writes serialize without false success', { timeout: 30000 }, async () => {
  const source = await readFile(new URL('../src/preview-runtime-store.js', import.meta.url), 'utf8')
  const server = createServer((_request, response) => response.writeHead(200, { 'Content-Type': 'text/html' }).end('<!doctype html><title>State</title>'))
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const browser = await chromium.launch()
  try {
    const page = await browser.newPage()
    await page.goto(`http://127.0.0.1:${server.address().port}/`)
    const result = await page.evaluate(async source => {
      const module = await import(URL.createObjectURL(new Blob([source], { type: 'text/javascript' })))
      const { withRunState } = module
      const increment = state => ({ response: { status: 200 }, nextState: { count: state.count + 1 } })
      const writes = await Promise.all(Array.from({ length: 20 }, () => withRunState('run-1', { count: 0 }, increment)))
      const read = await withRunState('run-1', { count: 0 }, state => ({ response: { count: state.count }, nextState: state }))
      let rejected = false
      try { await withRunState('run-1', { count: 0 }, () => { throw new Error('render failed') }) }
      catch { rejected = true }
      let storageRejected = false
      try { await withRunState('run-1', { count: 0 }, () => ({ response: { status: 200 }, nextState: { impossible: () => 1 } })) }
      catch { storageRejected = true }
      const after = await withRunState('run-1', { count: 0 }, state => ({ response: { count: state.count }, nextState: state }))
      const other = await withRunState('run-2', { count: 0 }, state => ({ response: { count: state.count }, nextState: state }))
      return { revisions: writes.map(item => item.revision), read: read.response.count, after: after.response.count, other: other.response.count, rejected, storageRejected }
    }, source)
    assert.deepEqual(result.revisions.sort((a, b) => a - b), Array.from({ length: 20 }, (_, index) => index + 1))
    assert.equal(result.read, 20)
    assert.equal(result.after, 20)
    assert.equal(result.other, 0)
    assert.equal(result.rejected, true)
    assert.equal(result.storageRejected, true)
    await page.reload()
    assert.equal(await page.evaluate(async source => {
      const { withRunState } = await import(URL.createObjectURL(new Blob([source], { type: 'text/javascript' })))
      return (await withRunState('run-1', { count: 0 }, state => ({ response: state.count, nextState: state }))).response
    }, source), 20)
  } finally {
    await browser.close()
    await new Promise(resolve => server.close(resolve))
  }
})
