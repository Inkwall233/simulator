/**
 * 仿真 Service Worker —— 有状态接口 Mock
 *
 * 作用域限定在 /device-demo/，只拦截该路径下的 /device-demo/api/* 请求，
 * 其余请求（页面、脚本、样式）原样放行。
 *
 * 状态保存在 SW 的模块作用域里（登录会话 / 设备配置 / 录像播放次数），
 * 因此模拟出的接口是"有状态"的：登录一次后会话保持、改过的配置会留存、
 * 每次回放都会让 playCount 递增，直到注销或重载 SW。
 */
'use strict'

var API_PREFIX = '/device-demo/api/'

// -------------------------------------------------------------- 初始状态
var initialDevice = {
  name: 'ACME-IPC-01 前门',
  model: 'ACME-IPC-2100',
  resolution: '1920x1080',
  fps: 25,
  bitrate: 4096,
  timezone: 'UTC+08:00',
  firmware: 'V2.4.1'
}

var initialRecordings = [
  { id: 'rec-01', name: '前门 10-14 早班', startTime: '2026-10-14 08:00:12', duration: 92, size: '18.4 MB', codec: 'H.264', url: '/device-demo/media/rec-01.mp4', playCount: 0 },
  { id: 'rec-02', name: '前门 10-14 午间', startTime: '2026-10-14 12:30:05', duration: 143, size: '27.1 MB', codec: 'H.264', url: '/device-demo/media/rec-02.mp4', playCount: 0 },
  { id: 'rec-03', name: '前门 10-15 夜间', startTime: '2026-10-15 22:05:41', duration: 208, size: '39.6 MB', codec: 'H.265', url: '/device-demo/media/rec-03.mp4', playCount: 0 }
]

var state = {
  session: null,
  device: Object.assign({}, initialDevice),
  recordings: initialRecordings.map(function (r) {
    return Object.assign({}, r)
  })
}

function adminSession(injected) {
  return {
    token: 'sim-' + Math.random().toString(36).slice(2, 10),
    user: { name: 'admin', role: 'admin' },
    loginAt: new Date().toISOString(),
    injected: !!injected
  }
}

// -------------------------------------------------------------- 响应工具
function json(body, status) {
  return new Response(JSON.stringify(body), {
    status: status || 200,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }
  })
}

function delay(ms) {
  return new Promise(function (resolve) {
    setTimeout(resolve, ms)
  })
}

function requireSession() {
  if (!state.session) return json({ ok: false, message: '未登录或会话已失效' }, 401)
  return null
}

async function readBody(request) {
  try {
    return await request.json()
  } catch (e) {
    return {}
  }
}

// -------------------------------------------------------------- 路由
async function handleApi(request) {
  var url = new URL(request.url)
  var path = url.pathname.slice(API_PREFIX.length)
  var method = request.method.toUpperCase()

  await delay(90) // 模拟一点网络/设备处理耗时

  // 登录
  if (path === 'login' && method === 'POST') {
    var body = await readBody(request)
    if (body.username === 'admin' && body.password === 'admin123') {
      state.session = adminSession(false)
      return json({ ok: true, session: state.session })
    }
    return json({ ok: false, message: '用户名或密码错误' }, 401)
  }

  // 会话注入（跳过登录用的后门接口）
  if (path === 'inject-session' && method === 'POST') {
    state.session = adminSession(true)
    return json({ ok: true, session: state.session, injected: true })
  }

  // 注销
  if (path === 'logout' && method === 'POST') {
    state.session = null
    return json({ ok: true })
  }

  // 查询会话
  if (path === 'session' && method === 'GET') {
    if (!state.session) return json({ ok: false, message: '未登录' }, 401)
    return json({ ok: true, session: state.session })
  }

  // 设备配置读取
  if (path === 'device' && method === 'GET') {
    var denied = requireSession()
    if (denied) return denied
    return json({ ok: true, device: state.device })
  }

  // 设备配置写入（有状态：会保留修改）
  if (path === 'device' && method === 'PUT') {
    var denied2 = requireSession()
    if (denied2) return denied2
    var patch = await readBody(request)
    Object.assign(state.device, patch)
    return json({ ok: true, device: state.device })
  }

  // 录像列表
  if (path === 'recordings' && method === 'GET') {
    var denied3 = requireSession()
    if (denied3) return denied3
    return json({ ok: true, recordings: state.recordings })
  }

  // 录像播放计数（有状态：playCount 递增）
  var playMatch = path.match(/^recordings\/([^/]+)\/play$/)
  if (playMatch && method === 'POST') {
    var denied4 = requireSession()
    if (denied4) return denied4
    var rec = state.recordings.find(function (r) {
      return r.id === playMatch[1]
    })
    if (!rec) return json({ ok: false, message: '录像不存在' }, 404)
    rec.playCount += 1
    rec.lastPlayedAt = new Date().toISOString()
    return json({ ok: true, recording: rec })
  }

  return json({ ok: false, message: '未实现的接口: ' + method + ' ' + path }, 404)
}

// -------------------------------------------------------------- 生命周期
self.addEventListener('install', function () {
  self.skipWaiting()
})

self.addEventListener('activate', function (event) {
  event.waitUntil(self.clients.claim())
})

self.addEventListener('fetch', function (event) {
  var url = new URL(event.request.url)
  if (url.origin !== self.location.origin) return
  if (!url.pathname.startsWith(API_PREFIX)) return
  event.respondWith(handleApi(event.request))
})
