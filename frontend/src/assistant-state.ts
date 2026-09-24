interface Snapshot {
  id: string
  updatedAt: string
  messages: { id: string; status: string }[]
}
const finished = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'])

// A poll started before a submit/cancel response may arrive afterwards.
export function shouldAcceptSession(current: Snapshot | null, incoming: Snapshot): boolean {
  if (!current || current.id !== incoming.id) return true
  if (incoming.messages.length < current.messages.length) return false
  if (Date.parse(incoming.updatedAt) < Date.parse(current.updatedAt)) return false
  const incomingById = new Map(incoming.messages.map(message => [message.id, message]))
  return current.messages.every(message => {
    const next = incomingById.get(message.id)
    return !!next && (!finished.has(message.status) || next.status === message.status)
  })
}

export function readStorage(key: string): string {
  try { return window.localStorage.getItem(key) || '' } catch { return '' }
}
export function writeStorage(key: string, value: string): void {
  try { window.localStorage.setItem(key, value) } catch { /* Private browsing may disable storage. */ }
}
