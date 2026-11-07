;(async function startSkillLabPreviewRuntime() {
  const configResponse = await fetch('/__skilllab/config.json', { cache: 'no-store' })
  if (!configResponse.ok) return
  const config = await configResponse.json()
  const parentOrigin = typeof config.parentOrigin === 'string' ? config.parentOrigin : ''

  function report(event, detail) {
    if (!parentOrigin || window.parent === window) return
    window.parent.postMessage({
      type: 'SKILLLAB_PREVIEW_EVENT',
      previewId: config.previewId,
      event,
      detail: detail || {},
      timestamp: new Date().toISOString()
    }, parentOrigin)
  }

  function reloadOnceWhenControlled() {
    if (navigator.serviceWorker.controller) {
      report('SERVICE_WORKER_READY', { controlled: Boolean(navigator.serviceWorker.controller) })
      if (window.location.pathname === '/__skilllab/start') window.location.replace('/')
      return
    }
    const reload = function () {
      if (window.location.pathname === '/__skilllab/start') window.location.replace('/')
      else window.location.reload()
    }
    navigator.serviceWorker.addEventListener('controllerchange', reload, { once: true })
    window.setTimeout(function () {
      if (navigator.serviceWorker.controller) reload()
    }, 1200)
  }

  if (!('serviceWorker' in navigator)) {
    report('SERVICE_WORKER_UNAVAILABLE')
    return
  }

  navigator.serviceWorker.addEventListener('message', function (message) {
    const data = message.data
    if (!data || data.type !== 'SKILLLAB_MOCK_EVENT') return
    report(data.event, data.detail)
  })

  try {
    await navigator.serviceWorker.register('/__skilllab/mock-sw.js', { scope: '/', type: 'module' })
    await navigator.serviceWorker.ready
    reloadOnceWhenControlled()
  } catch (error) {
    report('SERVICE_WORKER_ERROR', {
      message: String(error && error.message ? error.message : error).slice(0, 300)
    })
  }
})().catch(function () {
  // A package may run before its body exists. The preview still remains isolated.
})
