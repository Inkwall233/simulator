import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { validateBehaviorV2 } from './behavior-contract.js'
import { evaluateBehavior } from './behavior-interpreter.js'

export const validatorRuntimeVersion = 'behavior-runtime-v2'
const runtimeFiles = ['simulator-validator.js', 'behavior-contract.js', 'behavior-interpreter.js',
  'preview-mock-sw.js', 'preview-runtime-store.js', 'preview-bootstrap.js', 'server.js']
const runtimeSources = await Promise.all(runtimeFiles.map(name => readFile(new URL(name, import.meta.url))))
export const validatorRuntimeHash = createHash('sha256').update(validatorRuntimeVersion)
  .update(Buffer.concat(runtimeSources)).digest('hex')

function equal(actual, expected) { return JSON.stringify(actual) === JSON.stringify(expected) }
function issue(code, message, caseId = null) { return { code, message, caseId } }

export function validateSimulator(input) {
  const behavior = validateBehaviorV2(input.behavior)
  const blockers = [], warnings = [], cases = []
  const hitRules = new Set()
  const declared = behavior.verificationCases
  const knownRules = new Set(behavior.rules.map(rule => rule.id))
  const caseIds = new Set()
  if (!declared.length) blockers.push(issue('NO_ASSERTIONS', '制作者尚未确认验证断言'))
  if (declared.length > 50) throw new Error('Too many verification cases')
  for (const item of declared) {
    const id = typeof item?.id === 'string' ? item.id : ''
    if (!id || caseIds.has(id) || Object.keys(item).some(key => !['id', 'steps', 'expectedState'].includes(key))
        || !Array.isArray(item.steps) || !item.steps.length || item.steps.length > 50) {
      blockers.push(issue('INVALID_CASE', '验证用例缺少请求步骤', id)); continue
    }
    caseIds.add(id)
    let state = structuredClone(behavior.initialState)
    const steps = []
    for (const step of item.steps) {
      if (!step || Object.keys(step).some(key => !['method', 'path', 'query', 'body', 'reloadBefore', 'expectedStatus', 'expectedBody', 'expectedRuleId'].includes(key))
          || (step.reloadBefore !== undefined && step.reloadBefore !== true)
          || typeof step.method !== 'string' || typeof step.path !== 'string'
          || !Number.isInteger(step.expectedStatus) || step.expectedStatus < 200 || step.expectedStatus >= 400
          || !knownRules.has(step.expectedRuleId) || !Object.hasOwn(step, 'expectedBody')) {
        blockers.push(issue('INVALID_STEP', '步骤缺少请求或响应断言', id)); break
      }
      try {
        const result = evaluateBehavior(behavior, { method: step.method, path: step.path,
          query: step.query || {}, body: step.body ?? null }, state)
        const passed = result.response.status === step.expectedStatus && equal(result.response.body, step.expectedBody)
          && result.ruleId === step.expectedRuleId
        steps.push({ method: step.method, path: step.path, status: result.response.status, ruleId: result.ruleId, passed })
        if (result.ruleId) hitRules.add(result.ruleId)
        if (!passed) blockers.push(issue('ASSERTION_FAILED', `响应断言失败：${step.method} ${step.path}`, id))
        state = result.nextState
      } catch (error) {
        blockers.push(issue('RUNTIME_ERROR', String(error.message).slice(0, 200), id)); break
      }
    }
    if (!Object.hasOwn(item, 'expectedState')) blockers.push(issue('MISSING_STATE_ASSERTION', '用例缺少最终状态断言', id))
    else if (!equal(state, item.expectedState)) blockers.push(issue('STATE_MISMATCH', '最终状态与制作者断言不符', id))
    cases.push({ id, steps, passed: !blockers.some(blocker => blocker.caseId === id) })
  }
  for (const id of behavior.scope.criticalRuleIds) {
    if (!hitRules.has(id)) blockers.push(issue('CRITICAL_UNCOVERED', `关键规则未被验证：${id}`))
  }
  const hasWrite = behavior.scope.criticalRuleIds.some(id => behavior.rules.some(rule => rule.id === id && !['GET', 'HEAD'].includes(rule.match.method)))
  if (hasWrite && !declared.some(item => Array.isArray(item?.steps) && item.steps.some(step => step.reloadBefore === true))) {
    blockers.push(issue('PERSISTENCE_UNVERIFIED', '关键写入链路缺少刷新后读回断言'))
  }
  for (const rule of behavior.rules) {
    if (rule.evidence.type === 'INFERRED_PENDING') {
      const finding = issue('INFERENCE_PENDING', `推断仍待人工确认：${rule.id}`)
      if (behavior.scope.criticalRuleIds.includes(rule.id)) blockers.push(finding)
      else warnings.push(finding)
    }
    else if (rule.evidence.type === 'INFERRED_CONFIRMED') warnings.push(issue('INFERENCE_CONFIRMED', `已确认的推断：${rule.id}`))
  }
  return { status: blockers.length ? 'BLOCKED' : 'PASSED', runtimeVersion: validatorRuntimeVersion,
    runtimeHash: validatorRuntimeHash, blockers, warnings, cases, hitRuleIds: [...hitRules] }
}

export async function validateBrowserReplay(preview, browser, preflight = validateSimulator({ behavior: preview.draft })) {
  const report = structuredClone(preflight)
  if (report.blockers.length) return report
  const errors = []
  const external = []
  const origin = preview.origin
  for (const item of preview.draft.verificationCases) {
    if (!Array.isArray(item?.steps)) continue
    const context = await browser.newContext({ serviceWorkers: 'allow', acceptDownloads: false })
    const page = await context.newPage()
    let devicePageLoaded = false
    page.on('response', response => {
      if (response.url() === origin + '/' && response.request().isNavigationRequest()
          && response.status() === 200 && /text\/html/i.test(response.headers()['content-type'] || '')) {
        devicePageLoaded = true
      }
    })
    page.on('pageerror', error => errors.push(String(error.message).slice(0, 300)))
    page.on('console', message => {
      if (message.type() === 'error' && /Content Security Policy|Refused to connect/i.test(message.text())) {
        external.push(message.text().slice(0, 300))
      }
    })
    context.on('request', request => {
      if (new URL(request.url()).origin !== origin) external.push(request.url().slice(0, 300))
    })
    await context.route('**/*', route => {
      if (new URL(route.request().url()).origin !== origin) return route.abort()
      return route.continue()
    })
    try {
      await page.goto(origin + '/__skilllab/start', { waitUntil: 'domcontentloaded', timeout: 10000 })
      await page.waitForURL(origin + '/', { timeout: 10000 })
      await page.waitForLoadState('load', { timeout: 10000 })
      await page.waitForFunction(() => Boolean(navigator.serviceWorker.controller), { timeout: 10000 })
      if (!devicePageLoaded) {
        report.blockers.push(issue('PACKAGE_START_FAILED', '设备页面未加载为 HTML', item.id))
        continue
      }
      const actual = []
      for (const step of item.steps) {
        if (!step || typeof step.path !== 'string' || !step.path.startsWith('/')
            || !preview.draft.rules.some(rule => rule.id === step.expectedRuleId
              && rule.match.method === step.method && rule.match.path === step.path)) {
          report.blockers.push(issue('UNSAFE_STEP', '验证步骤未绑定批准的规则', item.id)); break
        }
        if (step.reloadBefore === true) {
          await page.reload({ waitUntil: 'domcontentloaded', timeout: 10000 })
          await page.waitForLoadState('load', { timeout: 10000 })
          await page.waitForFunction(() => Boolean(navigator.serviceWorker.controller), { timeout: 10000 })
        }
        const result = await page.evaluate(async step => {
          const query = new URLSearchParams(step.query || {}).toString()
          const url = step.path + (query ? '?' + query : '')
          const response = await fetch(url, { method: step.method, headers: step.body === undefined ? {} : { 'Content-Type': 'application/json' },
            body: step.body === undefined ? undefined : JSON.stringify(step.body) })
          const text = await response.text()
          let body; try { body = JSON.parse(text) } catch { body = text }
          return { status: response.status, body, ruleId: response.headers.get('X-SkillLab-Mock-Rule') }
        }, step)
        actual.push({ method: step.method, path: step.path, status: result.status,
          ruleId: result.ruleId, passed: result.status === step.expectedStatus
            && equal(result.body, step.expectedBody) && result.ruleId === step.expectedRuleId })
        if (!actual.at(-1).passed) report.blockers.push(issue('BROWSER_ASSERTION_FAILED', `浏览器响应不符：${step.method} ${step.path}`, item.id))
      }
      report.cases.push({ id: item.id + ':browser', steps: actual, passed: actual.every(step => step.passed) })
    } catch (error) {
      report.blockers.push(issue('PACKAGE_START_FAILED', String(error.message).slice(0, 300), item.id))
    } finally {
      await context.close()
    }
  }
  if (errors.length) report.blockers.push(issue('PAGE_ERROR', errors.join('; ')))
  if (external.length) report.blockers.push(issue('EXTERNAL_REQUEST', external.join('; ')))
  report.pageErrors = errors
  report.externalRequests = external
  report.status = report.blockers.length ? 'BLOCKED' : 'PASSED'
  return report
}
