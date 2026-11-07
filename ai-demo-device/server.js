import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const publicDir = path.join(here, 'public')
const host = process.env.DEMO_DEVICE_HOST || '127.0.0.1'
const port = Number(process.env.DEMO_DEVICE_PORT || 19090)

const device = {
  name: '前门网络摄像机',
  model: 'SL-IPC-4200',
  ip: '192.168.10.42',
  resolution: '1920 × 1080',
  fps: 25,
  bitrate: 4096,
  firmware: 'V3.8.2-demo',
  status: '在线',
}

let session = null
const recordings = [
  { id: 'rec-1001', name: '入口区域 · 上午', startTime: '2026-11-04 08:15:22', duration: 126, size: '24.8 MB', playCount: 0 },
  { id: 'rec-1002', name: '入口区域 · 午间', startTime: '2026-11-04 12:42:08', duration: 94, size: '18.1 MB', playCount: 0 },
  { id: 'rec-1003', name: '装卸区 · 傍晚', startTime: '2026-11-03 17:36:51', duration: 203, size: '39.6 MB', playCount: 0 },
]

function sendJson(res, status, value) {
  const body = JSON.stringify(value)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    'content-length': Buffer.byteLength(body),
  })
  res.end(body)
}

async function readJson(req) {
  let raw = ''
  for await (const chunk of req) {
    raw += chunk
    if (raw.length > 32 * 1024) throw new Error('request body too large')
  }
  return raw ? JSON.parse(raw) : {}
}

function requireSession(res) {
  if (session) return true
  sendJson(res, 401, { ok: false, message: '请先登录演示设备' })
  return false
}

async function handleApi(req, res, url) {
  const route = url.pathname
  const method = req.method || 'GET'

  if (route === '/api/session' && method === 'GET') {
    return session
      ? sendJson(res, 200, { ok: true, session })
      : sendJson(res, 401, { ok: false, message: '当前没有登录会话' })
  }

  if (route === '/api/login' && method === 'POST') {
    const body = await readJson(req)
    if (body.username !== 'admin' || body.password !== 'demo123') {
      return sendJson(res, 401, { ok: false, message: '账号或密码错误，请使用演示账号' })
    }
    session = {
      token: `demo-${Date.now().toString(36)}`,
      user: { name: 'admin', role: 'operator' },
      loginAt: new Date().toISOString(),
    }
    return sendJson(res, 200, { ok: true, session })
  }

  if (route === '/api/logout' && method === 'POST') {
    if (!requireSession(res)) return
    session = null
    return sendJson(res, 200, { ok: true })
  }

  if (!requireSession(res)) return

  if (route === '/api/device' && method === 'GET') {
    return sendJson(res, 200, { ok: true, device })
  }

  if (route === '/api/device' && method === 'PUT') {
    const patch = await readJson(req)
    const editable = ['name', 'resolution', 'fps', 'bitrate']
    for (const key of editable) {
      if (Object.hasOwn(patch, key)) device[key] = patch[key]
    }
    return sendJson(res, 200, { ok: true, device, message: '设备参数已保存（演示）' })
  }

  if (route === '/api/recordings' && method === 'GET') {
    const range = url.searchParams.get('range') || '24h'
    return sendJson(res, 200, {
      ok: true,
      range,
      total: recordings.length,
      recordings,
    })
  }

  const playMatch = route.match(/^\/api\/recordings\/([^/]+)\/play$/)
  if (playMatch && method === 'POST') {
    const recording = recordings.find((item) => item.id === playMatch[1])
    if (!recording) return sendJson(res, 404, { ok: false, message: '录像不存在' })
    recording.playCount += 1
    recording.lastPlayedAt = new Date().toISOString()
    return sendJson(res, 200, {
      ok: true,
      message: `正在播放：${recording.name}`,
      recording,
      stream: { type: 'image-sequence-demo', frameRate: 12 },
    })
  }

  return sendJson(res, 404, { ok: false, message: `未实现的演示接口：${method} ${route}` })
}

const contentTypes = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.json': 'application/json; charset=utf-8',
}

async function serveStatic(req, res, pathname) {
  const decoded = decodeURIComponent(pathname)
  const relative = decoded === '/' ? 'index.html' : decoded.replace(/^\/+/, '')
  const filePath = path.resolve(publicDir, relative)
  if (filePath !== publicDir && !filePath.startsWith(publicDir + path.sep)) {
    res.writeHead(403).end('Forbidden')
    return
  }

  try {
    const data = await readFile(filePath)
    res.writeHead(200, {
      'content-type': contentTypes[path.extname(filePath)] || 'application/octet-stream',
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
      'referrer-policy': 'no-referrer',
    })
    if (req.method === 'HEAD') return res.end()
    res.end(data)
  } catch {
    res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' }).end('Not found')
  }
}

const server = createServer(async (req, res) => {
  let url
  try {
    url = new URL(req.url || '/', `http://${host}:${port}`)
  } catch {
    return sendJson(res, 400, { ok: false, message: '无效请求地址' })
  }

  if (url.pathname === '/health') {
    return sendJson(res, 200, { ok: true, service: 'skilllab-demo-device' })
  }

  try {
    if (url.pathname.startsWith('/api/')) return await handleApi(req, res, url)
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { allow: 'GET, HEAD' }).end()
      return
    }
    return await serveStatic(req, res, url.pathname)
  } catch (error) {
    const status = error.message === 'request body too large' ? 413 : 400
    return sendJson(res, status, { ok: false, message: status === 413 ? error.message : '请求数据格式错误' })
  }
})

server.listen(port, host, () => {
  console.log(`SkillLab 演示设备已启动：http://${host}:${port}`)
  console.log('演示账号：admin / demo123')
})

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => process.exit(0)))
}
