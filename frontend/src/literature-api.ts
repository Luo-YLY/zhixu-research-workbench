import { ApiError } from './api'

export interface LiteratureDocument {
  id: string; projectId: string; title: string; fileName: string; mediaType: string
  sha256: string; sizeBytes: number; pageCount: number; chunkCount: number; createdAt: string
}
export interface LiteratureHit {
  chunkId: string; documentId: string; title: string; fileName: string; pageNumber: number
  chunkNumber: number; documentSha256: string; chunkSha256: string; excerpt: string
  score: number; sourceUrl: string
}
export interface LiteratureSearchResult { query: string; retrievalVersion: string; hits: LiteratureHit[] }
export interface LiteratureAnswer { status: string; answer: string; retrievalVersion: string; citations: LiteratureHit[] }

async function readResponse<T>(response: Response): Promise<T> {
  const body = await response.json().catch(() => null)
  if (!response.ok) throw new ApiError(body?.message || `请求未完成（${response.status}）`, response.status, body?.code || '')
  return body as T
}

export async function listLiterature(projectId: string): Promise<LiteratureDocument[]> {
  return readResponse(await fetch(`/api/literature/documents?projectId=${encodeURIComponent(projectId)}`))
}

export async function uploadLiterature(projectId: string, title: string, file: File): Promise<LiteratureDocument> {
  const data = new FormData()
  data.set('projectId', projectId); data.set('title', title); data.set('file', file)
  return readResponse(await fetch('/api/literature/documents', { method: 'POST', body: data }))
}

export async function searchLiterature(projectId: string, query: string, documentId = ''): Promise<LiteratureSearchResult> {
  const scope = documentId ? `&documentId=${encodeURIComponent(documentId)}` : ''
  return readResponse(await fetch(`/api/literature/search?projectId=${encodeURIComponent(projectId)}&q=${encodeURIComponent(query)}&limit=5${scope}`))
}

export async function answerLiterature(projectId: string, question: string, documentId = ''): Promise<LiteratureAnswer> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 190000)
  try {
    return await readResponse(await fetch('/api/literature/answer', {
      method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ projectId, question, ...(documentId ? { documentId } : {}) }), signal: controller.signal,
    }))
  } finally { window.clearTimeout(timeout) }
}

export function safeLiteratureSource(url: string): string | undefined {
  return /^\/api\/literature\/documents\/[0-9a-f-]{36}\/file(?:#page=\d+)?$/.test(url) ? url : undefined
}
