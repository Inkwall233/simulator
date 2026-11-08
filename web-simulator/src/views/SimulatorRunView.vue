<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { currentCreator, endRun, getRun, heartbeatRun, loginCreator, logoutCreator, startRun, type SimulatorRun } from '@/api/simulator-runs'

const route = useRoute()
const versionId = ref(String(route.params.versionId || ''))
const username = ref('')
const password = ref('')
const creator = ref('')
const run = ref<SimulatorRun | null>(null)
const error = ref('')
const busy = ref(false)
const ready = ref(false)
const frame = ref<HTMLIFrameElement | null>(null)
let timer: number | undefined
const storageKey = () => `skilllab.simulator.run.${versionId.value}`

function stopHeartbeat() {
  if (timer !== undefined) window.clearInterval(timer)
  timer = undefined
}

function clearRun() {
  stopHeartbeat()
  run.value = null
  ready.value = false
  sessionStorage.removeItem(storageKey())
}

function activate(value: SimulatorRun) {
  run.value = value
  ready.value = false
  sessionStorage.setItem(storageKey(), value.runId)
  stopHeartbeat()
  timer = window.setInterval(async () => {
    if (!run.value || busy.value) return
    try { run.value.expiresAt = (await heartbeatRun(run.value.runId)).expiresAt }
    catch (reason) { error.value = (reason as Error).message; clearRun() }
  }, Math.max(1000, value.heartbeatMs))
}

function onRuntimeMessage(message: MessageEvent) {
  const active = run.value
  if (!active || message.source !== frame.value?.contentWindow) return
  let expectedOrigin: string
  try { expectedOrigin = new URL(active.runUrl).origin } catch { return }
  const data = message.data
  if (message.origin !== expectedOrigin || !data || typeof data !== 'object'
      || data.type !== 'SKILLLAB_PREVIEW_EVENT' || data.previewId !== active.runId
      || data.schemaVersion !== 'skilllab.simulator-behavior.v2') return
  if (data.event === 'SERVICE_WORKER_READY' && data.detail?.controlled === true) ready.value = true
  if (data.event === 'SERVICE_WORKER_ERROR' || data.event === 'SERVICE_WORKER_UNAVAILABLE') {
    ready.value = false
    error.value = '设备页面的 Service Worker 未能接管'
  }
}

async function restore() {
  const id = sessionStorage.getItem(storageKey())
  if (!id) return
  try {
    const value = await getRun(id)
    if (value.versionId === versionId.value) activate(value)
    else clearRun()
  } catch { clearRun() }
}

async function login() {
  busy.value = true; error.value = ''
  try { creator.value = (await loginCreator(username.value.trim(), password.value)).userId; password.value = ''; await restore() }
  catch (reason) { error.value = (reason as Error).message }
  finally { busy.value = false }
}

async function start() {
  busy.value = true; error.value = ''
  try { activate(await startRun(versionId.value)) }
  catch (reason) { error.value = (reason as Error).message }
  finally { busy.value = false }
}

async function end() {
  const id = run.value?.runId
  if (!id) return true
  busy.value = true; error.value = ''
  try { await endRun(id); clearRun(); return true }
  catch (reason) { error.value = (reason as Error).message; return false }
  finally { busy.value = false }
}

async function logout() {
  if (!(await end())) return
  try { await logoutCreator() } catch { /* The session may already have expired. */ }
  creator.value = ''
}

watch(() => route.params.versionId, async id => {
  clearRun()
  versionId.value = String(id || '')
  if (creator.value) await restore()
})
onMounted(async () => { window.addEventListener('message', onRuntimeMessage); try { creator.value = (await currentCreator()).userId; await restore() } catch { creator.value = '' } })
onBeforeUnmount(() => { stopHeartbeat(); window.removeEventListener('message', onRuntimeMessage) })
</script>

<template>
  <section class="sim-run">
    <header><div><small>SKILLLAB / SIMULATOR</small><h1>设备仿真</h1><span>版本 {{ versionId }}</span></div>
      <div class="actions"><button v-if="run" :disabled="busy" @click="end">结束运行</button><button v-if="creator" @click="logout">退出登录</button></div>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <form v-if="!creator" class="login" @submit.prevent="login">
      <h2>制作者登录</h2><input v-model="username" autocomplete="username" placeholder="用户名" required />
      <input v-model="password" type="password" autocomplete="current-password" placeholder="密码" required />
      <button :disabled="busy">登录</button>
    </form>
    <div v-else-if="!run" class="empty"><button :disabled="busy" @click="start">启动模拟器</button></div>
    <div v-else class="stage"><div class="stage-bar"><span>{{ ready ? '运行中' : '正在加载设备页面' }}</span><span>租约至 {{ new Date(run.expiresAt).toLocaleTimeString('zh-CN') }}</span></div>
      <iframe :key="run.runId" ref="frame" :src="run.runUrl" title="设备模拟器" sandbox="allow-scripts allow-same-origin allow-forms" referrerpolicy="no-referrer" />
    </div>
  </section>
</template>

<style scoped>
.sim-run{min-height:100%;display:flex;flex-direction:column;background:#f5f7f8;color:#24323b}
header{display:flex;justify-content:space-between;align-items:center;gap:20px;padding:18px 28px;border-bottom:1px solid #dce3e6;background:#fff}
header small{font-size:10px;color:#647d85}h1{margin:2px 0;font-size:19px}header span{font-size:12px;color:#657980}.actions{display:flex;gap:8px}
button{padding:9px 14px;border:1px solid #b8c8cc;border-radius:5px;background:#fff;color:#223a42;cursor:pointer}button:disabled{opacity:.5;cursor:default}
.error{margin:14px 28px;padding:10px;border:1px solid #e6b9b9;color:#a23d3d;background:#fff4f4}
.login{display:grid;gap:10px;width:min(360px,calc(100% - 40px));margin:8vh auto;padding:22px;border:1px solid #dce3e6;background:#fff}
.login h2{margin:0 0 5px;font-size:16px}.login input{padding:10px;border:1px solid #cbd5d9;border-radius:4px}.login button,.empty button{background:#237063;color:#fff;border-color:#237063}
.empty{display:grid;flex:1;place-items:center}.stage{display:flex;flex:1;flex-direction:column;min-height:0}.stage-bar{display:flex;justify-content:space-between;gap:12px;padding:8px 28px;font-size:12px;color:#5f7278}
iframe{display:block;flex:1;width:100%;min-height:600px;border:0;background:#fff}
</style>
