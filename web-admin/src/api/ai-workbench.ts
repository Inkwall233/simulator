const adminKeyStorageKey = 'skilllab.ai.adminKey'

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

export function getSavedAdminKey(): string {
  return sessionStorage.getItem(adminKeyStorageKey) || ''
}

export function saveAdminKey(value: string): void {
  sessionStorage.setItem(adminKeyStorageKey, value)
}

export function clearAdminKey(): void {
  sessionStorage.removeItem(adminKeyStorageKey)
}

export async function aiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('X-SkillLab-AI-Key', getSavedAdminKey())
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const response = await fetch('/api/ai' + path, { ...init, headers })
  if (!response.ok) {
    const result = await response.json().catch(() => ({})) as { error?: string; message?: string }
    if (response.status === 401) clearAdminKey()
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

export async function fetchScreenshot(sessionId: string): Promise<Blob> {
  const response = await fetch('/api/ai/sessions/' + sessionId + '/screenshot', {
    headers: { 'X-SkillLab-AI-Key': getSavedAdminKey() },
    cache: 'no-store'
  })
  if (!response.ok) throw new Error('Screenshot unavailable (' + response.status + ')')
  return response.blob()
}
