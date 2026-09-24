import { test } from 'node:test'
import assert from 'node:assert/strict'
import { shouldAcceptSession } from '../src/assistant-state.ts'

const snapshot = (updatedAt: string, ...messages: { id: string; status: string }[]) => ({ id: 'session-1', updatedAt, messages })
const start = '2026-09-22T10:00:00Z'
const finish = '2026-09-22T10:00:02Z'

test('late polling cannot restore a running response after cancellation', () => {
  const current = snapshot(finish, { id: 'answer-1', status: 'CANCELLED' })
  assert.equal(shouldAcceptSession(current, snapshot(start, { id: 'answer-1', status: 'RUNNING' })), false)
  assert.equal(shouldAcceptSession(current, snapshot(finish, { id: 'answer-1', status: 'RUNNING' })), false)
})
test('polling cannot remove an acknowledged submitted message', () => {
  const current = snapshot(finish, { id: 'user-1', status: 'COMPLETED' }, { id: 'answer-1', status: 'RUNNING' })
  assert.equal(shouldAcceptSession(current, snapshot(start, { id: 'user-1', status: 'COMPLETED' })), false)
})
test('completed replies and subsequent turns remain accepted', () => {
  const current = snapshot(start, { id: 'answer-1', status: 'RUNNING' })
  const complete = snapshot(finish, { id: 'answer-1', status: 'COMPLETED' })
  assert.equal(shouldAcceptSession(current, complete), true)
  assert.equal(shouldAcceptSession(complete, snapshot(finish, { id: 'answer-1', status: 'COMPLETED' }, { id: 'user-2', status: 'COMPLETED' })), true)
})
test('switching conversations accepts an independent session snapshot', () => {
  assert.equal(shouldAcceptSession(null, snapshot(start)), true)
  assert.equal(shouldAcceptSession({ ...snapshot(finish), id: 'session-2' }, snapshot(start)), true)
})
