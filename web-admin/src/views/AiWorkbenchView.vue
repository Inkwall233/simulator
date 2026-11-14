<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import V2ProposalReview from './ai-workbench/V2ProposalReview.vue'
import {
  aiRequest,
  currentCreator,
  loginCreator,
  logoutCreator,
  getSimulatorDraft,
  createBehaviorRevision,
  importLegacyDraft,
  registerCandidate,
  getSimulatorVersion,
  validateCandidate,
  getValidationReport,
  enableCandidate,
  fetchScreenshot,
  listCaptureEvidence,
  listBaselines,
  createBaseline,
  proposeV2,
  uploadPackage,
  type BrowserSession,
  type CaptureEvidence,
  type DeviceBaseline,
  type DeviceProject,
  type NetworkEntry,
  type PackageVersion,
  type PendingApproval,
  type SimulatorDraft,
  type SimulatorPreview,
  type SimulatorVersion,
  type ValidationReport,
  type V2Proposal,
  createSimulatorDraft as createSimulatorDraftRequest
} from '@/api/ai-workbench'

const projects = ref<DeviceProject[]>([])
const selectedProjectId = ref('')
const selectedProject = computed(() => projects.value.find((item) => item.id === selectedProjectId.value) || null)
const creator = ref('')
const username = ref('')
const password = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const projectName = ref('')
const manufacturer = ref('')
const model = ref('')
const targetUrl = ref('')
const selectedFile = ref<File | null>(null)
const latestPackage = ref<PackageVersion | null>(null)
const session = ref<BrowserSession | null>(null)
const screenshotUrl = ref('')
const network = ref<NetworkEntry[]>([])
const capturedEvidence = ref<CaptureEvidence[]>([])
const baselines = ref<DeviceBaseline[]>([])
const baselineSelection = ref<string[]>([])
const baselineAction = ref('')
const baselineCheckpointKind = ref('TEXT_CONTAINS')
const baselineCheckpointValue = ref('')
const baselineBusy = ref(false)
const approvals = ref<PendingApproval[]>([])
const apiDraft = ref<Record<string, unknown> | null>(null)
const apiDraftId = ref('')
const apiDraftEndpointCount = ref(0)
const creatingDraft = ref(false)
const behaviorJson = ref('')
const editingApiDraftId = ref('')
const simulatorGoal = ref('')
const proposingBehavior = ref(false)
const behaviorNotes = ref<string[]>([])
const pendingProposal = ref<{ rules: unknown[]; stateModel: Record<string, unknown>; notes: string[] } | null>(null)
const pendingV2 = ref<V2Proposal | null>(null)
const selectedBaselineId = ref('')
const adoptedSource = ref<{ sourceFingerprint: string; baselineId?: string; currentDraftId?: string } | undefined>()
const selectedDraftId = ref('')
const proposalContext = computed(() => [selectedProjectId.value, latestPackage.value?.id, apiDraftId.value,
  selectedBaselineId.value, selectedDraftId.value].join('|'))
watch(proposalContext, () => { pendingV2.value = null; adoptedSource.value = undefined })
const proposalChanges = computed(() => {
  if (!pendingProposal.value) return []
  const current = apiDraft.value?.mockRules
  const original = new Map((Array.isArray(current) ? current : []).map((rule: Record<string, unknown>) => [rule.id, rule]))
  return pendingProposal.value.rules.map((rule) => {
    const proposed = rule as Record<string, unknown>
    const before = original.get(proposed.id) as Record<string, unknown> | undefined
    return { id: String(proposed.id || ''), kind: before ? '修改' : '新增',
      match: proposed.match, source: proposed.source,
      changed: before ? Object.keys(proposed).filter(key => JSON.stringify(proposed[key]) !== JSON.stringify(before[key])) : Object.keys(proposed) }
  })
})
type WorkbenchView = 'package' | 'capture' | 'behavior' | 'validation' | 'version'
const activeView = ref<WorkbenchView>('package')
const workbenchViews: Array<{ id: WorkbenchView; label: string }> = [
  { id: 'package', label: '设备包' }, { id: 'capture', label: '真实采集' },
  { id: 'behavior', label: '行为制作' }, { id: 'validation', label: '验证' },
  { id: 'version', label: '版本' }
]
const selectedDraft = computed(() => simulatorDraft.value?.id === selectedDraftId.value
  ? simulatorDraft.value : simulatorDrafts.value.find(item => item.id === selectedDraftId.value) || null)
const behaviorRevisionJson = ref('')
const advancedBehavior = ref(false)
const parsedBehavior = computed(() => {
  try { return JSON.parse(behaviorRevisionJson.value) as Record<string, unknown> }
  catch { return null }
})
const behaviorRules = computed(() => Array.isArray(parsedBehavior.value?.rules)
  ? parsedBehavior.value.rules as Array<Record<string, unknown>> : [])
const behaviorScope = computed(() => parsedBehavior.value?.scope as Record<string, unknown> | undefined)
const verificationCases = computed(() => Array.isArray(parsedBehavior.value?.verificationCases)
  ? parsedBehavior.value.verificationCases as Array<Record<string, unknown>> : [])
function addVerificationCase() {
  updateBehavior(model => {
    (model.verificationCases as Array<Record<string, unknown>>).push({ id: 'case-' + Date.now(), steps: [], expectedState: structuredClone(model.initialState) })
  })
}
function addVerificationStep(caseIndex: number) {
  updateBehavior(model => {
    const testCase = (model.verificationCases as Array<Record<string, unknown>>)[caseIndex]
    const rule = (model.rules as Array<Record<string, unknown>>)[0]
    const match = rule.match as Record<string, unknown>
    const response = rule.response as Record<string, unknown>
    (testCase.steps as Array<Record<string, unknown>>).push({ method: match.method, path: match.path,
      expectedStatus: response.status, expectedBody: response.body, expectedRuleId: rule.id })
  })
}
function editVerificationCase(index: number, field: string, value: string) {
  try { updateBehavior(model => { (model.verificationCases as Array<Record<string, unknown>>)[index][field] = field === 'expectedState' ? JSON.parse(value) : value }) }
  catch { showError('状态断言必须是有效 JSON') }
}
function editVerificationStep(caseIndex: number, stepIndex: number, field: string, value: string | boolean) {
  try {
    updateBehavior(model => {
      const testCase = (model.verificationCases as Array<Record<string, unknown>>)[caseIndex]
      const step = (testCase.steps as Array<Record<string, unknown>>)[stepIndex]
      if (field === 'body' && ['GET', 'HEAD'].includes(String(step.method))) delete step.body
      else if (field === 'body' || field === 'expectedBody') step[field] = JSON.parse(String(value))
      else if (field === 'expectedStatus') step[field] = Number(value)
      else if (field === 'method') {
        step[field] = value
        if (value === 'GET' || value === 'HEAD') delete step.body
      }
      else if (field === 'reloadBefore' && !value) delete step[field]
      else step[field] = value
    })
  } catch { showError('请求体与响应断言必须是有效 JSON') }
}
function updateBehavior(change: (model: Record<string, unknown>) => void) {
  if (!parsedBehavior.value) return
  const next = structuredClone(parsedBehavior.value)
  change(next)
  behaviorRevisionJson.value = JSON.stringify(next, null, 2)
}
function updateScopeName(value: string) {
  updateBehavior(model => { (model.scope as Record<string, unknown>).name = value })
}
function updateCriticalRule(id: string, checked: boolean) {
  updateBehavior(model => {
    const scope = model.scope as Record<string, unknown>
    const ids = new Set(scope.criticalRuleIds as string[])
    if (checked) ids.add(id)
    else ids.delete(id)
    scope.criticalRuleIds = [...ids]
  })
}
function updateInitialState(value: string) {
  try { updateBehavior(model => { model.initialState = JSON.parse(value) as Record<string, unknown> }) }
  catch { showError('初始状态必须是有效 JSON') }
}
const revisionSourceId = ref('')
const savingRevision = ref(false)
const candidate = ref<SimulatorVersion | null>(null)
const candidateBusy = ref(false)
const validationReport = ref<ValidationReport | null>(null)
const reportIdInput = ref('')
const validating = ref(false)
const acknowledgeWarnings = ref(false)

async function runValidation() {
  const version = candidate.value
  if (!version) return
  const projectId = selectedProjectId.value
  validating.value = true
  validationReport.value = null
  acknowledgeWarnings.value = false
  try {
    const report = await validateCandidate(projectId, version.versionId, selectedBaselineId.value || undefined)
    if (projectId === selectedProjectId.value && candidate.value?.versionId === version.versionId) validationReport.value = report
    if (projectId === selectedProjectId.value) reportIdInput.value = report.reportId
  } catch (reason) { showError((reason as Error).message) }
  finally { validating.value = false }
}

async function loadReport() {
  if (!candidate.value || !reportIdInput.value.trim()) return
  const versionId = candidate.value.versionId
  const projectId = selectedProjectId.value
  try {
    const [report, version] = await Promise.all([
      getValidationReport(projectId, versionId, reportIdInput.value.trim()),
      getSimulatorVersion(projectId, versionId)
    ])
    if (projectId !== selectedProjectId.value || candidate.value?.versionId !== versionId) return
    validationReport.value = report
    candidate.value = version
    acknowledgeWarnings.value = false
  }
  catch (reason) { showError((reason as Error).message) }
}

async function enableValidated() {
  const report = validationReport.value
  const version = candidate.value
  if (!report || !version || report.status !== 'PASSED') return
  candidateBusy.value = true
  try {
    await enableCandidate(selectedProjectId.value, version.versionId, report.reportId, acknowledgeWarnings.value)
    await refreshCandidate()
    addEvent('模拟器版本已启用', 'success')
  } catch (reason) { showError((reason as Error).message) }
  finally { candidateBusy.value = false }
}

async function selectDraft(draft: SimulatorDraft) {
  const projectId = selectedProjectId.value
  try {
    const full = draft.payload ? draft : await getSimulatorDraft(projectId, draft.id)
    if (projectId !== selectedProjectId.value) return
    selectedDraftId.value = full.id
    simulatorDraft.value = full
    behaviorRevisionJson.value = ''
    revisionSourceId.value = ''
    if (full.payload?.schemaVersion === 'skilllab.simulator-behavior.v2') {
      behaviorRevisionJson.value = JSON.stringify(full.payload, null, 2)
      advancedBehavior.value = false
      revisionSourceId.value = full.sourceApiDraftId
    }
    activeView.value = 'behavior'
  } catch (reason) { showError((reason as Error).message) }
}

async function importDraft(draft: SimulatorDraft) {
  const projectId = selectedProjectId.value
  savingRevision.value = true
  try {
    const created = await importLegacyDraft(projectId, draft.id)
    if (projectId !== selectedProjectId.value) return
    simulatorDrafts.value.unshift(created)
    await selectDraft(created)
    addEvent('已导入 v2 行为修订；所有规则仍标记为待核实推断', 'warning')
  } catch (reason) { showError((reason as Error).message) }
  finally { savingRevision.value = false }
}

function editAsNewRevision() {
  const draft = selectedDraft.value
  if (!draft?.payload || draft.payload.schemaVersion !== 'skilllab.simulator-behavior.v2') return
  behaviorRevisionJson.value = JSON.stringify(draft.payload, null, 2)
  advancedBehavior.value = false
  revisionSourceId.value = draft.sourceApiDraftId
  candidate.value = null
}

async function confirmRevision() {
  const projectId = selectedProjectId.value
  if (!revisionSourceId.value || !behaviorRevisionJson.value) return
  savingRevision.value = true
  try {
    const behavior = JSON.parse(behaviorRevisionJson.value) as Record<string, unknown>
    if (behavior.schemaVersion !== 'skilllab.simulator-behavior.v2') throw new Error('仅支持 v2 行为协议')
    const created = await createBehaviorRevision(projectId, revisionSourceId.value, behavior, adoptedSource.value)
    if (projectId !== selectedProjectId.value) return
    simulatorDrafts.value.unshift(created)
    simulatorDraft.value = created
    selectedDraftId.value = created.id
    candidate.value = null
    validationReport.value = null
    adoptedSource.value = undefined
    addEvent('已确认并保存不可变行为修订 v' + created.versionNumber, 'success')
    activeView.value = 'validation'
  } catch (reason) { showError((reason as Error).message) }
  finally { savingRevision.value = false }
}

async function makeCandidate() {
  const draft = selectedDraft.value
  if (!draft || draft.payload?.schemaVersion !== 'skilllab.simulator-behavior.v2') return
  const projectId = selectedProjectId.value
  candidateBusy.value = true
  try {
    const result = await registerCandidate(projectId, draft.id)
    if (projectId !== selectedProjectId.value) return
    candidate.value = result
    validationReport.value = null
    activeView.value = 'version'
  } catch (reason) { showError((reason as Error).message) }
  finally { candidateBusy.value = false }
}

async function refreshCandidate() {
  if (!candidate.value) return
  try { candidate.value = await getSimulatorVersion(selectedProjectId.value, candidate.value.versionId) }
  catch (reason) { showError((reason as Error).message) }
}

async function proposeBehavior() {
  if (!session.value || !simulatorGoal.value.trim()) return
  proposingBehavior.value = true
  const sourceId = apiDraftId.value
  try {
    const proposal = await aiRequest<{ apiDraftId: string; rules: unknown[]; stateModel: Record<string, unknown>; notes?: string[] }>(
      '/sessions/' + session.value.id + '/simulator-behavior',
      { method: 'POST', body: JSON.stringify({ goal: simulatorGoal.value }) }
    )
    if (sourceId !== apiDraftId.value || proposal.apiDraftId !== sourceId) throw new Error('采集版本已变化，请重新生成')
    pendingProposal.value = { rules: proposal.rules, stateModel: proposal.stateModel,
      notes: Array.isArray(proposal.notes) ? proposal.notes : [] }
  } catch (reason) { showError((reason as Error).message) }
  finally { proposingBehavior.value = false }
}

async function proposeBehaviorV2() {
  if (!latestPackage.value || !apiDraftId.value || !simulatorGoal.value.trim()) return
  const context = proposalContext.value
  proposingBehavior.value = true
  try {
    const proposal = await proposeV2(selectedProjectId.value, { packageId: latestPackage.value.id,
      apiDraftId: apiDraftId.value, baselineId: selectedBaselineId.value || undefined,
      currentDraftId: selectedDraftId.value || undefined, goal: simulatorGoal.value.trim() })
    if (context !== proposalContext.value || proposal.apiDraftId !== apiDraftId.value) return
    pendingV2.value = proposal
  } catch (reason) { if (context === proposalContext.value) showError((reason as Error).message) }
  finally { proposingBehavior.value = false }
}

function adoptV2(behavior: Record<string, unknown>) {
  if (!pendingV2.value) return
  behaviorRevisionJson.value = JSON.stringify(behavior, null, 2)
  revisionSourceId.value = apiDraftId.value
  adoptedSource.value = { sourceFingerprint: pendingV2.value.sourceFingerprint,
    baselineId: selectedBaselineId.value || undefined, currentDraftId: selectedDraftId.value || undefined }
  pendingV2.value = null
}

function startManualV2() {
  const archive = latestPackage.value
  if (!archive || !apiDraftId.value || !Array.isArray(apiDraft.value?.mockRules)) return
  const rules = (apiDraft.value.mockRules as Array<Record<string, unknown>>).map((rule, index) => ({
    id: rule.id, priority: index, match: rule.match,
    response: { status: rule.status, contentType: rule.contentType || 'application/json', body: rule.body ?? null },
    operations: [], evidence: { type: 'INFERRED_PENDING', observationIds: [], note: '待人工确认' }
  }))
  behaviorRevisionJson.value = JSON.stringify({ schemaVersion: 'skilllab.simulator-behavior.v2',
    packageVersion: { id: archive.id, sha256: archive.sha256 }, scope: { name: '设备行为', criticalRuleIds: [] },
    initialState: {}, rules, verificationCases: [] }, null, 2)
  revisionSourceId.value = apiDraftId.value
  adoptedSource.value = undefined
}

function adoptProposal() {
  if (!pendingProposal.value || !apiDraftId.value) return
  behaviorJson.value = JSON.stringify({ rules: pendingProposal.value.rules, stateModel: pendingProposal.value.stateModel }, null, 2)
  editingApiDraftId.value = apiDraftId.value
  behaviorNotes.value = pendingProposal.value.notes
  pendingProposal.value = null
}

function configureBehavior() {
  const payload = apiDraft.value
  if (!payload || !Array.isArray(payload.mockRules)) return
  behaviorJson.value = JSON.stringify({
    rules: payload.mockRules.map((rule: Record<string, unknown>) => ({
      ...rule,
      source: { apiDraftId: apiDraftId.value, endpointId: rule.id }
    })),
    stateModel: { initial: {}, transitions: [] }
  }, null, 2)
  editingApiDraftId.value = apiDraftId.value
}
const simulatorDraft = ref<SimulatorDraft | null>(null)
const creatingSimulatorDraft = ref(false)
const simulatorDrafts = ref<SimulatorDraft[]>([])
const preview = ref<SimulatorPreview | null>(null)
const previewFrame = ref<HTMLIFrameElement | null>(null)
const openingPreview = ref(false)
const previewEvents = ref<string[]>([])
const previewEventNames = new Set(['SERVICE_WORKER_READY', 'SERVICE_WORKER_UNAVAILABLE', 'SERVICE_WORKER_ERROR', 'RULE_HIT', 'UNMATCHED_REQUEST', 'MOCK_RUNTIME_ERROR'])

async function openPreview(draft: SimulatorDraft) {
  const projectId = selectedProjectId.value
  openingPreview.value = true
  activeView.value = 'validation'
  preview.value = null
  previewEvents.value = []
  try {
    const result = await aiRequest<SimulatorPreview>('/device-projects/' + projectId + '/simulator-drafts/' + draft.id + '/preview', { method: 'POST' })
    if (selectedProjectId.value === projectId && authorized.value) preview.value = result
  } catch (reason) {
    showError((reason as Error).message)
  } finally {
    openingPreview.value = false
  }
}

function receivePreviewEvent(message: MessageEvent) {
  const active = preview.value
  if (!active || message.source !== previewFrame.value?.contentWindow
      || message.origin !== new URL(active.previewUrl).origin) return
  const data = message.data
  if (!data || data.type !== 'SKILLLAB_PREVIEW_EVENT' || data.previewId !== active.previewId
      || !previewEventNames.has(data.event)) return
  const detail = data.detail && typeof data.detail === 'object' ? data.detail : {}
  previewEvents.value.unshift(new Date().toLocaleTimeString('zh-CN') + ' ' + data.event + ' ' + JSON.stringify(detail).slice(0, 500))
  previewEvents.value.length = Math.min(previewEvents.value.length, 100)
}
const command = ref('')
const typedText = ref('')
const timeline = ref<Array<{ id: number; time: string; text: string; type: string }>>([])
let pollTimer: number | undefined
let nextTimelineId = 1

const authorized = computed(() => Boolean(creator.value))
const httpWarning = computed(() => targetUrl.value.trim().toLowerCase().startsWith('http://'))

onMounted(async () => {
  window.addEventListener('message', receivePreviewEvent)
  try {
    creator.value = (await currentCreator()).userId
    await loadProjects()
  } catch { creator.value = '' }
})

onBeforeUnmount(() => {
  window.removeEventListener('message', receivePreviewEvent)
  stopPolling()
  clearScreenshot()
  if (session.value) void closeSession(false)
})

watch(selectedProjectId, async (id) => {
  if (session.value && session.value.projectId !== id) await closeSession(false)
  activeView.value = 'package'
  selectedDraftId.value = ''
  behaviorRevisionJson.value = ''
  revisionSourceId.value = ''
  candidate.value = null
  validationReport.value = null
  reportIdInput.value = ''
  pendingProposal.value = null
  apiDraft.value = null
  apiDraftId.value = ''
  apiDraftEndpointCount.value = 0
  behaviorJson.value = ''
  editingApiDraftId.value = ''
  behaviorNotes.value = []
  preview.value = null
  previewEvents.value = []
  network.value = []
  capturedEvidence.value = []
  baselines.value = []
  baselineSelection.value = []
  approvals.value = []
  latestPackage.value = null
  simulatorDraft.value = null
  simulatorDrafts.value = []
  if (!id) return
  try {
    const [detail, savedDrafts, savedBaselines] = await Promise.all([
      aiRequest<DeviceProject>('/device-projects/' + id),
      aiRequest<SimulatorDraft[]>('/device-projects/' + id + '/simulator-drafts'),
      listBaselines(id)
    ])
    if (selectedProjectId.value !== id) return
    const index = projects.value.findIndex((item) => item.id === id)
    if (index >= 0) projects.value[index] = detail
    latestPackage.value = detail.packages?.[0] || null
    simulatorDrafts.value = savedDrafts
    baselines.value = savedBaselines
  } catch (reason) {
    showError((reason as Error).message)
  }
})

async function connectWithKey() {
  if (!username.value.trim() || !password.value) return
  loading.value = true
  try {
    creator.value = (await loginCreator(username.value.trim(), password.value)).userId
    password.value = ''
    await loadProjects()
  } catch (reason) { showError((reason as Error).message) }
  finally { loading.value = false }
}

async function loadProjects() {
  loading.value = true
  try {
    projects.value = await aiRequest<DeviceProject[]>('/device-projects')
    if (!projects.value.some((item) => item.id === selectedProjectId.value)) {
      selectedProjectId.value = projects.value[0]?.id || ''
    }
    error.value = ''
  } catch (reason) {
    creator.value = ''
    error.value = (reason as Error).message
  } finally {
    loading.value = false
  }
}

async function createProject() {
  if (!projectName.value.trim()) return
  busy.value = true
  try {
    const created = await aiRequest<DeviceProject>('/device-projects', {
      method: 'POST',
      body: JSON.stringify({
        name: projectName.value.trim(),
        manufacturer: manufacturer.value.trim(),
        model: model.value.trim()
      })
    })
    projects.value.unshift(created)
    selectedProjectId.value = created.id
    projectName.value = ''
    manufacturer.value = ''
    model.value = ''
    addEvent('已创建设备项目「' + created.name + '」', 'success')
  } catch (reason) {
    showError((reason as Error).message)
  } finally {
    busy.value = false
  }
}

function onFileChange(event: Event) {
  selectedFile.value = (event.target as HTMLInputElement).files?.[0] || null
}

async function uploadPackageFile() {
  if (!selectedProjectId.value || !selectedFile.value) return
  if (selectedFile.value.size > 200 * 1024 * 1024) {
    showError('当前版本支持最大 200 MB 的 ZIP 包')
    return
  }
  busy.value = true
  try {
    latestPackage.value = await uploadPackage(selectedProjectId.value, selectedFile.value)
    simulatorDraft.value = null
    activeView.value = 'capture'
    addEvent('已上传并检查 ' + latestPackage.value.filename, 'success')
    selectedFile.value = null
    const input = document.querySelector<HTMLInputElement>('#ai-package-input')
    if (input) input.value = ''
  } catch (reason) {
    showError((reason as Error).message)
  } finally {
    busy.value = false
  }
}

async function connectDevice() {
  if (!selectedProjectId.value || !targetUrl.value.trim()) return
  if (session.value) await closeSession(false)
  apiDraft.value = null
  apiDraftId.value = ''
  apiDraftEndpointCount.value = 0
  simulatorDraft.value = null
  busy.value = true
  try {
    session.value = await aiRequest<BrowserSession>(
      '/device-projects/' + selectedProjectId.value + '/sessions',
      { method: 'POST', body: JSON.stringify({ targetUrl: targetUrl.value.trim() }) }
    )
    addEvent('远程浏览器已连接：' + session.value.targetUrl, 'success')
    activeView.value = 'capture'
    startPolling()
    await refreshBrowser()
  } catch (reason) {
    showError((reason as Error).message)
    addEvent('连接失败：' + (reason as Error).message, 'error')
  } finally {
    busy.value = false
  }
}

function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(() => void refreshBrowser(), 1300)
}

function stopPolling() {
  if (pollTimer !== undefined) window.clearInterval(pollTimer)
  pollTimer = undefined
}

async function refreshBrowser() {
  if (!session.value) return
  const sessionId = session.value.id
  try {
    const results = await Promise.all([
      fetchScreenshot(sessionId),
      aiRequest<{ items: NetworkEntry[] }>('/sessions/' + sessionId + '/network'),
      aiRequest<{ items: PendingApproval[] }>('/sessions/' + sessionId + '/pending-approval')
    ])
    if (session.value?.id !== sessionId) return
    const nextUrl = URL.createObjectURL(results[0])
    clearScreenshot()
    screenshotUrl.value = nextUrl
    network.value = results[1].items || []
    approvals.value = results[2].items || []
  } catch (reason) {
    showError((reason as Error).message)
  }
}

function onStageClick(event: MouseEvent) {
  const image = event.currentTarget as HTMLImageElement
  const bounds = image.getBoundingClientRect()
  void runAction({
    type: 'click',
    x: Math.round((event.clientX - bounds.left) * 1360 / bounds.width),
    y: Math.round((event.clientY - bounds.top) * 820 / bounds.height)
  }, '手动点击设备页面')
}

function onStageWheel(event: WheelEvent) {
  event.preventDefault()
  void runAction({ type: 'scroll', deltaY: event.deltaY }, '手动滚动设备页面')
}

async function runAction(action: Record<string, unknown>, label: string) {
  if (!session.value) return
  try {
    const result = await aiRequest<{ status?: string }>('/sessions/' + session.value.id + '/actions', {
      method: 'POST',
      body: JSON.stringify(action)
    })
    addEvent(label, 'info')
    notice.value = result.status || ''
    await refreshBrowser()
  } catch (reason) {
    showError((reason as Error).message)
  }
}

async function typeIntoPage() {
  if (!typedText.value) return
  const text = typedText.value
  typedText.value = ''
  await runAction({ type: 'type', text }, '已向页面焦点输入文本；输入内容不进入时间线')
}

async function navigateToTarget() {
  if (!session.value || !targetUrl.value.trim()) return
  await runAction({ type: 'navigate', url: targetUrl.value.trim() }, '导航至 ' + targetUrl.value.trim())
}

async function sendCommand() {
  if (!session.value || !command.value.trim()) return
  const text = command.value.trim()
  command.value = ''
  busy.value = true
  addEvent('指令：' + text, 'command')
  try {
    const result = await aiRequest<{
      message?: string
      action?: { type?: string }
      result?: { status?: string }
    }>('/sessions/' + session.value.id + '/commands', {
      method: 'POST',
      body: JSON.stringify({ command: text })
    })
    if (result.action) addEvent('AI 操作：' + (result.action.type || '页面操作'), 'ai')
    if (result.message) addEvent(result.message, 'ai')
    notice.value = result.result?.status || ''
    await refreshBrowser()
  } catch (reason) {
    addEvent((reason as Error).message, 'error')
    showError((reason as Error).message)
  } finally {
    busy.value = false
  }
}

async function resolveApproval(item: PendingApproval, approved: boolean) {
  if (!session.value) return
  try {
    await aiRequest('/sessions/' + session.value.id + '/approvals', {
      method: 'POST',
      body: JSON.stringify({ approvalId: item.id, approved })
    })
    approvals.value = approvals.value.filter((approval) => approval.id !== item.id)
    addEvent((approved ? '已批准一次：' : '已拒绝：') + item.method + ' ' + item.url, approved ? 'warning' : 'info')
  } catch (reason) {
    showError((reason as Error).message)
  }
}

async function createApiDraft() {
  if (!session.value) return
  creatingDraft.value = true
  try {
    const result = await aiRequest<{
      id: string
      endpointCount: number
      payload: Record<string, unknown>
    }>('/sessions/' + session.value.id + '/drafts', { method: 'POST' })
    apiDraftId.value = result.id
    apiDraft.value = result.payload
    apiDraftEndpointCount.value = result.endpointCount
    pendingProposal.value = null
    configureBehavior()
    simulatorDraft.value = null
    selectedDraftId.value = ''
    behaviorRevisionJson.value = ''
    revisionSourceId.value = ''
    addEvent('已生成 ' + result.endpointCount + ' 条接口 Mock 草稿，待人工审查', 'success')
    activeView.value = 'behavior'
  } catch (reason) {
    showError((reason as Error).message)
  } finally {
    creatingDraft.value = false
  }
}

async function saveObservations() {
  const active = session.value
  const projectId = selectedProjectId.value
  if (!active || !network.value.length) return
  baselineBusy.value = true
  try {
    await aiRequest(`/device-projects/${projectId}/sessions/${active.id}/evidence`, {
      method: 'POST', body: JSON.stringify({ observationIds: network.value.map(item => item.id) })
    })
    if (active.id !== session.value?.id || projectId !== selectedProjectId.value) return
    capturedEvidence.value = await listCaptureEvidence(projectId, active.id)
    addEvent(`已保存 ${capturedEvidence.value.length} 条脱敏观察`, 'success')
  } catch (reason) { showError((reason as Error).message) }
  finally { baselineBusy.value = false }
}

async function freezeBaseline() {
  const active = session.value
  const projectId = selectedProjectId.value
  const archive = latestPackage.value
  const sourceId = apiDraftId.value
  if (!active || !archive || !sourceId || !baselineSelection.value.length) return
  baselineBusy.value = true
  try {
    const chosen = capturedEvidence.value.filter(item => baselineSelection.value.includes(item.id))
    const steps = chosen.sort((a, b) => a.sequence - b.sequence).map(item => ({
      observationId: item.id, action: baselineAction.value.trim(),
      checkpoint: baselineCheckpointValue.value.trim() ? { kind: baselineCheckpointKind.value, value: baselineCheckpointValue.value.trim() } : null,
      comparisons: []
    }))
    const saved = await createBaseline(projectId, archive.id, sourceId, active.id, { steps })
    if (active.id !== session.value?.id || projectId !== selectedProjectId.value || archive.id !== latestPackage.value?.id) return
    baselines.value.unshift(saved)
    baselineSelection.value = []
    addEvent('已冻结设备基线', 'success')
  } catch (reason) { showError((reason as Error).message) }
  finally { baselineBusy.value = false }
}

async function createSimulatorDraft() {
  if (!selectedProjectId.value || !latestPackage.value || !apiDraftId.value) return
  creatingSimulatorDraft.value = true
  try {
    simulatorDraft.value = await createSimulatorDraftRequest(
      selectedProjectId.value,
      latestPackage.value.id,
      apiDraftId.value,
      behaviorJson.value && editingApiDraftId.value === apiDraftId.value ? JSON.parse(behaviorJson.value) : undefined
    )
    simulatorDrafts.value = [
      simulatorDraft.value,
      ...simulatorDrafts.value.filter((item) => item.id !== simulatorDraft.value?.id)
    ]
    addEvent('已保存 Simulator 草稿 v' + simulatorDraft.value.versionNumber + '，待审核', 'success')
    selectedDraftId.value = simulatorDraft.value.id
  } catch (reason) {
    showError((reason as Error).message)
  } finally {
    creatingSimulatorDraft.value = false
  }
}

function downloadApiDraft() {
  if (!apiDraft.value) return
  const blob = new Blob([JSON.stringify(apiDraft.value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = 'skilllab-api-mock-draft.json'
  anchor.click()
  URL.revokeObjectURL(url)
}

function downloadSimulatorDraft() {
  if (!simulatorDraft.value?.payload) return
  downloadJson(simulatorDraft.value.payload, 'skilllab-simulator-draft-v' + simulatorDraft.value.versionNumber + '.json')
}

async function downloadSavedSimulatorDraft(draft: SimulatorDraft) {
  if (!selectedProjectId.value) return
  try {
    const fullDraft = draft.payload
      ? draft
      : await getSimulatorDraft(selectedProjectId.value, draft.id)
    if (fullDraft.payload) {
      downloadJson(fullDraft.payload, 'skilllab-simulator-draft-v' + fullDraft.versionNumber + '.json')
    }
  } catch (reason) {
    showError((reason as Error).message)
  }
}

function downloadJson(payload: Record<string, unknown>, filename: string) {
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

async function closeSession(showNotice = true) {
  const active = session.value
  if (!active) return
  stopPolling()
  session.value = null
  approvals.value = []
  network.value = []
  apiDraft.value = null
  apiDraftId.value = ''
  apiDraftEndpointCount.value = 0
  simulatorDraft.value = null
  clearScreenshot()
  try {
    await aiRequest('/sessions/' + active.id + '/close', { method: 'POST' })
    if (showNotice) addEvent('浏览器会话已结束并清理', 'info')
  } catch (reason) {
    if (showNotice) showError((reason as Error).message)
  }
}

async function forgetKey() {
  preview.value = null
  await closeSession(false)
  try { await logoutCreator() } catch { /* Expired sessions are already logged out. */ }
  creator.value = ''
  projects.value = []
  selectedProjectId.value = ''
}

function addEvent(text: string, type: string) {
  timeline.value.unshift({
    id: nextTimelineId++,
    time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    text,
    type
  })
  if (timeline.value.length > 80) timeline.value.length = 80
}

function showError(message: string) {
  error.value = message
  if (message) window.setTimeout(() => {
    if (error.value === message) error.value = ''
  }, 7000)
}

function clearScreenshot() {
  if (screenshotUrl.value) URL.revokeObjectURL(screenshotUrl.value)
  screenshotUrl.value = ''
}

function sizeLabel(size: number) {
  return size < 1024 * 1024
    ? Math.max(1, Math.round(size / 1024)) + ' KB'
    : (size / 1024 / 1024).toFixed(1) + ' MB'
}
</script>

<template>
  <section class="ai-workbench">
    <header class="wb-header">
      <div>
        <p class="wb-eyebrow">DEVICE SIMULATOR STUDIO</p>
        <h1>AI 设备制作工作台</h1>
        <p class="wb-subtitle">上传静态包，连接获准设备，在线采集页面操作与接口。</p>
      </div>
      <button v-if="authorized" class="wb-quiet" @click="forgetKey">退出登录</button>
    </header>

    <div v-if="!authorized" class="wb-key-gate">
      <div class="wb-key-mark">⌑</div>
      <h2>制作者登录</h2>
      <form class="wb-key-form" @submit.prevent="connectWithKey">
        <input v-model="username" autocomplete="username" placeholder="用户名" />
        <input v-model="password" type="password" autocomplete="current-password" placeholder="密码" />
        <button class="wb-primary" :disabled="!username.trim() || !password || loading">登录</button>
      </form>
      <p v-if="error" class="wb-error">{{ error }}</p>
    </div>

    <template v-else>
      <div v-if="error" class="wb-error">{{ error }}</div>
      <nav class="wb-views" aria-label="制作流程">
        <button v-for="(view, index) in workbenchViews" :key="view.id" type="button"
          :class="{ active: activeView === view.id }" :aria-current="activeView === view.id ? 'step' : undefined"
          @click="activeView = view.id"><span>{{ index + 1 }}</span>{{ view.label }}</button>
      </nav>
      <div class="wb-layout">
        <aside class="wb-left">
          <section v-show="activeView === 'package' || activeView === 'version'" class="wb-card wb-project-card">
            <div class="wb-section-title">
              <div><span>01 / PROJECT</span><h2>设备项目</h2></div>
              <button class="wb-icon-button" title="刷新" @click="loadProjects">↻</button>
            </div>
            <label class="wb-label" for="ai-project-select">当前项目</label>
            <select id="ai-project-select" v-model="selectedProjectId" class="wb-input" :disabled="!!session || busy">
              <option value="">选择或新建项目</option>
              <option v-for="item in projects" :key="item.id" :value="item.id">{{ item.name }}</option>
            </select>
            <form class="wb-create-form" @submit.prevent="createProject">
              <input v-model="projectName" class="wb-input" placeholder="设备项目名称" maxlength="160" />
              <div class="wb-two-fields">
                <input v-model="manufacturer" class="wb-input" placeholder="厂商" maxlength="160" />
                <input v-model="model" class="wb-input" placeholder="型号" maxlength="160" />
              </div>
              <button class="wb-secondary wb-full" :disabled="busy || !projectName.trim()">新建设备项目</button>
            </form>

            <div v-if="selectedProject" class="wb-package">
              <div class="wb-package-heading"><span class="wb-label">设备静态包</span><small>ZIP · 最大 200 MB</small></div>
              <label class="wb-upload" for="ai-package-input">
                <span class="wb-upload-mark">↑</span>
                <strong>{{ selectedFile?.name || '选择设备 Web 包' }}</strong>
                <small>{{ selectedFile ? sizeLabel(selectedFile.size) : '保留原始版本并检查 ZIP 结构' }}</small>
              </label>
              <input id="ai-package-input" class="wb-file" type="file" accept=".zip,application/zip" @change="onFileChange" />
              <button class="wb-secondary wb-full" :disabled="busy || !selectedFile" @click="uploadPackageFile">
                {{ busy ? '处理中…' : '上传并检查' }}
              </button>
              <div v-if="latestPackage" class="wb-package-result">
                <div><strong>{{ latestPackage.filename }}</strong><span>{{ sizeLabel(latestPackage.sizeBytes) }}</span></div>
                <small>SHA-256 {{ latestPackage.sha256.slice(0, 16) }}…</small>
                <p v-if="latestPackage.scan?.rootPathReferenceHtml?.length" class="wb-compat-warning">发现根路径资源引用，发布前需要检查路径兼容性。</p>
                <p v-else class="wb-compat-ok">ZIP 结构有效，包含 index.html。</p>
              </div>
              <div v-if="simulatorDrafts.length" class="wb-saved-drafts">
                <span class="wb-label">已保存 Simulator 草稿</span>
                <div v-for="draft in simulatorDrafts" :key="draft.id" class="wb-saved-draft-row">
                  <span>v{{ draft.versionNumber }} · {{ draft.status }} · {{ draft.createdAt }}</span>
                  <button class="wb-link-button" @click="downloadSavedSimulatorDraft(draft)">下载</button>
                  <button class="wb-link-button" :disabled="openingPreview" @click="openPreview(draft)">隔离预览</button>
                  <button class="wb-link-button" @click="selectDraft(draft)">查看修订</button>
                  <button v-if="draft.payload?.schemaVersion === 'skilllab.simulator-draft.v1'" class="wb-link-button" :disabled="savingRevision" @click="importDraft(draft)">导入 v2</button>
                </div>
              </div>
            </div>
          </section>

          <section v-show="activeView === 'capture'" class="wb-card wb-command-card">
            <div class="wb-section-title">
              <div><span>02 / ASSISTANT</span><h2>指令与事件</h2></div>
              <span class="wb-live" :class="{ active: session }">{{ session ? 'LIVE' : 'READY' }}</span>
            </div>
            <div class="wb-timeline">
              <div v-for="item in timeline" :key="item.id" class="wb-event" :class="item.type">
                <time>{{ item.time }}</time><p>{{ item.text }}</p>
              </div>
              <div v-if="!timeline.length" class="wb-empty-timeline">创建项目并连接设备，采集过程会显示在这里。</div>
            </div>
            <form class="wb-composer" @submit.prevent="sendCommand">
              <textarea v-model="command" rows="2" :disabled="!session || busy" placeholder="例如：打开录像页，观察加载了哪些接口" />
              <button class="wb-primary" :disabled="!session || busy || !command.trim()">发送指令 ↗</button>
              <small>AI 通过受限浏览器操作；页面摘要会发送到部署配置的模型，请勿在敏感页面启用。</small>
            </form>
          </section>
        </aside>

        <main class="wb-right">
          <section v-if="preview && activeView === 'validation'" class="wb-preview">
            <div class="wb-device-toolbar">
              <strong>隔离预览 · v{{ preview.versionNumber }}</strong>
              <span>{{ preview.runtimeVersion }} · {{ new Date(preview.expiresAt).toLocaleTimeString('zh-CN') }} 到期</span>
              <button class="wb-secondary" @click="preview = null">关闭</button>
            </div>
            <iframe ref="previewFrame" :src="preview.previewUrl" title="Simulator 草稿预览"
              sandbox="allow-scripts allow-same-origin allow-forms" referrerpolicy="no-referrer" />
            <ol class="wb-preview-events"><li v-for="(item, index) in previewEvents" :key="index">{{ item }}</li></ol>
          </section>
          <section v-show="activeView === 'capture'" class="wb-card wb-device-card">
            <div class="wb-device-toolbar">
              <div><span class="wb-kicker">03 / LIVE DEVICE</span><strong>真实设备页面</strong></div>
              <form class="wb-target-form" @submit.prevent="session ? navigateToTarget() : connectDevice()">
                <span class="wb-url-tag" :class="{ insecure: httpWarning }">{{ httpWarning ? 'HTTP' : 'URL' }}</span>
                <input v-model="targetUrl" type="url" :disabled="!!session" placeholder="http://192.168.1.10 或 https://device.local" />
                <button v-if="!session" class="wb-primary" :disabled="busy || !selectedProjectId || !targetUrl.trim()">连接</button>
                <button v-else type="button" class="wb-secondary" @click="closeSession()">结束</button>
              </form>
            </div>
            <div v-if="httpWarning" class="wb-http-warning"><b>!</b> HTTP 未加密。请仅在获准的可信内网使用，设备凭证和流量可能以明文传输。</div>

            <div v-if="!session" class="wb-browser-empty">
              <div class="wb-orbit"><i></i><i></i><i></i></div>
              <h3>远程浏览器待命</h3>
              <p>连接已获准设备后，在隔离浏览器中登录、操作并采集接口。管理员可以随时接管。</p>
              <div class="wb-chips"><span>HTTP / HTTPS</span><span>手动接管</span><span>写请求需确认</span></div>
            </div>

            <template v-else>
              <div class="wb-browser-meta">
                <span class="wb-origin"><i></i>{{ session.targetUrl }}</span>
                <span v-if="notice">{{ notice }}</span>
                <span class="wb-request-count">{{ network.length }} 个请求</span>
              </div>
              <div class="wb-stage" @wheel="onStageWheel">
                <img v-if="screenshotUrl" :src="screenshotUrl" alt="远程设备浏览器画面" draggable="false" @click="onStageClick" />
                <div v-else class="wb-stage-loading">正在加载远程页面…</div>
                <small>点击画面可操作设备页面</small>
              </div>
              <div class="wb-manual-controls">
                <input v-model="typedText" placeholder="输入到远程页面当前焦点；输入内容不会进入时间线" @keydown.enter.prevent="typeIntoPage" />
                <button class="wb-secondary" :disabled="!typedText" @click="typeIntoPage">输入</button>
                <button class="wb-secondary wb-key-button" @click="runAction({ type: 'press', key: 'Enter' }, '向页面发送 Enter')">Enter</button>
                <button class="wb-secondary wb-key-button" @click="runAction({ type: 'press', key: 'Tab' }, '向页面发送 Tab')">Tab</button>
              </div>
            </template>
          </section>

          <section v-if="session && (activeView === 'capture' || activeView === 'behavior')" class="wb-card wb-network-card">
            <div class="wb-section-title">
              <div><span>04 / OBSERVED TRAFFIC</span><h2>接口采集</h2></div>
              <div class="wb-draft-actions">
                <span class="wb-request-count">最近 {{ network.length }} 条</span>
                <button class="wb-secondary" :disabled="creatingDraft || !network.length" @click="createApiDraft">
                  {{ creatingDraft ? '生成中…' : '生成 Mock 草稿' }}
                </button>
                <button v-if="apiDraft" class="wb-secondary" @click="downloadApiDraft">下载 JSON</button>
                <button v-if="apiDraft && activeView === 'behavior'" class="wb-secondary" :disabled="busy || creatingSimulatorDraft || !latestPackage || !apiDraftId" @click="createSimulatorDraft">
                  {{ creatingSimulatorDraft ? '保存中…' : '创建 Simulator 草稿' }}
                </button>
                <button v-if="simulatorDraft?.payload" class="wb-secondary" @click="downloadSimulatorDraft">下载 Simulator 草稿</button>
              </div>
            </div>
            <details v-if="apiDraft" class="wb-draft-preview">
              <summary>已生成 {{ apiDraftEndpointCount }} 条接口草稿 · 展开检查</summary>
              <pre>{{ JSON.stringify(apiDraft, null, 2) }}</pre>
              <p>规则来自观察到的请求样例，不是最终可发布的设备配置；请审查后再接入 Simulator。</p>
            </details>
            <div v-if="activeView === 'behavior' && apiDraft && latestPackage" class="wb-behavior-editor">
              <label class="wb-label" for="simulator-goal">模拟能力范围</label>
              <textarea id="simulator-goal" v-model="simulatorGoal" rows="2" placeholder="例如：设备参数读取、修改与保存；不包含录像播放" />
              <select v-model="selectedBaselineId" class="wb-input"><option value="">无真实设备基线（演示）</option><option v-for="item in baselines.filter(item => item.packageId === latestPackage?.id && item.apiDraftId === apiDraftId)" :key="item.id" :value="item.id">基线 {{ item.id.slice(0, 8) }}</option></select>
              <div class="wb-proposal-actions"><button class="wb-secondary" :disabled="proposingBehavior || !simulatorGoal.trim()" @click="proposeBehaviorV2">{{ proposingBehavior ? '生成中…' : '生成 v2 提案' }}</button><button class="wb-secondary" @click="startManualV2">手工创建 v2</button></div>
              <V2ProposalReview v-if="pendingV2" :proposal="pendingV2" :current="parsedBehavior" @adopt="adoptV2" @cancel="pendingV2 = null" />
            </div>
            <div v-if="activeView === 'behavior' && apiDraft && editingApiDraftId === apiDraftId" class="wb-behavior-editor">
              <details><summary>旧版 Mock 提案</summary><button class="wb-secondary" :disabled="proposingBehavior || !simulatorGoal.trim()" @click="proposeBehavior">生成旧版草案</button></details>
              <div v-if="pendingProposal" class="wb-proposal-review">
                <strong>待审查提案 · {{ proposalChanges.length }} 条规则</strong>
                <div v-for="change in proposalChanges" :key="change.id" class="wb-proposal-row">
                  <span>{{ change.kind }} · {{ change.id }}</span>
                  <small>{{ JSON.stringify(change.match) }} · 变化字段：{{ change.changed.join('、') }}</small>
                  <small>采集来源：{{ JSON.stringify(change.source || '未提供') }}</small>
                </div>
                <p v-for="(note, index) in pendingProposal.notes" :key="index">{{ note }}</p>
                <div class="wb-proposal-actions"><button class="wb-primary" @click="adoptProposal">采用到待确认草稿</button><button class="wb-secondary" @click="pendingProposal = null">取消提案</button></div>
              </div>
              <ul v-if="behaviorNotes.length"><li v-for="(note, index) in behaviorNotes" :key="index">{{ note }}</li></ul>
              <label class="wb-label" for="simulator-behavior">接口与状态规则 · 待确认</label>
              <textarea id="simulator-behavior" v-model="behaviorJson" rows="16" spellcheck="false" />
            </div>
            <details v-if="simulatorDraft?.payload" class="wb-draft-preview">
              <summary>Simulator 草稿 v{{ simulatorDraft.versionNumber }} · {{ simulatorDraft.status }} · 待审核</summary>
              <pre>{{ JSON.stringify(simulatorDraft.payload, null, 2) }}</pre>
              <p>草稿已固定关联静态包和接口采集版本，尚未发布。</p>
            </details>
            <div v-if="activeView === 'capture' && approvals.length" class="wb-approvals">
              <div v-for="item in approvals" :key="item.id" class="wb-approval">
                <div><small>需要确认 · 真实设备请求</small><strong>{{ item.method }} {{ item.url }}</strong></div>
                <div class="wb-approval-actions">
                  <button class="wb-reject" @click="resolveApproval(item, false)">拒绝</button>
                  <button class="wb-approve" @click="resolveApproval(item, true)">批准一次</button>
                </div>
              </div>
            </div>
            <div v-if="activeView === 'capture' && session" class="wb-baseline">
              <div class="wb-section-title"><div><span>BASELINE</span><h2>设备观察基线</h2></div>
                <button class="wb-secondary" :disabled="baselineBusy || !network.length" @click="saveObservations">保存脱敏观察</button></div>
              <template v-if="capturedEvidence.length">
                <label v-for="item in capturedEvidence" :key="item.id" class="wb-baseline-item">
                  <input v-model="baselineSelection" type="checkbox" :value="item.id" :disabled="item.truncated || item.redacted || (!item.bodyUsable && !['GET', 'HEAD'].includes(item.method))" />
                  {{ item.sequence }} · {{ item.method }} {{ item.path }} · {{ item.status }}
                </label>
                <input v-model="baselineAction" class="wb-input" maxlength="200" placeholder="操作意图，例如：保存配置" />
                <div class="wb-two-fields"><select v-model="baselineCheckpointKind" class="wb-input"><option value="TEXT_CONTAINS">页面文字</option><option value="URL_EQUALS">页面地址</option><option value="ELEMENT_VISIBLE">元素可见</option></select>
                  <input v-model="baselineCheckpointValue" class="wb-input" placeholder="确认的页面结果（可留空）" /></div>
                <button class="wb-secondary" :disabled="baselineBusy || !apiDraftId || !latestPackage || !baselineAction.trim() || !baselineSelection.length" @click="freezeBaseline">冻结所选基线</button>
              </template>
              <p v-if="baselines.length">已冻结 {{ baselines.length }} 份基线</p>
            </div>
            <div v-if="activeView === 'capture'" class="wb-table-scroll">
              <table class="wb-table">
                <thead><tr><th>方法</th><th>请求地址</th><th>状态</th><th>类型</th><th>时间</th></tr></thead>
                <tbody>
                  <tr v-for="item in [...network].reverse()" :key="item.id">
                    <td><span class="wb-method" :class="(item.method || 'event').toLowerCase()">{{ item.method || 'EVENT' }}</span></td>
                    <td class="wb-request-cell"><span>{{ item.url || item.failure }}</span><small v-if="item.responseSample">已捕获脱敏 JSON 响应样例</small></td>
                    <td><span class="wb-status" :class="{ failed: item.failure || (item.status && item.status >= 400) }">{{ item.status || (item.failure ? 'ERR' : '—') }}</span></td>
                    <td class="wb-type-cell">{{ item.contentType || item.resourceType || '—' }}</td>
                    <td class="wb-time-cell">{{ new Date(item.startedAt).toLocaleTimeString('zh-CN', { hour12: false }) }}</td>
                  </tr>
                  <tr v-if="!network.length"><td colspan="5" class="wb-table-empty">在右侧操作真实页面后，请求会显示在这里。</td></tr>
                </tbody>
              </table>
            </div>
            <p v-if="activeView === 'capture'" class="wb-footnote">请求正文不保存；写请求与疑似修改配置的 GET 默认暂停。WebSocket 默认阻止；显式放行后消息内容不受逐次审批。</p>
          </section>
          <section v-if="activeView === 'behavior'" class="wb-workflow-panel">
            <div class="wb-section-title"><div><span>BEHAVIOR / V2</span><h2>行为修订</h2></div></div>
            <p v-if="!selectedDraft && !apiDraft">先在真实采集中生成接口草稿，再保存并导入 v2 行为修订。</p>
            <template v-if="selectedDraft">
              <div class="wb-saved-draft-row"><strong>v{{ selectedDraft.versionNumber }}</strong><span>{{ selectedDraft.payload?.schemaVersion || '加载修订后可查看协议' }}</span></div>
              <button v-if="selectedDraft.payload?.schemaVersion === 'skilllab.simulator-draft.v1'" class="wb-secondary" :disabled="savingRevision" @click="importDraft(selectedDraft)">导入为 v2 待核实修订</button>
              <button v-else-if="selectedDraft.payload?.schemaVersion === 'skilllab.simulator-behavior.v2'" class="wb-secondary" @click="editAsNewRevision">以此修订新版本</button>
            </template>
            <div v-if="behaviorRevisionJson" class="wb-behavior-editor">
              <div class="wb-editor-mode"><strong>v2 行为模型</strong><button class="wb-secondary" @click="advancedBehavior = !advancedBehavior">{{ advancedBehavior ? '结构化编辑' : '高级 JSON' }}</button></div>
              <template v-if="!advancedBehavior && parsedBehavior">
                <label class="wb-label" for="behavior-scope">模拟范围</label>
                <input id="behavior-scope" class="wb-input" :value="behaviorScope?.name" @change="updateScopeName(($event.target as HTMLInputElement).value)" />
                <label class="wb-label" for="behavior-initial">初始状态 JSON</label>
                <textarea id="behavior-initial" :key="behaviorRevisionJson" rows="5" :value="JSON.stringify(parsedBehavior.initialState, null, 2)" @change="updateInitialState(($event.target as HTMLTextAreaElement).value)" />
                <div v-for="(rule, index) in behaviorRules" :key="String(rule.id)" class="wb-rule-editor">
                  <div><strong>{{ rule.id }}</strong><span>{{ (rule.match as Record<string, unknown>)?.method }} {{ (rule.match as Record<string, unknown>)?.path }}</span></div>
                  <label><input type="checkbox" :checked="(behaviorScope?.criticalRuleIds as string[] || []).includes(String(rule.id))" @change="updateCriticalRule(String(rule.id), ($event.target as HTMLInputElement).checked)" /> 关键接口</label>
                  <small>证据：{{ JSON.stringify(rule.evidence || {}) }}</small>
                  <label class="wb-label" :for="'rule-evidence-'+index">证据说明 / 推断依据</label>
                  <input :id="'rule-evidence-'+index" class="wb-input" :value="(rule.evidence as Record<string, unknown>)?.note" @change="updateBehavior(model => { ((model.rules as Array<Record<string, unknown>>)[index].evidence as Record<string, unknown>).note = ($event.target as HTMLInputElement).value })" />
                </div>
                <div class="wb-editor-mode"><strong>验证用例</strong><button class="wb-secondary" @click="addVerificationCase">添加用例</button></div>
                <div v-for="(testCase, caseIndex) in verificationCases" :key="String(testCase.id)" class="wb-rule-editor">
                  <label class="wb-label">用例名称 <input class="wb-input" :value="testCase.id" @change="editVerificationCase(caseIndex, 'id', ($event.target as HTMLInputElement).value)" /></label>
                  <div v-for="(step, stepIndex) in (testCase.steps as Array<Record<string, unknown>> || [])" :key="stepIndex" class="wb-verification-step">
                    <select class="wb-input" :value="step.method" @change="editVerificationStep(caseIndex, stepIndex, 'method', ($event.target as HTMLSelectElement).value)"><option>GET</option><option>POST</option><option>PUT</option><option>PATCH</option><option>DELETE</option></select>
                    <input class="wb-input" :value="step.path" placeholder="/api/config" @change="editVerificationStep(caseIndex, stepIndex, 'path', ($event.target as HTMLInputElement).value)" />
                    <input class="wb-input" :value="step.expectedRuleId" placeholder="命中规则 ID" @change="editVerificationStep(caseIndex, stepIndex, 'expectedRuleId', ($event.target as HTMLInputElement).value)" />
                    <input class="wb-input" type="number" min="200" max="399" :value="step.expectedStatus" aria-label="期望状态码" @change="editVerificationStep(caseIndex, stepIndex, 'expectedStatus', ($event.target as HTMLInputElement).value)" />
                    <label>请求体 JSON <textarea :disabled="step.method === 'GET' || step.method === 'HEAD'" :value="JSON.stringify(step.body ?? null, null, 2)" rows="3" @change="editVerificationStep(caseIndex, stepIndex, 'body', ($event.target as HTMLTextAreaElement).value)" /></label>
                    <label>期望响应 JSON <textarea :value="JSON.stringify(step.expectedBody, null, 2)" rows="3" @change="editVerificationStep(caseIndex, stepIndex, 'expectedBody', ($event.target as HTMLTextAreaElement).value)" /></label>
                    <label><input type="checkbox" :checked="step.reloadBefore === true" @change="editVerificationStep(caseIndex, stepIndex, 'reloadBefore', ($event.target as HTMLInputElement).checked)" /> 此步骤前刷新页面</label>
                  </div>
                  <button class="wb-secondary" @click="addVerificationStep(caseIndex)">添加请求步骤</button>
                  <label class="wb-label">最终状态断言 JSON <textarea :value="JSON.stringify(testCase.expectedState, null, 2)" rows="4" @change="editVerificationCase(caseIndex, 'expectedState', ($event.target as HTMLTextAreaElement).value)" /></label>
                </div>
              </template>
              <template v-else><label class="wb-label" for="behavior-v2-json">高级 JSON</label><textarea id="behavior-v2-json" v-model="behaviorRevisionJson" rows="20" spellcheck="false" /></template>
              <button class="wb-primary" :disabled="savingRevision || !revisionSourceId" @click="confirmRevision">{{ savingRevision ? '保存中…' : '人工确认并保存新修订' }}</button>
            </div>
          </section>
          <section v-if="activeView === 'validation'" class="wb-workflow-panel">
            <div class="wb-section-title"><div><span>VALIDATION</span><h2>隔离验证</h2></div></div>
            <p>选择已保存的 v2 修订，在隔离预览中检查设备页面，并为关键链路填写请求、期望响应与最终状态断言。</p>
            <div v-for="draft in simulatorDrafts" :key="draft.id" class="wb-saved-draft-row">
              <span>v{{ draft.versionNumber }} · {{ draft.status }}</span>
              <button class="wb-secondary" :disabled="openingPreview" @click="openPreview(draft)">隔离预览</button>
              <button class="wb-secondary" @click="selectDraft(draft)">查看修订</button>
            </div>
            <button v-if="selectedDraft?.payload?.schemaVersion === 'skilllab.simulator-behavior.v2'" class="wb-primary" :disabled="candidateBusy" @click="makeCandidate">登记候选版本</button>
            <button v-if="candidate?.status === 'CANDIDATE'" class="wb-secondary" :disabled="validating" @click="runValidation">{{ validating ? '验证中…' : '运行工程验证' }}</button>
            <div v-if="candidate" class="wb-report-lookup"><input v-model="reportIdInput" class="wb-input" placeholder="报告 ID" aria-label="报告 ID" /><button class="wb-secondary" :disabled="!reportIdInput.trim()" @click="loadReport">查看报告</button></div>
            <div v-if="validationReport" class="wb-validation-report">
              <strong>{{ validationReport.status === 'PASSED' ? '验证通过' : '验证阻断' }} · {{ validationReport.runtimeVersion }}</strong>
              <p>真实设备一致性：{{ validationReport.consistency?.status || 'NOT_TESTED' }}</p>
              <p v-for="(difference, index) in validationReport.consistency?.differences || []" :key="'diff-'+index">差异：{{ JSON.stringify(difference) }}</p>
              <p v-for="(item, index) in validationReport.consistency?.uncovered || []" :key="'uncovered-'+index">未覆盖：{{ JSON.stringify(item) }}</p>
              <p v-for="(finding, index) in validationReport.blockers" :key="'blocker-'+index" class="wb-blocker">{{ finding.caseId ? finding.caseId + ' · ' : '' }}{{ finding.message }}</p>
              <button v-if="validationReport.blockers.length" class="wb-secondary" @click="activeView = 'behavior'">修改行为修订</button>
              <p v-for="(finding, index) in validationReport.warnings" :key="'warning-'+index" class="wb-warning">{{ finding.message }}</p>
              <div v-for="item in validationReport.cases" :key="item.id" class="wb-report-case">
                <strong>{{ item.id }} · {{ item.passed ? '通过' : '失败' }}</strong>
                <span v-for="(step, index) in item.steps" :key="index">{{ step.method }} {{ step.path }} · {{ step.status }} · {{ step.passed ? '通过' : '失败' }}</span>
              </div>
              <label v-if="validationReport.warnings.length" class="wb-risk-confirm"><input v-model="acknowledgeWarnings" type="checkbox" /> 我已审查并接受报告中的非阻断风险</label>
              <button v-if="validationReport.status === 'PASSED' && candidate?.status === 'CANDIDATE'" class="wb-primary"
                :disabled="candidateBusy || (!!validationReport.warnings.length && !acknowledgeWarnings)" @click="enableValidated">启用制作者演示</button>
            </div>
          </section>
          <section v-if="activeView === 'version'" class="wb-workflow-panel">
            <div class="wb-section-title"><div><span>VERSION</span><h2>版本状态</h2></div><button v-if="candidate" class="wb-secondary" @click="refreshCandidate">刷新</button></div>
            <template v-if="candidate"><p>版本 {{ candidate.versionId }} · {{ candidate.status }}</p><p v-if="candidate.status === 'DEMO_ENABLED'">仅演示，未验证真实设备一致性</p><p>包 SHA-256 {{ candidate.packageSha256 }}</p><p>行为 SHA-256 {{ candidate.behaviorSha256 }}</p><button v-if="candidate.status === 'CANDIDATE'" class="wb-primary" @click="activeView = 'validation'">前往验证</button></template>
            <p v-else>尚未登记候选版本。选择 v2 行为修订并完成隔离预览后登记。</p>
          </section>
        </main>
      </div>
    </template>
  </section>
</template>

<style scoped>
.ai-workbench{min-height:100%;padding:25px 28px 34px;background:#f2f4f7;color:#192435}
.wb-views{display:flex;gap:0;overflow-x:auto;margin-bottom:16px;border-bottom:1px solid #dce3eb}
.wb-views button{display:flex;align-items:center;gap:8px;flex:0 0 auto;padding:11px 16px;border:0;border-bottom:2px solid transparent;background:transparent;color:#637286;font-family:inherit;font-size:12px;font-weight:600;cursor:pointer}
.wb-views button.active{border-bottom-color:#315ec6;color:#234da9}
.wb-views button span{display:grid;width:19px;height:19px;place-items:center;border-radius:50%;background:#e2e8ef;font-size:10px}
.wb-views button.active span{background:#315ec6;color:#fff}
.wb-workflow-panel{min-width:0;padding:18px 0;border-top:1px solid #dce3eb;color:#4c5b6e;font-size:12px;overflow-wrap:anywhere}
.wb-workflow-panel p{line-height:1.6}.wb-workflow-panel .wb-saved-draft-row{padding:10px 0;border-bottom:1px solid #e4e9ef}
.wb-validation-report{display:grid;gap:8px;margin-top:16px;padding:12px 0;border-top:1px solid #dce3eb}
.wb-report-lookup{display:flex;gap:8px;max-width:500px;margin-top:12px}
.wb-validation-report p{margin:0}.wb-blocker{color:#a33f3f}.wb-warning{color:#95691d}
.wb-report-case{display:grid;gap:4px;padding:9px 0;border-top:1px solid #e4e9ef}.wb-risk-confirm{display:flex;align-items:center;gap:8px}
.wb-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:19px}
.wb-eyebrow,.wb-section-title>div>span,.wb-kicker{display:block;margin:0 0 5px;color:#7b8797;font-size:9px;font-weight:750;letter-spacing:1px}
.wb-header h1{margin:0;font-size:23px;letter-spacing:-.5px}
.wb-subtitle{margin:6px 0 0;color:#77869a;font-size:12px}
.wb-layout{display:grid;grid-template-columns:minmax(300px,370px) minmax(0,1fr);gap:15px;align-items:start}
.wb-left,.wb-right{display:flex;flex-direction:column;gap:15px;min-width:0}
.wb-card{overflow:hidden;border:1px solid #e2e7ee;border-radius:12px;background:#fff;box-shadow:0 3px 12px rgba(24,39,58,.035)}
.wb-project-card,.wb-command-card{padding:17px}
.wb-section-title{display:flex;justify-content:space-between;align-items:center;margin-bottom:13px}
.wb-section-title h2{margin:0;font-size:14px}
.wb-icon-button,.wb-quiet,.wb-secondary,.wb-primary,.wb-approve,.wb-reject{border:0;border-radius:7px;padding:8px 11px;font:inherit;font-size:11px;font-weight:650;cursor:pointer}
.wb-icon-button,.wb-quiet,.wb-secondary{border:1px solid #dfe5ec;background:white;color:#46556a}
.wb-icon-button{width:29px;height:29px;padding:0;font-size:17px}
.wb-primary{background:#315ec6;color:white}
.wb-primary:hover:not(:disabled){background:#264da8}
.wb-secondary:hover:not(:disabled){background:#f5f7fa}
.wb-primary:disabled,.wb-secondary:disabled{cursor:not-allowed;opacity:.5}
.wb-label{display:block;margin:0 0 6px;color:#596779;font-size:10px;font-weight:700}
.wb-input,.wb-key-form input,.wb-target-form input,.wb-manual-controls input,.wb-composer textarea{width:100%;min-width:0;padding:9px 10px;border:1px solid #dfe5ec;border-radius:7px;background:white;color:#2e3c50;font:inherit;font-size:11px;outline:none}
.wb-input:focus,.wb-key-form input:focus,.wb-target-form input:focus,.wb-manual-controls input:focus,.wb-composer textarea:focus{border-color:#91a9df;box-shadow:0 0 0 3px rgba(49,94,198,.08)}
.wb-create-form{display:flex;flex-direction:column;gap:7px;padding-top:13px}
.wb-two-fields{display:grid;grid-template-columns:1fr 1fr;gap:7px}
.wb-full{width:100%}
.wb-package{margin-top:16px;padding-top:14px;border-top:1px solid #edf0f4}
.wb-package-heading,.wb-package-result>div{display:flex;justify-content:space-between;align-items:center;gap:8px}
.wb-package-heading small,.wb-package-result>div span{color:#8c98a7;font-size:9px}
.wb-upload{display:flex;flex-direction:column;align-items:center;gap:4px;padding:14px 8px;border:1px dashed #cbd5e2;border-radius:8px;color:#48566c;font-size:11px;cursor:pointer;text-align:center}
.wb-upload:hover{border-color:#7190d5;background:#f9faff}
.wb-upload small{color:#8995a4;font-size:9px}
.wb-upload-mark,.wb-key-mark{display:grid;place-items:center;border-radius:50%;background:#eaf0ff;color:#315ec6}
.wb-upload-mark{width:24px;height:24px;font-size:16px}
.wb-file{display:none}
.wb-package-result{margin-top:9px;padding:9px;border-radius:7px;background:#f7f9fb}
.wb-package-result>div{color:#3b4a5f;font-size:10px}
.wb-package-result>small,.wb-package-result p{display:block;margin:6px 0 0;color:#8b97a6;font-size:9px}
.wb-compat-warning{color:#a56821!important}.wb-compat-ok{color:#32835a!important}
.wb-live{color:#96a1af;font-size:9px;font-weight:800;letter-spacing:.8px}.wb-live.active{color:#28835b}
.wb-timeline{max-height:220px;min-height:110px;overflow:auto;border-top:1px solid #eef1f5;border-bottom:1px solid #eef1f5}
.wb-event{display:grid;grid-template-columns:48px 1fr;gap:7px;padding:7px 1px;border-bottom:1px solid #f1f3f6}
.wb-event time{color:#9aa4b1;font:9px ui-monospace,monospace}.wb-event p{margin:0;color:#59687b;font-size:10px;line-height:1.45;overflow-wrap:anywhere}
.wb-event.command p{color:#315ec6}.wb-event.ai p{color:#694ba1}.wb-event.error p{color:#b34242}.wb-event.warning p{color:#9a6729}.wb-event.success p{color:#2c8058}
.wb-empty-timeline{display:grid;min-height:108px;padding:17px;place-items:center;color:#98a3b1;font-size:10px;text-align:center}
.wb-composer{display:grid;grid-template-columns:1fr auto;gap:7px;padding-top:11px}
.wb-composer textarea{resize:vertical;min-height:56px}.wb-composer button{align-self:start;white-space:nowrap}
.wb-composer small{grid-column:1/-1;color:#98a2af;font-size:9px}
.wb-device-card{min-height:525px}
.wb-behavior-editor{display:grid;gap:8px;margin:12px 0;font-size:12px}
.wb-behavior-editor textarea{width:100%;box-sizing:border-box;resize:vertical;min-width:0;font:12px/1.5 ui-monospace,monospace}
.wb-behavior-editor button{justify-self:start}
.wb-proposal-review{display:grid;gap:8px;padding:12px;border:1px solid #d7e1ec;border-radius:6px;background:#f8fafc;overflow-wrap:anywhere}
.wb-proposal-row{display:grid;gap:3px;padding:7px 0;border-top:1px solid #e3e9ef}
.wb-proposal-row small{color:#657387}.wb-proposal-actions{display:flex;flex-wrap:wrap;gap:7px}
.wb-editor-mode,.wb-rule-editor>div:first-child{display:flex;align-items:center;justify-content:space-between;gap:10px}
.wb-rule-editor{display:grid;gap:8px;padding:12px 0;border-top:1px solid #e0e6ee;overflow-wrap:anywhere}
.wb-rule-editor small{color:#68788a}.wb-rule-editor label:not(.wb-label){display:flex;align-items:center;gap:6px}
.wb-verification-step{display:grid;grid-template-columns:100px minmax(180px,1fr) minmax(130px,1fr) 92px;gap:8px;align-items:center;padding:10px 0;border-top:1px solid #e5eaf0}
.wb-verification-step label{display:grid!important;gap:5px}.wb-verification-step label:last-child{display:flex!important;grid-column:1/-1}
.wb-verification-step label:has(textarea){grid-column:span 2}.wb-verification-step textarea{min-width:0}
@media(max-width:1050px){.wb-verification-step{grid-template-columns:1fr 1fr}.wb-verification-step label:has(textarea){grid-column:span 1}}
.wb-saved-drafts{display:grid;gap:7px;margin-top:12px}
.wb-saved-draft-row{display:flex;flex-wrap:wrap;align-items:center;gap:6px;font-size:11px}
.wb-saved-draft-row>span{flex:1 1 160px;overflow-wrap:anywhere}
.wb-link-button{border:0;background:transparent;padding:4px;color:#315ec6;cursor:pointer;font-size:11px}
.wb-preview{min-width:0;border-top:2px solid #32835a;background:#fff}
.wb-preview .wb-device-toolbar{flex-wrap:wrap;font-size:11px}
.wb-preview iframe{display:block;width:100%;height:600px;border:0}
.wb-preview-events{max-height:160px;overflow:auto;padding:8px 24px;font:11px/1.6 ui-monospace,monospace;overflow-wrap:anywhere}
.wb-device-toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:14px 16px;border-bottom:1px solid #edf0f4}
.wb-device-toolbar>div strong{color:#344257;font-size:12px}
.wb-target-form{display:flex;flex:1;max-width:660px;align-items:center;gap:6px}
.wb-target-form input{height:33px;flex:1}
.wb-url-tag{padding:5px 6px;border-radius:5px;background:#eff3f8;color:#748198;font-size:8px;font-weight:800}
.wb-url-tag.insecure{background:#fff1df;color:#a9681e}
.wb-http-warning{display:flex;align-items:center;gap:8px;margin:11px 14px 0;padding:8px 10px;border:1px solid #f1dfc7;border-radius:7px;background:#fff9f0;color:#8d642f;font-size:9px;line-height:1.45}
.wb-http-warning b{display:grid;width:16px;height:16px;flex:0 0 16px;place-items:center;border-radius:50%;background:#f3dfc3}
.wb-browser-empty{display:flex;min-height:420px;flex-direction:column;align-items:center;justify-content:center;padding:25px;background:radial-gradient(ellipse at center,#f8faff 0,#fff 68%);text-align:center}
.wb-orbit{display:flex;align-items:center;gap:5px;margin-bottom:19px}.wb-orbit i{width:8px;height:8px;border-radius:50%;background:#d7e0f5}.wb-orbit i:nth-child(2){width:13px;height:13px;background:#95ade2}
.wb-browser-empty h3{margin:0;color:#354359;font-size:14px}.wb-browser-empty p{max-width:385px;margin:8px 0 15px;color:#8995a5;font-size:10px;line-height:1.6}
.wb-chips{display:flex;gap:6px}.wb-chips span{padding:5px 8px;border:1px solid #e5eaf1;border-radius:99px;color:#79879a;font-size:8px}
.wb-browser-meta{display:flex;justify-content:space-between;align-items:center;gap:10px;padding:8px 13px;color:#8995a4;font-size:9px}
.wb-origin{display:flex;min-width:0;align-items:center;gap:6px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.wb-origin i{width:6px;height:6px;flex:0 0 6px;border-radius:50%;background:#37a46c}
.wb-request-count{flex:0 0 auto;color:#8995a4;font-size:9px}
.wb-stage{position:relative;display:grid;min-height:220px;place-items:center;overflow:hidden;background:#e0e5ec}
.wb-stage img{display:block;width:100%;max-height:calc(100vh - 320px);object-fit:contain;cursor:crosshair;user-select:none}
.wb-stage-loading{display:grid;min-height:300px;place-items:center;color:#8995a5;font-size:11px}
.wb-stage>small{position:absolute;right:8px;bottom:8px;padding:4px 6px;border-radius:5px;background:rgba(25,35,50,.65);color:white;font-size:8px;pointer-events:none}
.wb-manual-controls{display:flex;gap:6px;padding:9px 11px;border-top:1px solid #e9edf2}.wb-manual-controls input{flex:1}.wb-key-button{padding-left:9px;padding-right:9px}
.wb-network-card{padding:15px 17px 11px}.wb-network-card .wb-section-title{margin-bottom:10px}
.wb-draft-actions{display:flex;align-items:center;gap:7px}.wb-draft-preview{margin:0 0 11px;padding:9px;border:1px solid #e4e9f0;border-radius:7px;background:#f9fafc;color:#59687b;font-size:9px}
.wb-draft-preview summary{cursor:pointer;font-weight:700}.wb-draft-preview pre{max-height:260px;overflow:auto;padding:9px;border-radius:5px;background:#202a38;color:#dce6f4;font:9px/1.5 ui-monospace,monospace;white-space:pre-wrap;overflow-wrap:anywhere}
.wb-draft-preview p{margin:7px 0 0;color:#9a6729}
.wb-approvals{display:flex;flex-direction:column;gap:7px;margin-bottom:10px}
.wb-approval{display:flex;justify-content:space-between;align-items:center;gap:10px;padding:9px;border:1px solid #f0d8b8;border-radius:7px;background:#fffaf2}
.wb-approval>div:first-child{display:flex;min-width:0;flex-direction:column;gap:4px}.wb-approval small{color:#9a6729;font-size:8px;font-weight:750}.wb-approval strong{overflow:hidden;color:#52432e;font:9px ui-monospace,monospace;text-overflow:ellipsis;white-space:nowrap}
.wb-approval-actions{display:flex;flex:0 0 auto;gap:5px}.wb-reject{border:1px solid #f0d1d1;background:white;color:#ae4646}.wb-approve{background:#31845a;color:white}
.wb-table-scroll{max-height:280px;overflow:auto}.wb-table{width:100%;border-collapse:collapse;text-align:left}
.wb-table th{position:sticky;top:0;padding:7px;background:#f8f9fb;color:#8b96a5;font-size:8px;font-weight:700}
.wb-table td{padding:7px;border-top:1px solid #f0f2f5;color:#68778b;font-size:9px}
.wb-request-cell{max-width:450px}.wb-request-cell>span{display:block;overflow:hidden;color:#3e4b5f;font:9px ui-monospace,monospace;text-overflow:ellipsis;white-space:nowrap}.wb-request-cell small{color:#9aa4b1;font-size:8px}
.wb-method{display:inline-block;min-width:39px;padding:3px 4px;border-radius:4px;background:#eef2f7;color:#65748a;font:700 8px ui-monospace,monospace;text-align:center}
.wb-method.get,.wb-method.head{background:#e8f5ee;color:#31815a}.wb-method.post,.wb-method.put,.wb-method.patch,.wb-method.delete{background:#fff2df;color:#9a6729}
.wb-status{color:#31815a;font:9px ui-monospace,monospace}.wb-status.failed{color:#c34b4b}.wb-type-cell,.wb-time-cell{white-space:nowrap}
.wb-baseline{display:grid;gap:10px;padding:14px 0;border-top:1px solid #dce3e6}.wb-baseline-item{display:flex;align-items:center;gap:8px;font-size:12px;overflow-wrap:anywhere}
.wb-table-empty{height:75px;color:#98a3b1!important;text-align:center}.wb-footnote{margin:9px 0 0;color:#99a3af;font-size:8px}
.wb-key-gate{max-width:440px;margin:8vh auto;padding:26px;border:1px solid #e2e7ee;border-radius:13px;background:white;text-align:center}
.wb-key-mark{width:40px;height:40px;margin:0 auto 11px;font-size:23px}.wb-key-gate h2{margin:0;font-size:16px}.wb-key-gate p{color:#77869a;font-size:10px;line-height:1.6}
.wb-key-form{display:flex;gap:7px;margin-top:15px}.wb-key-form input{flex:1}
.wb-error{margin:11px 0;padding:9px 11px;border:1px solid #f0d0d0;border-radius:7px;background:#fff5f5;color:#a54040;font-size:10px}
@media(max-width:1050px){.ai-workbench{padding:18px 15px}.wb-layout{grid-template-columns:1fr}.wb-left{display:grid;grid-template-columns:1fr 1fr;align-items:start}}
@media(max-width:700px){.wb-header{align-items:flex-start;gap:8px}.wb-header h1{font-size:19px}.wb-left{display:flex}.wb-device-toolbar{align-items:flex-start;flex-direction:column}.wb-target-form{width:100%;max-width:none}.wb-stage img{max-height:60vh}.wb-approval{align-items:flex-start;flex-direction:column}.wb-table th:nth-child(4),.wb-table td:nth-child(4){display:none}.wb-views button{padding:10px}.wb-layout{gap:8px}.wb-key-form{flex-direction:column}}
</style>
