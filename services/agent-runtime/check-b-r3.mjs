import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { existsSync, rmSync } from 'node:fs'
import { AttemptHost } from './attempt-host.mjs'
import { describeError } from './error-envelope.mjs'

const spec = { ownerId: 'synthetic-owner-a', caseId: 'PF-C001-synthetic', fixtureId: 'a',
  credentialMarker: 'synthetic-only', dshSource: process.env.PF_DSH_SOURCE }

const neverStarted = new AttemptHost(spec)
assert.equal(existsSync(neverStarted.home), true)
const noProcess = await neverStarted.close()
assert.equal(noProcess.no_process, true)
assert.equal(noProcess.home_removed, true)
assert.deepEqual(await neverStarted.close(), noProcess)

const forkThrows = new AttemptHost(spec, { forkWorker: () => { throw new Error('private fork secret') } })
assert.equal((await forkThrows.start()).classification, 'worker_fork_failed')
assert.equal((await forkThrows.close()).home_removed, true)

class FakeChild extends EventEmitter {
  connected = true
  send() {}
}
const fake = new FakeChild()
const workerFails = new AttemptHost(spec, { forkWorker: () => fake })
const starting = workerFails.start()
assert.equal(fake.listenerCount('error'), 1)
queueMicrotask(() => fake.emit('error', new Error('private worker secret')))
assert.equal((await starting).classification, 'worker_error')
assert.equal((await workerFails.close()).home_removed, true)

const ipc = new FakeChild()
ipc.send = () => { throw new Error('private IPC secret') }
const sendFails = new AttemptHost(spec, { forkWorker: () => ipc })
assert.equal((await sendFails.start()).classification, 'ipc_send_failed')
const sendCleanup = await sendFails.close()
assert.equal(sendCleanup.tree_stop_confirmed, false)
assert.equal(sendCleanup.home_removed, false)
assert.equal(existsSync(sendFails.home), true)
rmSync(sendFails.home, { recursive: true, force: true }) // test-owned fake had no OS child

let removeCalls = 0
const dshSource = process.env.PF_DSH_SOURCE
assert.ok(dshSource, 'PF_DSH_SOURCE required')
const deleteFails = new AttemptHost({ ...spec, scenario: 'crash' }, { removeHome: path => {
  removeCalls++
  if (removeCalls === 1) throw new Error('private filesystem secret')
  rmSync(path, { recursive: true, force: true })
} })
assert.equal((await deleteFails.start()).type, 'started')
const runFailure = await deleteFails.run()
assert.equal(runFailure.type, 'run_error')
assert.match(runFailure.error.message, /exit code: 23/)
const firstClose = await deleteFails.close()
assert.equal(firstClose.tree_stop_confirmed, true)
assert.equal(firstClose.home_removed, false)
assert.equal(firstClose.primary_error.classification, runFailure.error.classification)
assert.equal(firstClose.cleanup_error.classification, 'runtime_error')
assert.equal(existsSync(deleteFails.home), true)
const secondClose = await deleteFails.close()
assert.equal(secondClose.home_removed, true)
assert.equal(secondClose.primary_error.classification, runFailure.error.classification)
assert.equal(removeCalls, 2)
assert.equal(existsSync(deleteFails.home), false)

const outer = new Error('private outer secret')
const inner = new Error('private inner secret')
outer.cause = inner; inner.cause = outer
const encoded = JSON.stringify(describeError(outer))
assert.equal(encoded.includes('private'), false)
assert.ok(encoded.length < 400)
assert.equal(JSON.parse(encoded).cause.name, 'Error')

process.stdout.write(JSON.stringify({ unstarted_close: 'pass', delete_retry: 'pass',
  worker_error: 'pass', ipc_failure: 'pass', bounded_cause: 'pass', real_dsh_crash: 1 }) + '\n')
