export interface DeviceProject {
  id: string
  name: string
  manufacturer: string | null
  model: string | null
  createdAt: string
  packages?: PackageVersion[]
}

export interface PackageVersion {
  id: string
  filename: string
  sha256: string
  sizeBytes: number
  uploadedAt: string
  scan?: {
    entryCount: number
    expandedBytes: number
    hasIndexHtml: boolean
    pathCompatibility: string
    rootPathReferenceHtml: string[]
  }
}

export interface BrowserSession {
  id: string
  projectId: string
  targetUrl: string
  status: string
  createdAt: string
}

export interface NetworkEntry {
  id: string
  method?: string
  url?: string
  resourceType?: string
  startedAt: string
  hasBody?: boolean
  status?: number | null
  contentType?: string | null
  responseSample?: unknown
  failure?: string | null
}

export interface CaptureEvidence {
  id: string
  sequence: number
  method: string
  path: string
  status: number
  bodyUsable: boolean
  truncated: boolean
  redacted: boolean
}

export interface DeviceBaseline {
  id: string
  projectId: string
  packageId: string
  packageSha256: string
  apiDraftId: string
  sessionId: string
  snapshotSha256: string
  scenario: { steps: Array<Record<string, unknown>> }
}

export function listCaptureEvidence(projectId: string, sessionId: string): Promise<CaptureEvidence[]> {
  return aiRequest(`/device-projects/${projectId}/sessions/${sessionId}/evidence`)
}

export function listBaselines(projectId: string): Promise<DeviceBaseline[]> {
  return aiRequest(`/device-projects/${projectId}/baselines`)
}

export function createBaseline(projectId: string, packageId: string, apiDraftId: string, sessionId: string,
  scenario: { steps: Array<Record<string, unknown>> }): Promise<DeviceBaseline> {
  return aiRequest(`/device-projects/${projectId}/baselines`, {
    method: 'POST', body: JSON.stringify({ packageId, apiDraftId, sessionId, scenario })
  })
}

export interface PendingApproval {
  id: string
  method: string
  url: string
  createdAt: string
}

export interface SimulatorDraft {
  id: string
  projectId: string
  packageId: string
  sourceApiDraftId: string
  versionNumber: number
  status: string
  createdAt: string
  payload?: Record<string, unknown>
}

export interface V2Proposal {
  behavior: Record<string, unknown>
  sourceFingerprint: string
  apiDraftId: string
  baselineId: string | null
  gaps: string[]
}

export function proposeV2(projectId: string, input: { packageId: string; apiDraftId: string;
  baselineId?: string; currentDraftId?: string; goal: string }): Promise<V2Proposal> {
  return aiRequest(`/device-projects/${projectId}/v2-proposals`, { method: 'POST', body: JSON.stringify(input) })
}

export interface SimulatorPreview {
  previewId: string
  projectId: string
  draftId: string
  versionNumber: number
  previewUrl: string
  expiresAt: string
  runtimeVersion: string
}

export interface SimulatorVersion {
  versionId: string
  draftId: string
  projectId: string
  status: 'CANDIDATE' | 'ENABLED' | 'DISABLED'
  packageSha256: string
  behaviorSha256: string
}

export interface ValidationFinding { code: string; message: string; caseId?: string | null }
export interface ValidationReport {
  reportId: string
  versionId: string
  status: 'PASSED' | 'BLOCKED'
  runtimeVersion: string
  packageSha256: string
  behaviorSha256: string
  blockers: ValidationFinding[]
  warnings: ValidationFinding[]
  cases: Array<{ id: string; passed: boolean; steps: Array<{ method: string; path: string; status: number; passed: boolean }> }>
  pageErrors: string[]
  externalRequests: string[]
}

export function validateCandidate(projectId: string, versionId: string): Promise<ValidationReport> {
  return aiRequest('/device-projects/' + projectId + '/simulator-versions/' + versionId + '/validate', { method: 'POST' })
}

export function getValidationReport(projectId: string, versionId: string, reportId: string): Promise<ValidationReport> {
  return aiRequest('/device-projects/' + projectId + '/simulator-versions/' + versionId + '/reports/' + reportId)
}

export function enableCandidate(projectId: string, versionId: string, reportId: string, acknowledgeWarnings: boolean): Promise<SimulatorVersion> {
  return aiRequest('/device-projects/' + projectId + '/simulator-versions/' + versionId + '/enable', {
    method: 'POST', body: JSON.stringify({ reportId, acknowledgeWarnings })
  })
}

export function createBehaviorRevision(projectId: string, sourceApiDraftId: string, behavior: Record<string, unknown>,
  source?: { sourceFingerprint: string; baselineId?: string; currentDraftId?: string }): Promise<SimulatorDraft> {
  return aiRequest('/device-projects/' + projectId + '/simulator-drafts/v2', {
    method: 'POST', body: JSON.stringify({ sourceApiDraftId, behavior, ...source })
  })
}

export function importLegacyDraft(projectId: string, draftId: string): Promise<SimulatorDraft> {
  return aiRequest('/device-projects/' + projectId + '/simulator-drafts/' + draftId + '/import-v1', { method: 'POST' })
}

export function registerCandidate(projectId: string, draftId: string): Promise<SimulatorVersion> {
  return aiRequest('/device-projects/' + projectId + '/simulator-drafts/' + draftId + '/candidate', { method: 'POST' })
}

export function getSimulatorVersion(projectId: string, versionId: string): Promise<SimulatorVersion> {
  return aiRequest('/device-projects/' + projectId + '/simulator-versions/' + versionId)
}

export async function loginCreator(username: string, password: string): Promise<{ userId: string }> {
  const response = await fetch('/api/ai/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  })
  if (!response.ok) throw new Error('账号或密码错误')
  return response.json()
}

export async function currentCreator(): Promise<{ userId: string }> {
  return aiRequest('/auth/me')
}

export async function logoutCreator(): Promise<void> {
  await aiRequest('/auth/logout', { method: 'POST' })
}

export async function aiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const response = await fetch('/api/ai' + path, { ...init, headers })
  if (!response.ok) {
    const result = await response.json().catch(() => ({})) as { error?: string; message?: string }
    throw new Error(result.error || result.message || 'Request failed (' + response.status + ')')
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export async function uploadPackage(projectId: string, file: File): Promise<PackageVersion> {
  const body = new FormData()
  body.append('file', file)
  return aiRequest<PackageVersion>('/device-projects/' + projectId + '/packages', {
    method: 'POST',
    body
  })
}

export async function createSimulatorDraft(
  projectId: string,
  packageId: string,
  apiDraftId: string,
  behavior: { rules: unknown[]; stateModel: Record<string, unknown> } | undefined = undefined
): Promise<SimulatorDraft> {
  return aiRequest<SimulatorDraft>('/device-projects/' + projectId + '/simulator-drafts', {
    method: 'POST',
    body: JSON.stringify({ packageId, apiDraftId, ...behavior })
  })
}

export async function getSimulatorDraft(
  projectId: string,
  draftId: string
): Promise<SimulatorDraft> {
  return aiRequest<SimulatorDraft>(
    '/device-projects/' + projectId + '/simulator-drafts/' + draftId
  )
}

export async function fetchScreenshot(sessionId: string): Promise<Blob> {
  const response = await fetch('/api/ai/sessions/' + sessionId + '/screenshot', {
    headers: {},
    cache: 'no-store'
  })
  if (!response.ok) throw new Error('Screenshot unavailable (' + response.status + ')')
  return response.blob()
}
