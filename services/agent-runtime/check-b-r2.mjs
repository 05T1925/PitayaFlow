import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { AttemptHost, stopOwnedTree, runOwnedWatchdog } from './attempt-host.mjs'

const validResult = () => ({ type: 'result', validation: { usable: true }, output_bytes: 5, tool_results: [] })
const deferred = () => Promise.withResolvers()
class FakeChild extends EventEmitter {
  stdout = { resume() {} }
  stderr = { resume() {} }
  connected = true
  send() {}
  kill() {}
  unref() {}
}

// A message delivered after a cancellation request cannot advance the attempt.
for (const [reason, type, expected] of [
  ['cancelled', 'result', 'cancel_requested'],
  ['deadline', 'result', 'deadline_requested'],
  ['cancelled', 'run_error', 'cancel_requested'],
]) {
  const message = deferred(); const stopping = deferred()
  const host = Object.create(AttemptHost.prototype)
  host.state = 'running'; host.stopIntent = null; host.stopPromise = null
  host.limits = { outputLimitBytes: 4096 }
  host.child = { send() {} }
  host.waitFor = () => message.promise
  host.stop = async () => { host.state = expected; await stopping.promise; return { exit_confirmed: true } }
  const run = host.run()
  const cancel = host.requestCancel(reason)
  message.resolve(type === 'result' ? validResult() : { type: 'run_error', error: { message: 'late error' } })
  const outcome = await run
  assert.equal(host.state, expected)
  if (type === 'result') assert.equal(outcome.delivery_usable, false)
  stopping.resolve(); await cancel
}

// Late start and start error use the same monotone progress guard.
for (const lateType of ['started', 'start_error']) {
  const pending = deferred(); const stopping = deferred()
  const fakeChild = new FakeChild()
  const host = new AttemptHost({ ownerId: 'synthetic-a', caseId: 'PF-C001-synthetic', fixtureId: 'a',
    dshSource: 'unused', credentialMarker: 'synthetic' }, { forkWorker: () => fakeChild })
  host.waitFor = () => pending.promise
  Object.defineProperty(host, 'stop', { value: async () => {
    host.state = 'cancel_requested'; await stopping.promise; return { exit_confirmed: true }
  } })
  const start = host.start()
  const cancel = host.requestCancel('cancelled')
  pending.resolve({ type: lateType })
  assert.equal((await start).type, lateType)
  assert.equal(host.state, 'cancel_requested')
  stopping.resolve(); await cancel
  rmSync(host.home, { recursive: true, force: true })
}

assert.throws(() => new AttemptHost({ deadlineMs: 0 }), /deadlineMs/)
assert.throws(() => new AttemptHost({ maxModelCalls: -1 }), /maxModelCalls/)
assert.throws(() => new AttemptHost({ outputLimitBytes: 0 }), /outputLimitBytes/)

const exitedRoot = { pid: 99, exitCode: 0, signalCode: null }
const preExited = await stopOwnedTree(exitedRoot, { platform: 'win32', spawnKiller: () => { throw new Error('must not kill stale PID') } })
assert.equal(preExited.tree_stop_confirmed, false)
assert.equal(preExited.reason, 'root_already_exited')

const root = { pid: 99, exitCode: null, signalCode: null }
const hanging = new FakeChild()
let killerKilled = false
hanging.kill = () => { killerKilled = true }
const before = performance.now()
const timeout = await stopOwnedTree(root, { platform: 'win32', timeoutMs: 30, spawnKiller: () => hanging })
assert.equal(timeout.reason, 'stop_command_timeout')
assert.equal(timeout.tree_stop_confirmed, false)
assert.equal(killerKilled, true)
assert.ok(performance.now() - before < 500)

const failed = new FakeChild()
queueMicrotask(() => failed.emit('exit', 1))
assert.equal((await stopOwnedTree(root, { platform: 'win32', timeoutMs: 100,
  spawnKiller: () => failed })).reason, 'stop_command_failed')

const succeeded = new FakeChild()
queueMicrotask(() => succeeded.emit('exit', 0))
const stopOnly = await stopOwnedTree(root, { platform: 'win32', timeoutMs: 100, spawnKiller: () => succeeded })
assert.equal(stopOnly.command_succeeded, true)
assert.equal(stopOnly.root_exited, false)
assert.equal(stopOnly.tree_stop_confirmed, false)

const neverExits = new FakeChild()
const watchdogUnconfirmed = await runOwnedWatchdog(neverExits, 20, { stopBudgetMs: 30, exitGraceMs: 30,
  stopTree: async () => new Promise(() => {}) })
assert.equal(watchdogUnconfirmed.watchdog_fired, true)
assert.equal(watchdogUnconfirmed.code, 1)
assert.match(watchdogUnconfirmed.error, /unconfirmed/)
const watchdogNoRoot = await runOwnedWatchdog(new FakeChild(), 20, { stopBudgetMs: 30, exitGraceMs: 30,
  stopTree: async () => ({ descendants_stop_confirmed: true }) })
assert.equal(watchdogNoRoot.error, 'watchdog root exit unconfirmed')

const diagnosticHome = mkdtempSync(resolve(tmpdir(), 'pitayaflow-r2-retained-'))
const uncertain = Object.create(AttemptHost.prototype)
uncertain.home = diagnosticHome; uncertain.child = { connected: false, exitCode: 0, signalCode: null }
uncertain.exit = { code: 0, signal: null }; uncertain.timer = null
uncertain.state = 'cancel_requested'; uncertain.stopIntent = 'cancelled'; uncertain.startedAt = performance.now()
uncertain.waitFor = async () => ({ type: 'close_error', error: { message: 'close failed' } })
const retained = await uncertain.stop('cancelled')
assert.equal(retained.exit_confirmed, true)
assert.equal(retained.tree_stop_confirmed, false)
assert.equal(retained.home_removed, false)
assert.equal(existsSync(diagnosticHome), true)
assert.equal(uncertain.state, 'stop_unconfirmed')
rmSync(diagnosticHome, { recursive: true, force: true })

// A real DSH result is held at the worker/host boundary while cancellation wins admission order.
const dshSource = process.env.PF_DSH_SOURCE
assert.ok(dshSource, 'PF_DSH_SOURCE required')
const real = new AttemptHost({ dshSource, ownerId: 'synthetic-owner-a', caseId: 'PF-C001-synthetic', fixtureId: 'a',
  credentialMarker: 'synthetic-race', resultGate: true })
try {
  assert.equal((await real.start()).type, 'started')
  const running = real.run(30000)
  assert.equal((await real.waitFor(['result_ready'], 30000)).type, 'result_ready')
  const cancelling = real.requestCancel('cancelled')
  real.releaseResult()
  const late = await running
  assert.equal(late.type, 'result')
  assert.equal(late.delivery_usable, false)
  const release = await cancelling
  assert.equal(real.inspect().state, 'cancelled')
  assert.equal(release.tree_stop_confirmed, true)
  assert.equal(release.home_removed, true)
} finally {
  if (real.child && real.exit === null) await real.requestCancel('cancelled')
}

process.stdout.write(JSON.stringify({ race_barriers: 6, stop_failures: 6, real_dsh_races: 1, status: 'pass' }) + '\n')
