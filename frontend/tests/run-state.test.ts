import { test } from 'node:test'
import assert from 'node:assert/strict'
import { shouldAcceptRun } from '../src/run-state.ts'

const snapshot = (status: string, ...events: string[]) => ({ id: 'run-1', status, events: events.map(id => ({ id })) })

test('cancel response cannot regress to a delayed running snapshot', () => {
  assert.equal(shouldAcceptRun(snapshot('CANCELLED', '101'), snapshot('RUNNING', '99')), false)
})
test('approval response cannot regress to a delayed waiting snapshot', () => {
  assert.equal(shouldAcceptRun(snapshot('RUNNING', '102'), snapshot('WAITING_APPROVAL', '101')), false)
})
test('event ordering preserves large database IDs beyond JS integer precision', () => {
  assert.equal(shouldAcceptRun(snapshot('RUNNING', '9007199254740993'), snapshot('RUNNING', '9007199254740992')), false)
})
test('newer same-terminal snapshots can add artifacts and events', () => {
  assert.equal(shouldAcceptRun(snapshot('COMPLETED', '104'), snapshot('COMPLETED', '105')), true)
})
test('initial or different run accepts its first snapshot', () => {
  assert.equal(shouldAcceptRun(null, snapshot('QUEUED', '1')), true)
  assert.equal(shouldAcceptRun({ ...snapshot('COMPLETED', '999'), id: 'other-run' }, snapshot('QUEUED', '1')), true)
})
