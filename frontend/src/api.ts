export interface Capability { key: string; label: string; status: string; description: string }
export interface SystemInfo { appName: string; version: string; database: string; executionMode: string; capabilities: Capability[] }
export interface Dashboard { projectCount: number; taskCount: number; activeRunCount: number; pendingApprovalCount: number; completedRunCount: number }
export interface Project { id: string; name: string; description: string; createdAt: string }
export interface ResearchTask { id: string; projectId: string; title: string; objective: string; createdAt: string }
export interface RecurringSchedule { id: string; taskId: string; taskTitle: string; frequency: 'DAILY' | 'WEEKLY'; weekday: number | null; plannedTime: string; startDate: string; active: boolean; createdAt: string }
export interface PlanItem { id: string; date: string; plannedTime: string | null; taskId: string | null; scheduleId: string | null; title: string; status: 'TODO' | 'DONE'; createdAt: string; completedAt: string | null }
export interface RunSummary { id: string; taskId: string; taskTitle: string; projectId: string; status: string; executionMode: string; createdAt: string; updatedAt: string }
export interface RunNode { id: string; key: string; label: string; position: number; status: string; startedAt: string | null; finishedAt: string | null; detail: string | null }
export interface RunEvent { id: string; runId: string; type: string; message: string; createdAt: string }
export interface Approval { id: string; runId: string; taskTitle: string; status: string; createdAt: string; decision: string | null; comment: string | null; decidedAt: string | null }
export interface Artifact { id: string; runId: string; name: string; mediaType: string; sha256: string; sizeBytes: number; createdAt: string; downloadUrl: string }
export interface RunDetail extends RunSummary { objective: string; nodes: RunNode[]; events: RunEvent[]; artifacts: Artifact[]; approval: Approval | null }

export class ApiError extends Error {
  status: number
  code: string
  constructor(message: string, status: number, code = '') {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export async function request<T>(path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(`/api${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    })
    const result = await response.json().catch(() => null)
    if (!response.ok) throw new ApiError(result?.message || `请求未完成（${response.status}）`, response.status, result?.code || '')
    if (result === null) throw new Error('接口没有返回有效数据，请检查后端服务。')
    return result as T
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw new Error('请求超时，请检查后端服务后重试。')
    if (error instanceof TypeError) throw new Error('暂时无法连接后端服务，请确认本地服务已经启动。')
    throw error
  } finally { window.clearTimeout(timeout) }
}

export const terminal = (status: string) => ['COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED'].includes(status)
export const statusLabel = (status: string): string => ({
  QUEUED: '排队中', RUNNING: '运行中', WAITING_APPROVAL: '等待审批', COMPLETED: '已完成',
  FAILED: '失败', CANCELLED: '已取消', REJECTED: '已退回', PENDING: '待处理',
  SUCCEEDED: '已通过', APPROVED: '已接受', AVAILABLE: '已实现', PARTIAL: '部分可用', PLANNED: '待接入', CONFIGURATION_REQUIRED: '待配置',
}[status] || status)
