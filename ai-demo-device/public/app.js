(() => {
  'use strict'

  const $ = (selector) => document.querySelector(selector)
  const loginScreen = $('#login-screen')
  const deviceApp = $('#device-app')
  let device = null
  let recordings = []

  async function api(path, options = {}) {
    const response = await fetch(`/api${path}`, {
      method: options.method || 'GET',
      headers: options.body ? { 'content-type': 'application/json' } : undefined,
      body: options.body ? JSON.stringify(options.body) : undefined,
      cache: 'no-store',
    })
    const result = await response.json().catch(() => ({}))
    if (!response.ok) {
      const error = new Error(result.message || `请求失败 (${response.status})`)
      error.status = response.status
      throw error
    }
    return result
  }

  function showMessage(selector, message, kind = '') {
    const element = $(selector)
    element.textContent = message
    element.className = kind ? `inline-message ${kind}` : 'inline-message'
  }

  function formatTime() {
    return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date())
  }

  function showView(name) {
    document.querySelectorAll('.view-panel').forEach((panel) => {
      panel.hidden = panel.id !== `view-${name}`
    })
    document.querySelectorAll('.nav-item').forEach((item) => {
      item.classList.toggle('active', item.dataset.view === name)
    })
    const labels = { overview: '设备概览', settings: '设备配置', recordings: '录像回放' }
    $('#page-title').textContent = labels[name]
    if (name === 'recordings') void loadRecordings()
    if (name === 'settings' && device) fillSettings()
  }

  function renderDevice() {
    if (!device) return
    $('#side-device-name').textContent = device.name
    $('#metric-model').textContent = device.model
    $('#metric-resolution').textContent = device.resolution
    $('#metric-fps').textContent = device.fps
    $('#metric-firmware').textContent = device.firmware
    $('#detail-name').textContent = device.name
    $('#detail-ip').textContent = device.ip
    $('#detail-model').textContent = device.model
    $('#settings-firmware').textContent = device.firmware
    $('#recording-device-name').textContent = device.name
  }

  function fillSettings() {
    $('#setting-name').value = device.name
    $('#setting-resolution').value = device.resolution
    $('#setting-fps').value = device.fps
    $('#setting-bitrate').value = device.bitrate
  }

  function renderRecentRecordings() {
    const container = $('#recent-recordings')
    container.replaceChildren()
    recordings.slice(0, 2).forEach((recording) => {
      const row = document.createElement('div')
      row.className = 'recent-item'
      const icon = document.createElement('span')
      icon.className = 'file-icon'
      icon.textContent = '▶'
      const title = document.createElement('strong')
      title.textContent = recording.name
      const meta = document.createElement('span')
      meta.textContent = `${recording.startTime} · ${recording.duration} 秒`
      const info = document.createElement('div')
      info.className = 'recent-info'
      info.append(title, meta)
      const action = document.createElement('button')
      action.className = 'text-button'
      action.textContent = '回放'
      action.addEventListener('click', () => void playRecording(recording.id))
      row.append(icon, info, action)
      container.append(row)
    })
  }

  function renderRecordings() {
    const body = $('#recordings-body')
    body.replaceChildren()
    recordings.forEach((recording) => {
      const row = document.createElement('tr')
      const name = document.createElement('td')
      name.innerHTML = '<span class="recording-file-icon">▤</span>'
      const nameText = document.createElement('strong')
      nameText.textContent = recording.name
      name.append(nameText)
      for (const value of [recording.startTime, `${recording.duration} 秒`, recording.size, String(recording.playCount)]) {
        const cell = document.createElement('td')
        cell.textContent = value
        row.append(cell)
      }
      const actionCell = document.createElement('td')
      const play = document.createElement('button')
      play.className = 'button button-small button-primary'
      play.textContent = '播放'
      play.addEventListener('click', () => void playRecording(recording.id))
      actionCell.append(play)
      row.prepend(name)
      row.append(actionCell)
      body.append(row)
    })
  }

  async function loadRecordings() {
    try {
      const range = encodeURIComponent($('#recording-range').value)
      const result = await api(`/recordings?range=${range}`)
      recordings = result.recordings
      renderRecordings()
      renderRecentRecordings()
    } catch (error) {
      const row = document.createElement('tr')
      const cell = document.createElement('td')
      cell.colSpan = 6
      cell.className = 'empty-state'
      cell.textContent = error.message
      row.append(cell)
      $('#recordings-body').replaceChildren(row)
    }
  }

  async function loadDevice() {
    const result = await api('/device')
    device = result.device
    renderDevice()
    fillSettings()
  }

  async function enterDevice() {
    loginScreen.hidden = true
    deviceApp.hidden = false
    showView('overview')
    await loadDevice()
    await loadRecordings()
  }

  async function playRecording(id) {
    try {
      const result = await api(`/recordings/${encodeURIComponent(id)}/play`, { method: 'POST', body: {} })
      $('#playback-message').textContent = `${result.message} · 播放请求已记录`
      await loadRecordings()
    } catch (error) {
      $('#playback-message').textContent = error.message
    }
  }

  $('#login-form').addEventListener('submit', async (event) => {
    event.preventDefault()
    $('#login-error').textContent = ''
    const username = $('#login-user').value.trim()
    const password = $('#login-pass').value
    try {
      await api('/login', { method: 'POST', body: { username, password } })
      await enterDevice()
    } catch (error) {
      $('#login-error').textContent = error.message
    }
  })

  document.querySelectorAll('.nav-item').forEach((item) => {
    item.addEventListener('click', () => showView(item.dataset.view))
  })

  document.querySelectorAll('[data-goto]').forEach((button) => {
    button.addEventListener('click', () => showView(button.dataset.goto))
  })

  $('#settings-form').addEventListener('submit', async (event) => {
    event.preventDefault()
    const body = {
      name: $('#setting-name').value.trim(),
      resolution: $('#setting-resolution').value,
      fps: Number($('#setting-fps').value),
      bitrate: Number($('#setting-bitrate').value),
    }
    try {
      const result = await api('/device', { method: 'PUT', body })
      device = result.device
      renderDevice()
      showMessage('#settings-message', result.message, 'success')
    } catch (error) {
      showMessage('#settings-message', error.message, 'error')
    }
  })

  $('#reset-settings').addEventListener('click', async () => {
    try {
      await loadDevice()
      showMessage('#settings-message', '已恢复当前设备参数', 'success')
    } catch (error) {
      showMessage('#settings-message', error.message, 'error')
    }
  })

  $('#recording-range').addEventListener('change', () => void loadRecordings())
  $('#logout-button').addEventListener('click', async () => {
    try {
      await api('/logout', { method: 'POST', body: {} })
    } finally {
      device = null
      deviceApp.hidden = true
      loginScreen.hidden = false
      $('#login-pass').value = ''
    }
  })
  $('#scene-time').textContent = formatTime()

  api('/session').then(enterDevice).catch(() => {
    loginScreen.hidden = false
    deviceApp.hidden = true
  })
})()
