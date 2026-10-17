/**
 * 仿真桥接脚本（由模拟器注入到设备页 iframe 内部运行）
 *
 * 提供三类"加工"能力，供宿主页面通过 window.__simBridge 调用：
 *   1. 练习指引 Overlay —— 在设备页上叠加分步高亮与提示，并可自动推进流程
 *   2. 跳过登录注入     —— 直接向设备页注入已建立的会话，绕过登录表单
 *   3. 插件劫持         —— 把设备原有"厂商解码插件"替换为 canvas 图片帧播放器
 *
 * 该脚本运行在设备页所在文档的上下文里，因此可以直接操作其 DOM、
 * 覆写其全局对象，无需跨文档坐标换算。
 */
;(function () {
  'use strict'

  if (window.__simBridge) return

  var originalPlugin = window.DeviceVendorPlugin || null
  var overlay = createOverlay()
  var currentPlayer = null

  // ================================================================ 样式
  var style = document.createElement('style')
  style.textContent =
    '.sim-overlay{position:fixed;inset:0;z-index:2147483000;pointer-events:none;' +
    "font-family:system-ui,-apple-system,'PingFang SC','Microsoft YaHei',sans-serif}" +
    '.sim-overlay-box{position:absolute;border:2px solid #2f9bff;border-radius:6px;' +
    'box-shadow:0 0 0 2000px rgba(8,12,18,.62);transition:left .18s ease,top .18s ease,width .18s ease,height .18s ease}' +
    '.sim-overlay-tip{position:absolute;width:330px;padding:12px 14px;border-radius:8px;background:#fff;' +
    'box-shadow:0 10px 28px rgba(4,10,18,.4);pointer-events:auto}' +
    '.sim-overlay-tip h4{margin:0 0 6px;font-size:14px;color:#12305a}' +
    '.sim-overlay-idx{float:right;color:#7b8794;font-size:12px;font-weight:400;padding-top:2px}' +
    '.sim-overlay-desc{margin:0;font-size:12.5px;line-height:1.6;color:#4a5765}' +
    '.sim-overlay-btns{display:flex;gap:8px;justify-content:flex-end;margin-top:10px}' +
    '.sim-overlay-btns button{height:26px;padding:0 12px;border-radius:4px;font-size:12px;cursor:pointer;' +
    'border:1px solid #cfd8e3;background:#fff;color:#3c4a58}' +
    '.sim-overlay-btns button.primary{background:#2f6feb;border-color:#2f6feb;color:#fff}' +
    '.sim-player{position:relative;width:100%;height:100%;background:#0b1016;display:flex;flex-direction:column}' +
    '.sim-player-canvas{width:100%;height:300px;display:block}' +
    '.sim-player-bar{display:flex;align-items:center;gap:8px;padding:8px 10px;background:#111a23;color:#c9d4e0;font-size:12px}' +
    '.sim-player-bar button{height:24px;padding:0 10px;border:1px solid #2c3a49;border-radius:4px;' +
    'background:#1b2733;color:#c9d4e0;font-size:12px;cursor:pointer}' +
    '.sim-player-bar button:hover{background:#243242}' +
    '.sim-player-bar input[type=range]{flex:1;accent-color:#2f9bff}' +
    '.sim-player-tag{padding:1px 8px;border-radius:3px;background:#1d4ed8;color:#fff;font-size:11px}'
  ;(document.head || document.documentElement).appendChild(style)

  // ================================================================ Overlay
  function createOverlay() {
    var root = null
    var box = null
    var tip = null
    var tipTitle = null
    var tipIdx = null
    var tipDesc = null
    var api = { active: false, idx: 0, steps: [] }

    function build() {
      if (root) return
      root = document.createElement('div')
      root.className = 'sim-overlay'

      box = document.createElement('div')
      box.className = 'sim-overlay-box'

      tip = document.createElement('div')
      tip.className = 'sim-overlay-tip'
      tipTitle = document.createElement('h4')
      tipTitle.appendChild(document.createTextNode(''))
      tipIdx = document.createElement('span')
      tipIdx.className = 'sim-overlay-idx'
      tipTitle.appendChild(tipIdx)
      tipDesc = document.createElement('p')
      tipDesc.className = 'sim-overlay-desc'

      var btns = document.createElement('div')
      btns.className = 'sim-overlay-btns'

      var prev = document.createElement('button')
      prev.textContent = '上一步'
      prev.addEventListener('click', function () {
        api.prev()
      })

      var next = document.createElement('button')
      next.className = 'primary'
      next.textContent = '下一步'
      next.addEventListener('click', function () {
        api.next()
      })

      var stop = document.createElement('button')
      stop.textContent = '结束指引'
      stop.addEventListener('click', function () {
        api.hide()
      })

      btns.appendChild(prev)
      btns.appendChild(next)
      btns.appendChild(stop)

      tip.appendChild(tipTitle)
      tip.appendChild(tipDesc)
      tip.appendChild(btns)

      root.appendChild(box)
      root.appendChild(tip)
      document.body.appendChild(root)

      window.addEventListener('resize', reposition)
    }

    function reposition() {
      if (!api.active) return
      layout()
    }

    function layout() {
      var step = api.steps[api.idx]
      if (!step) return
      var target = document.querySelector(step.selector)
      if (!target) return

      var rect = target.getBoundingClientRect()
      var pad = 6
      box.style.left = rect.left - pad + 'px'
      box.style.top = rect.top - pad + 'px'
      box.style.width = rect.width + pad * 2 + 'px'
      box.style.height = rect.height + pad * 2 + 'px'

      var tipW = 330
      var tipH = tip.offsetHeight || 130
      var left = Math.min(Math.max(rect.left, 8), window.innerWidth - tipW - 8)
      var top = rect.bottom + 12
      if (top + tipH > window.innerHeight - 8) {
        top = Math.max(8, rect.top - tipH - 12)
      }
      tip.style.left = left + 'px'
      tip.style.top = top + 'px'
    }

    function render() {
      var step = api.steps[api.idx]
      if (!step) return
      tipTitle.childNodes[0].nodeValue = step.title
      tipIdx.textContent = api.idx + 1 + ' / ' + api.steps.length
      tipDesc.textContent = step.desc || ''
      layout()
    }

    function goTo(i) {
      if (i < 0 || i >= api.steps.length) return Promise.resolve()
      api.idx = i
      var step = api.steps[i]
      var run =
        typeof step.enter === 'function'
          ? Promise.resolve().then(function () {
              return step.enter()
            })
          : Promise.resolve()
      return run.then(function () {
        render()
      })
    }

    api.start = function (steps) {
      api.steps = steps || api.steps
      api.idx = 0
      api.active = true
      build()
      root.style.display = ''
      return goTo(0)
    }
    api.next = function () {
      if (!api.active) return Promise.resolve()
      if (api.idx >= api.steps.length - 1) {
        api.hide()
        return Promise.resolve()
      }
      return goTo(api.idx + 1)
    }
    api.prev = function () {
      if (!api.active || api.idx <= 0) return Promise.resolve()
      return goTo(api.idx - 1)
    }
    api.hide = function () {
      api.active = false
      if (root) root.style.display = 'none'
    }
    api.reset = function () {
      api.hide()
      api.idx = 0
    }
    return api
  }

  // ================================================================ 练习指引步骤
  function guideSteps() {
    var da = window.__deviceApp
    return [
      {
        selector: '#login-user',
        title: '第 1 步 · 输入用户名',
        desc: '在用户名输入框填写演示账号 admin。',
        enter: function () {
          da.fillLogin('admin', '')
        },
      },
      {
        selector: '#login-pass',
        title: '第 2 步 · 输入密码',
        desc: '密码为 admin123，输入后点击下一步。',
        enter: function () {
          da.fillLogin('admin', 'admin123')
        },
      },
      {
        selector: '#login-btn',
        title: '第 3 步 · 提交登录',
        desc: '点击"登录"按钮，登录请求会被 Service Worker 拦截并返回模拟会话。',
        enter: function () {},
      },
      {
        selector: '#tab-live',
        title: '第 4 步 · 查看实时预览',
        desc: '登录成功后进入主界面，默认展示实时预览画面。',
        enter: function () {
          return da.submitLogin().then(function () {
            da.switchView('live')
          })
        },
      },
      {
        selector: '#tab-config',
        title: '第 5 步 · 查看设备配置',
        desc: '切换标签查看设备名称、分辨率等参数。',
        enter: function () {
          da.switchView('config')
        },
      },
      {
        selector: '#tab-playback',
        title: '第 6 步 · 进入录像回放',
        desc: '切换到录像回放标签，列表由 Mock 接口提供。',
        enter: function () {
          da.switchView('playback')
        },
      },
      {
        selector: '.rec-item',
        title: '第 7 步 · 选择录像回放',
        desc: '点击任意一条录像，设备页会调用厂商插件进行播放。',
        enter: function () {
          return da.playFirstRecording()
        },
      },
    ]
  }

  // ================================================================ 图片帧播放器
  function createFramePlayer(host, recording) {
    var FPS = 12
    var totalFrames = Math.max(1, Math.round((recording.duration || 60) * FPS))
    var frame = 0
    var playing = true
    var timer = null

    host.innerHTML = ''
    var wrap = document.createElement('div')
    wrap.className = 'sim-player'

    var canvas = document.createElement('canvas')
    canvas.className = 'sim-player-canvas'
    canvas.width = 640
    canvas.height = 360
    wrap.appendChild(canvas)

    var bar = document.createElement('div')
    bar.className = 'sim-player-bar'

    var playBtn = document.createElement('button')
    playBtn.textContent = '暂停'
    playBtn.addEventListener('click', function () {
      playing ? stop() : play()
    })

    var prevBtn = document.createElement('button')
    prevBtn.textContent = '◀ 帧'
    prevBtn.addEventListener('click', function () {
      stop()
      setFrame(frame - 1)
    })

    var nextBtn = document.createElement('button')
    nextBtn.textContent = '帧 ▶'
    nextBtn.addEventListener('click', function () {
      stop()
      setFrame(frame + 1)
    })

    var slider = document.createElement('input')
    slider.type = 'range'
    slider.min = '0'
    slider.max = String(totalFrames - 1)
    slider.value = '0'
    slider.addEventListener('input', function () {
      stop()
      setFrame(Number(slider.value))
    })

    var label = document.createElement('span')
    var tag = document.createElement('span')
    tag.className = 'sim-player-tag'
    tag.textContent = '图片帧模拟播放'

    bar.appendChild(playBtn)
    bar.appendChild(prevBtn)
    bar.appendChild(nextBtn)
    bar.appendChild(slider)
    bar.appendChild(label)
    bar.appendChild(tag)
    wrap.appendChild(bar)
    host.appendChild(wrap)

    var ctx = canvas.getContext('2d')

    function drawFrame() {
      var W = canvas.width
      var H = canvas.height
      var p = frame / totalFrames // 0..1 进度

      // 背景
      var bg = ctx.createLinearGradient(0, 0, 0, H * 0.7)
      bg.addColorStop(0, '#101d2e')
      bg.addColorStop(1, '#22384f')
      ctx.fillStyle = bg
      ctx.fillRect(0, 0, W, H)

      // 月亮
      ctx.beginPath()
      ctx.arc(W * 0.78, H * 0.2, 20, 0, Math.PI * 2)
      ctx.fillStyle = 'rgba(226,238,250,.9)'
      ctx.fill()

      // 地面
      ctx.fillStyle = '#0e161e'
      ctx.fillRect(0, H * 0.7, W, H * 0.3)

      // 建筑
      ctx.fillStyle = '#0a1219'
      ctx.fillRect(W * 0.1, H * 0.44, 66, H * 0.26)
      ctx.fillRect(W * 0.2, H * 0.52, 46, H * 0.18)
      ctx.fillRect(W * 0.66, H * 0.38, 58, H * 0.32)

      // 走动的人（位置随帧推进）
      var px = p * (W + 120) - 60
      ctx.fillStyle = 'rgba(150,190,220,.9)'
      ctx.fillRect(px, H * 0.55, 10, 26)
      ctx.beginPath()
      ctx.arc(px + 5, H * 0.55 - 6, 6, 0, Math.PI * 2)
      ctx.fill()

      // 画面噪声
      ctx.fillStyle = 'rgba(255,255,255,.03)'
      for (var i = 0; i < 40; i++) {
        ctx.fillRect(Math.random() * W, Math.random() * H, 1.5, 1.5)
      }

      // 帧号水印
      ctx.fillStyle = 'rgba(255,255,255,.16)'
      ctx.font = 'bold 44px monospace'
      ctx.textAlign = 'center'
      ctx.fillText('#' + frame, W / 2, H * 0.42)
      ctx.textAlign = 'left'

      // 信息
      ctx.fillStyle = 'rgba(230,240,250,.92)'
      ctx.font = '13px monospace'
      var start = new Date(recording.startTime.replace(/-/g, '/'))
      var cur = new Date(start.getTime() + (frame / FPS) * 1000)
      ctx.fillText(cur.toLocaleString('zh-CN', { hour12: false }), 12, H - 14)
      ctx.textAlign = 'right'
      ctx.fillText(recording.name + ' · ' + FPS + 'fps', W - 12, H - 14)
      ctx.textAlign = 'left'

      // 进度条
      ctx.fillStyle = 'rgba(255,255,255,.15)'
      ctx.fillRect(12, H - 26, W - 24, 3)
      ctx.fillStyle = '#2f9bff'
      ctx.fillRect(12, H - 26, (W - 24) * p, 3)
    }

    function refreshLabel() {
      slider.value = String(frame)
      label.textContent = '第 ' + frame + ' / ' + (totalFrames - 1) + ' 帧'
    }

    function setFrame(f) {
      frame = ((f % totalFrames) + totalFrames) % totalFrames
      drawFrame()
      refreshLabel()
    }

    function tick() {
      setFrame(frame + 1)
    }

    function play() {
      playing = true
      playBtn.textContent = '暂停'
      if (timer) clearInterval(timer)
      timer = setInterval(tick, 1000 / FPS)
    }

    function stop() {
      playing = false
      playBtn.textContent = '播放'
      if (timer) {
        clearInterval(timer)
        timer = null
      }
    }

    setFrame(0)
    play()

    return {
      pluginId: 'sim-framedec',
      destroy: function () {
        stop()
        host.innerHTML = ''
      },
    }
  }

  function hijackPlugin() {
    window.DeviceVendorPlugin = {
      id: 'sim-framedec',
      name: 'Simulator Frame Decoder',
      version: 'sim-1.0',
      kind: 'simulated',
      hijacked: true,
      create: function (host, recording) {
        currentPlayer = createFramePlayer(host, recording)
        return currentPlayer
      },
    }
  }

  function restorePlugin() {
    if (originalPlugin) window.DeviceVendorPlugin = originalPlugin
    currentPlayer = null
  }

  // ================================================================ 对外接口
  window.__simBridge = {
    version: 'sim-1.0',
    guideSteps: guideSteps,

    status: function () {
      var da = window.__deviceApp
      var a = da ? da.app : null
      return {
        swControlled: !!(
          navigator.serviceWorker && navigator.serviceWorker.controller
        ),
        hasSession: !!(a && a.session),
        user: a && a.session && a.session.user ? a.session.user.name : null,
        injected: !!(a && a.session && a.session.injected),
        deviceName: a && a.device ? a.device.name : null,
        deviceResolution: a && a.device ? a.device.resolution : null,
        deviceFps: a && a.device ? a.device.fps : null,
        recordingCount: a ? a.recordings.length : 0,
        playCount: a && a.recordings[0] ? a.recordings[0].playCount : 0,
        playing: a && a.currentRecording ? a.currentRecording.name : null,
        plugin: window.DeviceVendorPlugin ? window.DeviceVendorPlugin.id : null,
        hijacked: !!(
          window.DeviceVendorPlugin && window.DeviceVendorPlugin.hijacked
        ),
        view: a ? a.view : null,
        overlayActive: overlay.active,
        overlayStep: overlay.active ? overlay.idx + 1 : 0,
        overlayTotal: overlay.steps.length,
      }
    },

    reset: function () {
      overlay.reset()
      restorePlugin()
      return window.__deviceApp.reset()
    },

    skipLogin: function () {
      return window.__deviceApp.applyInjectedSession()
    },

    hijackPlayback: function () {
      hijackPlugin()
      return window.__deviceApp.playFirstRecording()
    },

    startGuide: function () {
      overlay.reset()
      restorePlugin()
      return window.__deviceApp.reset().then(function () {
        return overlay.start(guideSteps())
      })
    },

    guideNext: function () {
      return overlay.next()
    },
    guidePrev: function () {
      return overlay.prev()
    },
    guideStop: function () {
      return overlay.hide()
    },
  }
})()
