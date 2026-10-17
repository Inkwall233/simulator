<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'

type Mode = 'original' | 'guide' | 'skipLogin' | 'hijack'

interface SimStatus {
  swControlled?: boolean
  hasSession?: boolean
  user?: string | null
  injected?: boolean
  deviceName?: string | null
  deviceResolution?: string | null
  deviceFps?: number | null
  recordingCount?: number
  playCount?: number
  playing?: string | null
  plugin?: string | null
  hijacked?: boolean
  view?: string | null
  overlayActive?: boolean
  overlayStep?: number
  overlayTotal?: number
}

const modes: { value: Mode; label: string; desc: string }[] = [
  {
    value: 'original',
    label: '原始模式',
    desc: '不做任何加工，设备包按原样运行',
  },
  {
    value: 'guide',
    label: '练习指引',
    desc: 'Overlay 分步高亮，引导完成一次操作',
  },
  {
    value: 'skipLogin',
    label: '跳过登录',
    desc: '注入会话，绕过登录直接进入主界面',
  },
  {
    value: 'hijack',
    label: '劫持回放',
    desc: '替换厂商插件，用图片帧模拟录像回放',
  },
]

const DEVICE_URL = '/device-demo/index.html'

const mode = ref<Mode>('original')
const frameSrc = ref('')
const iframeEl = ref<HTMLIFrameElement | null>(null)
const bridgeReady = ref(false)
const swState = ref<'idle' | 'registering' | 'ready' | 'unsupported' | 'error'>(
  'idle',
)
const swMessage = ref('等待注册')
const status = ref<SimStatus>({})
const logs = ref<string[]>([])

const bridgeRef = shallowRef<any>(null)
let statusTimer: number | null = null
let expectingLoad = false

const swStateText = computed(() => {
  switch (swState.value) {
    case 'registering':
      return '注册中…'
    case 'ready':
      return '已就绪'
    case 'unsupported':
      return '不支持'
    case 'error':
      return '注册失败'
    default:
      return '未注册'
  }
})

function log(msg: string) {
  const t = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  logs.value.unshift(`${t}  ${msg}`)
  if (logs.value.length > 60) logs.value.length = 60
}

function getWin(): any {
  return iframeEl.value?.contentWindow ?? null
}

// 本页在 /runtime，SW 作用域是 /device-demo/，两者 scope 不匹配，
// 因此 navigator.serviceWorker.ready 不会为该注册兑现，
// 必须直接等待 register() 返回的 registration 进入 activated。
function waitForActivated(reg: ServiceWorkerRegistration): Promise<void> {
  if (reg.active) return Promise.resolve()
  const sw = reg.installing || reg.waiting
  if (!sw) return Promise.resolve()
  return new Promise((resolve) => {
    const timer = window.setInterval(() => {
      if (reg.active) {
        window.clearInterval(timer)
        resolve()
      }
    }, 100)
    sw.addEventListener('statechange', () => {
      if (sw.state === 'activated') {
        window.clearInterval(timer)
        resolve()
      }
    })
  })
}

async function ensureServiceWorker() {
  if (!('serviceWorker' in navigator)) {
    swState.value = 'unsupported'
    swMessage.value = '当前浏览器不支持 Service Worker'
    log('浏览器不支持 Service Worker')
    return
  }
  swState.value = 'registering'
  try {
    const reg = await navigator.serviceWorker.register(
      '/device-demo/mock-sw.js',
      {
        scope: '/device-demo/',
      },
    )
    await waitForActivated(reg)
    swState.value = 'ready'
    swMessage.value = `作用域 ${reg.scope}`
    log('Service Worker 已就绪，接口 Mock 覆盖 /device-demo/api/*')
  } catch (e: any) {
    swState.value = 'error'
    swMessage.value = e?.message ?? String(e)
    log('Service Worker 注册失败：' + swMessage.value)
  }
}

function reloadDevice() {
  bridgeReady.value = false
  bridgeRef.value = null
  status.value = {}
  expectingLoad = true
  frameSrc.value = `${DEVICE_URL}?t=${Date.now()}`
  log('加载设备静态包')
}

function injectBridge() {
  const win = getWin()
  const doc = iframeEl.value?.contentDocument
  if (!win || !doc) return
  if (win.__simBridge) {
    bridgeRef.value = win.__simBridge
    bridgeReady.value = true
    void applyMode(mode.value)
    return
  }
  const s = doc.createElement('script')
  s.src = '/sim/sim-bridge.js'
  doc.head.appendChild(s)

  const started = Date.now()
  const timer = window.setInterval(() => {
    if (win.__simBridge) {
      window.clearInterval(timer)
      bridgeRef.value = win.__simBridge
      bridgeReady.value = true
      log('桥接脚本已注入，可开始加工设备页')
      void applyMode(mode.value)
    } else if (Date.now() - started > 8000) {
      window.clearInterval(timer)
      log('桥接脚本注入超时')
    }
  }, 120)
}

function onIframeLoad() {
  if (!expectingLoad) return
  expectingLoad = false
  injectBridge()
}

async function applyMode(m: Mode) {
  mode.value = m
  const b = bridgeRef.value
  if (!b) return
  try {
    if (m === 'original') {
      await b.reset()
      log('模式：原始（未加工）')
    } else if (m === 'guide') {
      await b.startGuide()
      log('模式：练习指引（分步高亮）')
    } else if (m === 'skipLogin') {
      await b.reset()
      await b.skipLogin()
      log('模式：跳过登录（注入会话）')
    } else if (m === 'hijack') {
      await b.reset()
      await b.skipLogin()
      await b.hijackPlayback()
      log('模式：劫持回放（图片帧模拟播放）')
    }
    status.value = b.status()
  } catch (e: any) {
    log('切换模式失败：' + (e?.message ?? e))
  }
}

async function guideNext() {
  await bridgeRef.value?.guideNext()
  status.value = bridgeRef.value?.status()
}
async function guidePrev() {
  await bridgeRef.value?.guidePrev()
  status.value = bridgeRef.value?.status()
}
async function guideStop() {
  await bridgeRef.value?.guideStop()
  status.value = bridgeRef.value?.status()
}

onMounted(async () => {
  await ensureServiceWorker()
  reloadDevice()
  statusTimer = window.setInterval(() => {
    const b = bridgeRef.value
    if (!b) return
    try {
      status.value = b.status()
    } catch {
      /* 设备页可能正在重载 */
    }
  }, 800)
})

onBeforeUnmount(() => {
  if (statusTimer) window.clearInterval(statusTimer)
})
</script>

<template>
  <section class="page runtime">
    <h1>仿真运行</h1>
    <p class="lead">
      左侧切换加工模式，右侧是被加工的设备静态包（由 iframe 加载
      <code>/device-demo/</code>）。
    </p>

    <div class="runtime-layout">
      <aside class="panel">
        <div class="panel-block">
          <h2>Service Worker</h2>
          <p class="badge" :class="swState">{{ swStateText }}</p>
          <p class="hint">{{ swMessage }}</p>
          <p class="hint">
            设备页受控：{{ status.swControlled ? '是' : '否' }}
          </p>
        </div>

        <div class="panel-block">
          <h2>加工模式</h2>
          <button
            v-for="m in modes"
            :key="m.value"
            class="mode-btn"
            :class="{ active: mode === m.value }"
            :disabled="!bridgeReady"
            @click="applyMode(m.value)"
          >
            <strong>{{ m.label }}</strong>
            <span>{{ m.desc }}</span>
          </button>
        </div>

        <div v-if="mode === 'guide'" class="panel-block">
          <h2>指引控制</h2>
          <p class="hint">
            当前第 {{ status.overlayStep || 0 }} /
            {{ status.overlayTotal || 0 }} 步
          </p>
          <div class="row">
            <button @click="guidePrev">上一步</button>
            <button @click="guideNext">下一步</button>
            <button @click="guideStop">结束</button>
          </div>
        </div>

        <div class="panel-block">
          <h2>设备页状态</h2>
          <ul class="kv">
            <li>
              <span>会话</span
              ><b>{{ status.hasSession ? '已登录' : '未登录' }}</b>
            </li>
            <li>
              <span>用户</span><b>{{ status.user || '—' }}</b>
            </li>
            <li>
              <span>注入登录</span><b>{{ status.injected ? '是' : '否' }}</b>
            </li>
            <li>
              <span>设备名称</span><b>{{ status.deviceName || '—' }}</b>
            </li>
            <li>
              <span>分辨率</span
              ><b
                >{{ status.deviceResolution || '—'
                }}{{
                  status.deviceFps ? ' @' + status.deviceFps + 'fps' : ''
                }}</b
              >
            </li>
            <li>
              <span>录像数量</span><b>{{ status.recordingCount ?? 0 }}</b>
            </li>
            <li>
              <span>首条播放次数</span><b>{{ status.playCount ?? 0 }}</b>
            </li>
            <li>
              <span>当前回放</span><b>{{ status.playing || '—' }}</b>
            </li>
            <li>
              <span>播放插件</span
              ><b
                >{{ status.plugin || '—'
                }}{{ status.hijacked ? '（已劫持）' : '' }}</b
              >
            </li>
            <li>
              <span>当前视图</span><b>{{ status.view || '—' }}</b>
            </li>
          </ul>
        </div>

        <div class="panel-block">
          <h2>操作</h2>
          <button @click="reloadDevice">重新加载设备包</button>
        </div>

        <div class="panel-block">
          <h2>日志</h2>
          <ul class="logs">
            <li v-for="(l, i) in logs" :key="i">{{ l }}</li>
            <li v-if="!logs.length" class="hint">暂无</li>
          </ul>
        </div>
      </aside>

      <div class="stage">
        <div class="stage-bar">
          <span>设备页：{{ DEVICE_URL }}</span>
          <span v-if="!bridgeReady" class="injecting">桥接注入中…</span>
        </div>
        <iframe
          ref="iframeEl"
          class="stage-frame"
          :src="frameSrc || undefined"
          @load="onIframeLoad"
        ></iframe>
      </div>
    </div>
  </section>
</template>

<style scoped>
.runtime-layout {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 16px;
  align-items: start;
}

.panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.panel-block {
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
}

.panel-block h2 {
  margin: 0 0 8px;
  font-size: 13px;
  color: #57606a;
  text-transform: uppercase;
  letter-spacing: 0.4px;
}

.badge {
  display: inline-block;
  margin: 0 0 6px;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  color: #8b5b00;
  background: #fdeec9;
}

.badge.ready {
  color: #10643a;
  background: #d6f5e3;
}

.badge.error,
.badge.unsupported {
  color: #8f2020;
  background: #fbe0e0;
}

.hint {
  margin: 4px 0 0;
  color: #8b949e;
  font-size: 12px;
  line-height: 1.5;
}

.mode-btn {
  display: block;
  width: 100%;
  margin-bottom: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: #fff;
  text-align: left;
  cursor: pointer;
}

.mode-btn:last-child {
  margin-bottom: 0;
}

.mode-btn strong {
  display: block;
  font-size: 13px;
  color: var(--fg);
}

.mode-btn span {
  display: block;
  margin-top: 2px;
  color: #8b949e;
  font-size: 12px;
}

.mode-btn.active {
  border-color: var(--accent);
  background: #eef4ff;
}

.mode-btn.active strong {
  color: var(--accent);
}

.mode-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.row {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.row button,
.panel-block > button {
  padding: 6px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: #fff;
  font-size: 12px;
  cursor: pointer;
}

.kv {
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
}

.kv li {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 3px 0;
  border-bottom: 1px dashed var(--border);
}

.kv li:last-child {
  border-bottom: none;
}

.kv span {
  color: #8b949e;
}

.kv b {
  color: var(--fg);
  font-weight: 600;
  text-align: right;
}

.logs {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 180px;
  overflow: auto;
  font-size: 11.5px;
  color: #57606a;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.logs li {
  padding: 2px 0;
}

.stage {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}

.stage-bar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
  color: #8b949e;
}

.injecting {
  color: var(--accent);
}

.stage-frame {
  width: 100%;
  height: 620px;
  border: 0;
  background: #eef1f5;
}
</style>
