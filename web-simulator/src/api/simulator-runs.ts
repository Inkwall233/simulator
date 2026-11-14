export interface SimulatorRun {
  runId: string
  versionId: string
  runUrl: string
  expiresAt: string
  heartbeatMs: number
  demoOnly?: boolean
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch('/api/simulator' + path, {
    ...init, headers: { ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers }
  })
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { error?: string }
    throw new Error(body.error || `请求失败 (${response.status})`)
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}

export const currentCreator = () => request<{ userId: string }>('/auth/me')
export const loginCreator = (username: string, password: string) => request<{ userId: string }>('/auth/login', {
  method: 'POST', body: JSON.stringify({ username, password })
})
export const logoutCreator = () => request<void>('/auth/logout', { method: 'POST' })
export const startRun = (versionId: string) => request<SimulatorRun>(`/versions/${encodeURIComponent(versionId)}/runs`, { method: 'POST' })
export const getRun = (runId: string) => request<SimulatorRun>(`/runs/${encodeURIComponent(runId)}`)
export const heartbeatRun = (runId: string) => request<{ expiresAt: string }>(`/runs/${encodeURIComponent(runId)}/heartbeat`, { method: 'POST' })
export const endRun = (runId: string) => request<void>(`/runs/${encodeURIComponent(runId)}/end`, { method: 'POST' })
