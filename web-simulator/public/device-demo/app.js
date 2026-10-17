/**
 * 设备操作页脚本（设备包自带，非仿真代码）
 *
 * 所有数据都通过 /device-demo/api/* 获取。在仿真环境里这些请求由
 * Service Worker 拦截并返回有状态的模拟数据。
 */
(function () {
  'use strict'

  var API = '/device-demo/api'

  var $ = function (sel) {
    return document.querySelector(sel)
  }

  var app = {
    session: null,
    device: null,
    recordings: [],
    currentRecording: null,
    player: null,
    view: 'live'
  }

  // ---------------------------------------------------------------- 请求
  function api(path, opts) {
    opts = opts || {}
    var init = { method: opts.method || 'GET' }
    if (opts.body) {
      init.headers = { 'Content-Type': 'application/json' }
      init.body = JSON.stringify(opts.body)
    }
    return fetch(API + path, init).then(function (res) {
      return res
        .json()
        .catch(function () {
          return null
        })
        .then(function (data) {
          return { ok: res.ok, status: res.status, data: data }
        })
    })
  }

  // ---------------------------------------------------------------- 视图
  function showLogin() {
    $('#main-view').hidden = true
    $('#login-view').hidden = false
    $('#login-error').textContent = ''
    $('#login-user').value = ''
    $('#login-pass').value = ''
  }

  function showMain() {
    $('#login-view').hidden = true
    $('#main-view').hidden = false
    var user = app.session && app.session.user ? app.session.user.name : ''
    $('#header-user').textContent = user ? '已登录：' + user : ''
  }

  function switchView(name) {
    app.view = name
    ;['live', 'config', 'playback'].forEach(function (v) {
      document.getElementById('view-' + v).hidden = v !== name
    })
    ;['live', 'config', 'playback'].forEach(function (v) {
      document.getElementById('tab-' + v).classList.toggle('active', v === name)
    })
  }

  // ---------------------------------------------------------------- 数据
  function loadDevice() {
    return api('/device').then(function (r) {
      if (!r.ok) return
      app.device = r.data.device
      $('#header-device-name').textContent = app.device.name
      $('#live-device').textContent = app.device.name + ' · ' + app.device.model + ' · ' + app.device.resolution
      $('#config-name').value = app.device.name
      $('#config-resolution').value = app.device.resolution
      $('#config-fps').value = app.device.fps
      $('#config-bitrate').value = app.device.bitrate
      $('#config-timezone').value = app.device.timezone
    })
  }

  function loadRecordings() {
    return api('/recordings').then(function (r) {
      if (!r.ok) return
      app.recordings = r.data.recordings
      renderRecordings()
    })
  }

  function renderRecordings() {
    var list = $('#rec-list')
    list.innerHTML = ''
    app.recordings.forEach(function (rec) {
      var li = document.createElement('li')
      li.className = 'rec-item'
      li.dataset.id = rec.id
      if (app.currentRecording && app.currentRecording.id === rec.id) {
        li.classList.add('active')
      }
      var name = document.createElement('div')
      name.className = 'rec-name'
      name.textContent = rec.name
      var meta = document.createElement('div')
      meta.className = 'rec-meta'
      meta.textContent = rec.startTime + ' · ' + rec.duration + 's · ' + rec.size + ' · 播放 ' + rec.playCount + ' 次'
      li.appendChild(name)
      li.appendChild(meta)
      li.addEventListener('click', function () {
        playRecording(rec)
      })
      list.appendChild(li)
    })
  }

  function playRecording(rec) {
    app.currentRecording = rec
    ;[].forEach.call(document.querySelectorAll('.rec-item'), function (el) {
      el.classList.toggle('active', el.dataset.id === rec.id)
    })

    // 上报一次播放（有状态：playCount 递增）
    api('/recordings/' + rec.id + '/play', { method: 'POST' }).then(function () {
      loadRecordings()
    })

    var host = $('#player-host')
    if (app.player && app.player.destroy) app.player.destroy()
    host.innerHTML = ''
    app.player = null

    var plugin = window.DeviceVendorPlugin
    if (!plugin || typeof plugin.create !== 'function') {
      host.textContent = '未找到可用的播放插件'
      return
    }
    app.player = plugin.create(host, rec)
  }

  function playFirstRecording() {
    var run = function () {
      switchView('playback')
      var first = app.recordings[0]
      if (first) playRecording(first)
    }
    if (app.recordings.length) return Promise.resolve(run())
    return loadRecordings().then(run)
  }

  // ---------------------------------------------------------------- 登录
  function fillLogin(user, pass) {
    if (typeof user === 'string') $('#login-user').value = user
    if (typeof pass === 'string') $('#login-pass').value = pass
  }

  function submitLogin() {
    var username = $('#login-user').value.trim()
    var password = $('#login-pass').value
    return api('/login', { method: 'POST', body: { username: username, password: password } }).then(
      function (r) {
        if (!r.ok) {
          $('#login-error').textContent = (r.data && r.data.message) || '登录失败'
          return false
        }
        $('#login-error').textContent = ''
        return applySession(r.data.session)
      }
    )
  }

  function applySession(session) {
    app.session = session
    showMain()
    switchView('live')
    return refresh()
  }

  function applyInjectedSession() {
    return api('/inject-session', { method: 'POST' }).then(function (r) {
      if (!r.ok) return false
      return applySession(r.data.session)
    })
  }

  function refresh() {
    return Promise.all([loadDevice(), loadRecordings()]).then(function () {})
  }

  function logout() {
    return api('/logout', { method: 'POST' }).then(function () {
      app.session = null
      app.currentRecording = null
      if (app.player && app.player.destroy) app.player.destroy()
      app.player = null
      $('#player-host').textContent = '请从左侧选择一条录像'
      showLogin()
    })
  }

  function reset() {
    switchView('live')
    return logout()
  }

  // ---------------------------------------------------------------- 事件
  function bindEvents() {
    $('#login-btn').addEventListener('click', function () {
      submitLogin()
    })
    $('#login-pass').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') submitLogin()
    })
    $('#logout-btn').addEventListener('click', function () {
      logout()
    })
    ;['live', 'config', 'playback'].forEach(function (v) {
      document.getElementById('tab-' + v).addEventListener('click', function () {
        switchView(v)
      })
    })
    $('#config-save').addEventListener('click', function () {
      var body = {
        name: $('#config-name').value.trim(),
        resolution: $('#config-resolution').value,
        fps: Number($('#config-fps').value),
        bitrate: Number($('#config-bitrate').value),
        timezone: $('#config-timezone').value
      }
      api('/device', { method: 'PUT', body: body }).then(function (r) {
        if (r.ok) {
          app.device = r.data.device
          $('#config-hint').textContent = '已保存（服务端状态已更新）'
          loadDevice()
          setTimeout(function () {
            $('#config-hint').textContent = ''
          }, 2000)
        }
      })
    })
  }

  // ---------------------------------------------------------------- 实时预览
  function startLive() {
    var canvas = $('#live-canvas')
    var ctx = canvas.getContext('2d')
    var W = canvas.width
    var H = canvas.height
    var t0 = Date.now()

    function draw() {
      var t = (Date.now() - t0) / 1000

      // 天空渐变
      var sky = ctx.createLinearGradient(0, 0, 0, H * 0.7)
      sky.addColorStop(0, '#0d1a2b')
      sky.addColorStop(1, '#1d3a55')
      ctx.fillStyle = sky
      ctx.fillRect(0, 0, W, H)

      // 月亮
      ctx.beginPath()
      ctx.arc(W * 0.8, H * 0.22, 22, 0, Math.PI * 2)
      ctx.fillStyle = 'rgba(226,238,250,.92)'
      ctx.fill()

      // 地面
      ctx.fillStyle = '#101820'
      ctx.fillRect(0, H * 0.7, W, H * 0.3)

      // 建筑剪影
      ctx.fillStyle = '#0a1219'
      ctx.fillRect(W * 0.08, H * 0.42, 70, H * 0.28)
      ctx.fillRect(W * 0.17, H * 0.5, 50, H * 0.2)
      ctx.fillRect(W * 0.62, H * 0.36, 60, H * 0.34)

      // 走动的人
      var px = ((t * 60) % (W + 120)) - 60
      ctx.fillStyle = 'rgba(150,190,220,.85)'
      ctx.fillRect(px, H * 0.55, 10, 26)
      ctx.beginPath()
      ctx.arc(px + 5, H * 0.55 - 6, 6, 0, Math.PI * 2)
      ctx.fill()

      // 扫描线
      var sy = (t * 120) % H
      ctx.fillStyle = 'rgba(120,200,255,.07)'
      ctx.fillRect(0, sy, W, 2)

      // 时间戳与信息
      ctx.fillStyle = 'rgba(230,240,250,.9)'
      ctx.font = '13px monospace'
      var stamp = new Date().toLocaleString('zh-CN', { hour12: false })
      ctx.fillText(stamp, 12, H - 14)
      ctx.textAlign = 'right'
      var dev = app.device ? app.device.name + ' ' + app.device.resolution + ' @' + app.device.fps + 'fps' : '—'
      ctx.fillText(dev, W - 12, H - 14)
      ctx.textAlign = 'left'

      requestAnimationFrame(draw)
    }
    requestAnimationFrame(draw)
  }

  // ---------------------------------------------------------------- 初始化
  function init() {
    bindEvents()
    startLive()
    switchView('live')
    api('/session').then(function (r) {
      if (r.ok && r.data.session) {
        applySession(r.data.session)
      } else {
        showLogin()
      }
    })
  }

  // 供仿真桥接脚本调用
  window.__deviceApp = {
    app: app,
    api: api,
    applySession: applySession,
    applyInjectedSession: applyInjectedSession,
    refresh: refresh,
    reset: reset,
    logout: logout,
    fillLogin: fillLogin,
    submitLogin: submitLogin,
    switchView: switchView,
    playRecording: playRecording,
    playFirstRecording: playFirstRecording
  }

  init()
})()
