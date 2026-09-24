import { terminal } from './api.ts'

interface VersionedRun { id: string; status: string; events: { id: string }[] }

function lastEventId(run: VersionedRun): bigint {
  return run.events.reduce((last, event) => {
    const value = /^\d+$/.test(event.id) ? BigInt(event.id) : 0n
    return value > last ? value : last
  }, 0n)
}

// HTTP responses and SSE can arrive out of order. Persisted event IDs are the
// revision sequence; a terminal response must never be replaced by stale work.
export function shouldAcceptRun(current: VersionedRun | null, incoming: VersionedRun): boolean {
  if (!current || current.id !== incoming.id) return true
  if (terminal(current.status) && current.status !== incoming.status) return false
  return lastEventId(incoming) >= lastEventId(current)
}
