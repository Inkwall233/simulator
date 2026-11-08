// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AiWorkbenchView from './AiWorkbenchView.vue'

const project = { id: 'project', name: '测试设备', packages: [] }
const other = { id: 'other', name: '其他设备', packages: [] }
const draft = { id: 'draft', projectId: 'project', packageId: 'pkg', sourceApiDraftId: 'api', versionNumber: 2, status: 'DRAFT', createdAt: '2026-11-08' }
const behavior = { schemaVersion: 'skilllab.simulator-behavior.v2', packageVersion: { id: 'pkg', sha256: 'a'.repeat(64) },
  scope: { name: '配置', criticalRuleIds: [] }, initialState: {}, rules: [], verificationCases: [] }

function mockApi() {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const data = url.endsWith('/auth/me') ? { userId: 'maker' }
      : url.endsWith('/device-projects') ? [project, other]
      : url.endsWith('/device-projects/project') ? project
      : url.endsWith('/device-projects/other') ? other
      : url.endsWith('/device-projects/project/simulator-drafts') ? [draft]
      : url.endsWith('/device-projects/other/simulator-drafts') ? []
      : url.endsWith('/device-projects/project/simulator-drafts/draft') ? { ...draft, payload: behavior }
      : init?.method === 'POST' ? {} : []
    return { ok: true, status: 200, json: async () => data } as Response
  })
}

describe('simulator authoring views', () => {
  beforeEach(() => { vi.stubGlobal('fetch', mockApi()) })
  afterEach(() => { vi.unstubAllGlobals() })

  it('keeps a loaded revision while moving between views', async () => {
    const wrapper = mount(AiWorkbenchView)
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '查看修订')!.trigger('click')
    await flushPromises()
    expect((wrapper.find('#behavior-scope').element as HTMLInputElement).value).toBe('配置')
    await wrapper.findAll('.wb-views button')[3].trigger('click')
    await wrapper.findAll('.wb-views button')[2].trigger('click')
    expect((wrapper.find('#behavior-scope').element as HTMLInputElement).value).toBe('配置')
    await wrapper.findAll('button').find(button => button.text() === '高级 JSON')!.trigger('click')
    expect((wrapper.find('#behavior-v2-json').element as HTMLTextAreaElement).value).toContain('initialState')
    wrapper.unmount()
  })

  it('clears the selected revision on project change', async () => {
    const wrapper = mount(AiWorkbenchView)
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '查看修订')!.trigger('click')
    await flushPromises()
    await wrapper.find('#ai-project-select').setValue('other')
    await flushPromises()
    await wrapper.findAll('.wb-views button')[2].trigger('click')
    expect(wrapper.find('#behavior-v2-json').exists()).toBe(false)
    wrapper.unmount()
  })

  it('discards an AI proposal without persisting a revision', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input)
      const data = url.endsWith('/auth/me') ? { userId: 'maker' }
        : url.endsWith('/device-projects') ? [project]
        : url.endsWith('/device-projects/project') ? project
        : url.endsWith('/device-projects/project/simulator-drafts') ? []
        : url.endsWith('/device-projects/project/sessions') ? { id: 'session', targetUrl: 'https://device.test' }
        : url.endsWith('/sessions/session/network') ? { items: [{ id: 'request', startedAt: '2026-11-08', url: '/api/config' }] }
        : url.endsWith('/sessions/session/pending-approval') ? { items: [] }
        : url.endsWith('/sessions/session/drafts') ? { id: 'api', endpointCount: 1, payload: { mockRules: [{ id: 'read', match: { method: 'GET', path: '/api/config' } }] } }
        : url.endsWith('/sessions/session/simulator-behavior') ? { apiDraftId: 'api', rules: [{ id: 'read', match: { method: 'GET', path: '/api/config' }, status: 200 }], stateModel: { initial: {}, transitions: [] }, notes: [] }
        : []
      if (url.endsWith('/screenshot')) return { ok: true, status: 200, blob: async () => new Blob() } as Response
      return { ok: true, status: 200, json: async () => data } as Response
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:sample', revokeObjectURL: () => {} })
    const wrapper = mount(AiWorkbenchView)
    await flushPromises()
    await wrapper.findAll('.wb-views button')[1].trigger('click')
    await wrapper.find('.wb-target-form input').setValue('https://device.test')
    await wrapper.find('.wb-target-form').trigger('submit')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '生成 Mock 草稿')!.trigger('click')
    await flushPromises()
    await wrapper.find('#simulator-goal').setValue('配置保存')
    await wrapper.findAll('button').find(button => button.text() === 'AI 制作行为草案')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.wb-proposal-review').exists()).toBe(true)
    await wrapper.findAll('button').find(button => button.text() === '取消提案')!.trigger('click')
    expect(wrapper.find('.wb-proposal-review').exists()).toBe(false)
    expect(fetchMock.mock.calls.some(([url, init]) => String(url).endsWith('/simulator-drafts/v2') && init?.method === 'POST')).toBe(false)
    wrapper.unmount()
  })

  it('requires a passing report and explicit warning acknowledgement before enabling', async () => {
    const version = { versionId: 'version', draftId: 'draft', projectId: 'project', status: 'CANDIDATE', packageSha256: 'a', behaviorSha256: 'b' }
    let blocked = true
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      const data = url.endsWith('/auth/me') ? { userId: 'maker' }
        : url.endsWith('/device-projects') ? [project]
        : url.endsWith('/device-projects/project') ? project
        : url.endsWith('/device-projects/project/simulator-drafts') ? [draft]
        : url.endsWith('/device-projects/project/simulator-drafts/draft') ? { ...draft, payload: behavior }
        : url.endsWith('/candidate') ? version
        : url.endsWith('/validate') ? { reportId: blocked ? 'blocked' : 'passed', versionId: 'version', status: blocked ? 'BLOCKED' : 'PASSED',
          runtimeVersion: 'v2', blockers: blocked ? [{ code: 'FAILED', message: '断言失败' }] : [],
          warnings: blocked ? [] : [{ code: 'RISK', message: '推断风险' }], cases: [], pageErrors: [], externalRequests: [] }
        : []
      return { ok: true, status: 200, json: async () => data } as Response
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiWorkbenchView)
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '查看修订')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('.wb-views button')[3].trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '登记候选版本')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('.wb-views button')[3].trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '运行工程验证')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('断言失败')
    expect(wrapper.findAll('button').some(button => button.text() === '启用已验证版本')).toBe(false)
    blocked = false
    await wrapper.findAll('button').find(button => button.text() === '运行工程验证')!.trigger('click')
    await flushPromises()
    const enable = wrapper.findAll('button').find(button => button.text() === '启用已验证版本')!
    expect(enable.attributes('disabled')).toBeDefined()
    await wrapper.find('.wb-risk-confirm input').setValue(true)
    expect(enable.attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })
})
