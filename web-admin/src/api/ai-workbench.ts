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

export interface SimulatorPreview {
  previewId: string
  projectId: string
  draftId: string
  versionNumber: number
  previewUrl: string
  expiresAt: string
  runtimeVersion: string
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
