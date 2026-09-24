import { request } from './api'

export type AssistantProvider = 'UNCONFIGURED' | 'CODEX_CLI' | 'MODEL_API'
export interface AssistantStatus { provider: AssistantProvider; available: boolean; version: string; message: string }
export interface AssistantContext { page: string; projectId?: string; taskId?: string; runId?: string; nodeKey?: string; selectedText?: string }
export interface ContextSnapshot { title: string; summary: string; content: string }
export interface SessionSummary { id: string; title: string; status: string; createdAt: string; updatedAt: string }
export interface ChatMessage { id: string; role: 'USER' | 'ASSISTANT'; content: string; status: string; contextTitle: string | null; contextSummary: string | null; createdAt: string }
export interface SessionDetail extends SessionSummary { messages: ChatMessage[] }
export interface TaskDraft { title: string; objective: string; projectId?: string }

export const assistantApi = {
  status: () => request<AssistantStatus>('/assistant/status'),
  context: (context: AssistantContext) => request<ContextSnapshot>('/assistant/context', context),
  sessions: () => request<SessionSummary[]>('/assistant/sessions'),
  create: (title?: string) => request<SessionDetail>('/assistant/sessions', title ? { title } : {}),
  session: (id: string) => request<SessionDetail>(`/assistant/sessions/${encodeURIComponent(id)}`),
  send: (id: string, text: string, includeContext: boolean, context?: AssistantContext) => request<SessionDetail>(`/assistant/sessions/${encodeURIComponent(id)}/messages`, { text, includeContext, ...(includeContext ? { context } : {}) }),
  cancel: (id: string) => request<SessionDetail>(`/assistant/sessions/${encodeURIComponent(id)}/cancel`, {}),
}
