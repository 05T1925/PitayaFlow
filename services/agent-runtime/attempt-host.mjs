import { fork, spawn } from 'node:child_process'
import { EventEmitter } from 'node:events'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { describeError } from './error-envelope.mjs'

const systemKeys = ['PATH', 'Path', 'SYSTEMROOT', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'APPDATA', 'LOCALAPPDATA', 'USERPROFILE']
const baseEnv = () => Object.fromEntries(systemKeys.filter(key => process.env[key]).map(key => [key, process.env[key]]))
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
const positiveMs = (value, name) => {
  if (!Number.isSafeInteger(value) || value <= 0 || value > 2_147_483_647) throw new RangeError(`${name} must be a positive bounded millisecond count`)
  return value
}
const nonnegativeLimit = (value, name) => {
  if (!Number.isSafeInteger(value) || value < 0) throw new RangeError(`${name} must be a nonnegative safe integer`)
  return value
}

/** @typedef {{command_succeeded: boolean, root_exited: boolean, descendants_stop_confirmed: boolean, tree_stop_confirmed: boolean, reason: string}} TreeStopResult */

/** A root that already exited has unknown descendants; never re-use its PID for taskkill. */
export async function stopOwnedTree(child, options = {}) {
  const rootExited = () => child.exitCode !== null || child.signalCode !== null
  if (rootExited()) return /** @type {TreeStopResult} */ ({ command_succeeded: false, root_exited: true, descendants_stop_confirmed: false, tree_stop_confirmed: false, reason: 'root_already_exited' })
  if (!Number.isSafeInteger(child.pid) || child.pid <= 0) return { command_succeeded: false, root_exited: false, descendants_stop_confirmed: false, tree_stop_confirmed: false, reason: 'missing_owned_pid' }
  const timeoutMs = positiveMs(options.timeoutMs ?? 2000, 'stop timeout')
  if ((options.platform ?? process.platform) === 'win32') {
    const launcher = options.spawnKiller ?? spawn
    const command = await new Promise(resolve => {
      let killer
      let finished = false
      const finish = value => { if (finished) return; finished = true; clearTimeout(timer); resolve(value) }
      try { killer = launcher('taskkill', ['/T', '/F', '/PID', String(child.pid)], { windowsHide: true, stdio: 'ignore' }) }
      catch { return resolve({ ok: false, reason: 'stop_command_spawn_failed' }) }
      const timer = setTimeout(() => {
        try { killer.kill('SIGKILL') } catch { /* command failure is reported below */ }
        killer.unref?.()
        finish({ ok: false, reason: 'stop_command_timeout' })
      }, timeoutMs)
      killer.once('error', () => finish({ ok: false, reason: 'stop_command_failed' }))
      killer.once('exit', code => finish({ ok: code === 0, reason: code === 0 ? 'stop_command_succeeded' : 'stop_command_failed' }))
    })
    return { command_succeeded: command.ok, root_exited: rootExited(),
      descendants_stop_confirmed: command.ok, tree_stop_confirmed: command.ok && rootExited(), reason: command.reason }
  }
  try {
    process.kill(-child.pid, 'SIGKILL')
    return { command_succeeded: true, root_exited: rootExited(), descendants_stop_confirmed: false,
      tree_stop_confirmed: false, reason: 'group_signal_sent_unconfirmed' }
  } catch { return { command_succeeded: false, root_exited: rootExited(), descendants_stop_confirmed: false,
    tree_stop_confirmed: false, reason: 'group_signal_failed' } }
}

/** Bound the outer demo wrapper as well as its owned stop command. */
export async function runOwnedWatchdog(child, timeoutMs, options = {}) {
  positiveMs(timeoutMs, 'watchdog timeoutMs')
  const stopBudgetMs = positiveMs(options.stopBudgetMs ?? 2500, 'stopBudgetMs')
  const exitGraceMs = positiveMs(options.exitGraceMs ?? 3000, 'exitGraceMs')
  const stopTree = options.stopTree ?? stopOwnedTree
  return new Promise(resolveOutcome => {
    let settled = false
    let watchdogFired = false
    let graceTimer = null
    const finish = value => { if (settled) return; settled = true; clearTimeout(timer); if (graceTimer) clearTimeout(graceTimer);
      resolveOutcome({ ...value, watchdog_fired: watchdogFired }) }
    const timer = setTimeout(async () => {
      watchdogFired = true
      const stopped = await new Promise(resolveStop => {
        const stopTimer = setTimeout(() => resolveStop(null), stopBudgetMs)
        Promise.resolve().then(() => stopTree(child)).then(
          value => { clearTimeout(stopTimer); resolveStop(value) },
          () => { clearTimeout(stopTimer); resolveStop(null) },
        )
      })
      if (settled) return
      if (!stopped?.descendants_stop_confirmed) {
        child.unref?.()
        finish({ code: 1, error: stopped?.reason ?? 'watchdog stop command unconfirmed' })
        return
      }
      graceTimer = setTimeout(() => { child.unref?.(); finish({ code: 1, error: 'watchdog root exit unconfirmed' }) }, exitGraceMs)
    }, timeoutMs)
    child.once('error', error => finish({ code: 1, error: error.message }))
    child.once('exit', code => finish({ code }))
  })
}

export class AttemptHost {
  constructor(spec, io = {}) {
    const limits = { deadlineMs: spec.deadlineMs === undefined ? undefined : positiveMs(spec.deadlineMs, 'deadlineMs'),
      startTimeoutMs: positiveMs(spec.startTimeoutMs ?? 30000, 'startTimeoutMs'),
      maxModelCalls: nonnegativeLimit(spec.maxModelCalls ?? 4, 'maxModelCalls'),
      maxToolCalls: nonnegativeLimit(spec.maxToolCalls ?? 2, 'maxToolCalls'),
      outputLimitBytes: positiveMs(spec.outputLimitBytes ?? 4096, 'outputLimitBytes') }
    this.spec = spec
    this.limits = limits
    this.forkWorker = io.forkWorker ?? fork
    this.removeHome = io.removeHome ?? (path => {
      if (spec.injectHomeRemovalFailure) throw new Error('injected_home_removal_failure')
      rmSync(path, { recursive: true, force: true })
    })
    this.home = spec.homePath ? resolve(spec.homePath) : mkdtempSync(resolve(tmpdir(), 'pitayaflow-attempt-'))
    if (spec.homePath) mkdirSync(this.home) // exclusive: never adopt a previous attempt's files
    this.grantPath = resolve(this.home, 'grant.json')
    this.auditPath = resolve(this.home, 'inventory.json')
    this.setGrant({ owner_id: spec.ownerId, case_id: spec.caseId, authorization_revision: 'auth-1', allowed: true })
    this.state = 'created'
    this.messages = []
    this.history = []
    this.events = new EventEmitter()
    this.exit = null
    this.timer = null
    this.result = null
    this.stopPromise = null
    this.cleanupPromise = null
    this.cleanupPending = false
    this.lastCleanup = null
    this.primaryError = null
    this.workerError = null
    this.ipcError = null
    this.spawnFailed = false
    /** @type {null | 'cancelled' | 'deadline' | 'closed' | 'completed'} */
    this.stopIntent = null
    this.startedAt = performance.now()
  }

  setGrant(value) { writeFileSync(this.grantPath, JSON.stringify(value)) }
  inspect() { return { state: this.state, stop_intent: this.stopIntent, exit: this.exit, home_exists: existsSync(this.home),
    timer_active: this.timer !== null, result: this.result } }

  acceptsProgress() { return this.stopIntent === null }

  removeOwnedHome() {
    try { this.removeHome(this.home) }
    catch (error) { return { home_removed: false, retained_home: this.home, cleanup_error: describeError(error) } }
    const removed = !existsSync(this.home)
    return { home_removed: removed, retained_home: removed ? undefined : this.home,
      cleanup_error: removed ? undefined : { name: 'Error', classification: 'home_removal_unconfirmed' } }
  }

  retryCleanup() {
    if (this.cleanupPromise) return this.cleanupPromise
    this.cleanupPromise = Promise.resolve().then(() => {
      const removal = this.removeOwnedHome()
      this.cleanupPending = !removal.home_removed
      this.lastCleanup = { ...this.lastCleanup, ...removal }
      if (removal.home_removed && this.state === 'cleanup_failed') this.state = 'closed'
      return this.lastCleanup
    }).finally(() => { this.cleanupPromise = null })
    return this.cleanupPromise
  }

  sendCommand(command) {
    try {
      this.child.send(command, error => {
        if (error) { this.ipcError = describeError(error); this.events.emit('ipc_error') }
      })
      return null
    } catch (error) { this.ipcError = describeError(error); return this.ipcError }
  }

  async start() {
    if (this.state !== 'created') throw new Error('attempt already started')
    const env = { ...baseEnv(), PF_DSH_SOURCE: this.spec.dshSource, PF_HOME: this.home,
      PF_GRANT_FILE: this.grantPath, PF_AUDIT_FILE: this.auditPath, PF_FIXTURE_ID: this.spec.fixtureId,
      PF_ATTEMPT_MODE: this.spec.mode ?? 'synthetic',
      PF_OWNER_ID: this.spec.ownerId, PF_SCENARIO: this.spec.scenario ?? 'success',
      ...(this.spec.bridge ? { PF_BRIDGE_URL: this.spec.bridge.url, PF_BRIDGE_TOKEN: this.spec.bridge.token,
        PF_BRIDGE_ATTEMPT: this.spec.bridge.attemptId, PF_BRIDGE_CASE_ID: this.spec.caseId } : {}),
      PF_AUTH_REV: 'auth-1', PF_CREDENTIAL_MARKER: this.spec.credentialMarker,
      PF_MAX_MODEL_CALLS: String(this.limits.maxModelCalls), PF_MAX_TOOL_CALLS: String(this.limits.maxToolCalls),
      ...(this.spec.injectCloseFailure ? { PF_INJECT_CLOSE_FAILURE: '1' } : {}),
      ...(this.spec.resultGate ? { PF_RESULT_GATE: '1' } : {}),
      ...this.spec.targetCaseId ? { PF_TARGET_CASE_ID: this.spec.targetCaseId } : {},
      ...this.spec.targetOwnerId ? { PF_TARGET_OWNER_ID: this.spec.targetOwnerId } : {} }
    try {
      this.child = this.forkWorker(resolve('services/agent-runtime/attempt-worker.mjs'), [], {
        cwd: process.cwd(), env, detached: process.platform !== 'win32', silent: true, windowsHide: true,
      })
    } catch (error) {
      this.spawnFailed = true
      this.primaryError = describeError(error)
      this.state = 'start_failed'
      return { type: 'start_error', classification: 'worker_fork_failed', error: this.primaryError }
    }
    this.child.stdout?.resume()
    this.child.stderr?.resume()
    this.child.on('message', message => { this.history.push(message); this.messages.push(message); this.events.emit('message') })
    this.child.on('exit', (code, signal) => { this.exit = { code, signal }; this.events.emit('exit') })
    this.child.on('error', error => { this.workerError = describeError(error)
      this.spawnFailed = !Number.isSafeInteger(this.child.pid); this.events.emit('worker_error') })
    this.state = 'starting'
    const sendError = this.sendCommand({ type: 'start' })
    if (sendError) {
      this.primaryError = sendError
      this.state = 'start_failed'
      return { type: 'start_error', classification: 'ipc_send_failed', error: sendError }
    }
    if (this.limits.deadlineMs !== undefined) {
      this.timer = setTimeout(() => { void this.requestCancel('deadline') }, this.limits.deadlineMs)
    }
    const started = await this.waitFor(['started', 'start_error'], this.limits.startTimeoutMs)
    if (started.type === 'worker_error' || started.type === 'ipc_error') {
      this.primaryError = started.error
      if (this.acceptsProgress()) this.state = 'start_failed'
      return { type: 'start_error', classification: started.type, error: started.error }
    }
    if (started.type === 'started') { if (this.acceptsProgress()) this.state = 'running'; return started }
    if (started.type === 'wait_timeout') return started
    if (this.acceptsProgress()) this.state = 'start_failed'
    if (started.type === 'start_error') this.primaryError = started.error
    return started
  }

  async waitFor(types, timeoutMs) {
    const take = () => {
      const index = this.messages.findIndex(message => types.includes(message.type))
      return index < 0 ? null : this.messages.splice(index, 1)[0]
    }
    const immediate = take()
    if (immediate) return immediate
    if (this.workerError) return { type: 'worker_error', error: this.workerError }
    if (this.ipcError) return { type: 'ipc_error', error: this.ipcError }
    if (this.exit) return { type: 'worker_exit', exit: this.exit }
    return new Promise(resolve => {
      let settled = false
      const finish = value => { if (settled) return; settled = true; clearTimeout(timer);
        this.events.off('message', onMessage); this.events.off('exit', onExit)
        this.events.off('worker_error', onWorkerError); this.events.off('ipc_error', onIpcError); resolve(value) }
      const onMessage = () => { const value = take(); if (value) finish(value) }
      const onExit = () => finish({ type: 'worker_exit', exit: this.exit })
      const onWorkerError = () => finish({ type: 'worker_error', error: this.workerError })
      const onIpcError = () => finish({ type: 'ipc_error', error: this.ipcError })
      const timer = setTimeout(() => finish({ type: 'wait_timeout' }), timeoutMs)
      this.events.on('message', onMessage)
      this.events.on('exit', onExit)
      this.events.on('worker_error', onWorkerError)
      this.events.on('ipc_error', onIpcError)
    })
  }

  async run(waitMs = 15000) {
    positiveMs(waitMs, 'run waitMs')
    if (this.state !== 'running') throw new Error(`cannot run in ${this.state}`)
    const sendError = this.sendCommand({ type: 'run' })
    if (sendError) { this.primaryError = sendError; this.state = 'run_failed'
      return { type: 'run_error', classification: 'ipc_send_failed', error: sendError } }
    const message = await this.waitFor(['result', 'run_error'], waitMs)
    if (message.type === 'worker_error' || message.type === 'ipc_error') {
      this.primaryError = message.error
      if (this.acceptsProgress()) this.state = 'run_failed'
      return { type: 'run_error', classification: message.type, error: message.error }
    }
    if (message.type === 'wait_timeout') return { type: 'wait_timeout', state: this.state }
    if (message.type === 'result') {
      this.result = message
      const quotaReason = message.output_bytes > this.limits.outputLimitBytes ? 'output_limit' :
        message.terminal_error?.includes('PF_MODEL_CALL_LIMIT') ? 'model_call_limit' :
          message.tool_results?.some(item => item.text?.includes('PF_TOOL_CALL_LIMIT')) ? 'tool_call_limit' : null
      message.quota_reason = quotaReason
      message.delivery_usable = this.acceptsProgress() && !quotaReason && message.validation?.usable === true
      if (this.acceptsProgress()) this.state = quotaReason ? 'quota' : message.validation?.usable ? 'completed' : 'rejected'
    } else if (message.type === 'run_error') {
      this.primaryError = message.error
      if (this.acceptsProgress()) this.state = 'run_failed'
    }
    return message
  }

  releaseResult() { if (this.spec.resultGate && this.child?.connected) this.child.send({ type: 'release_result' }) }

  /** @param {'cancelled' | 'deadline' | 'closed' | 'completed'} reason */
  requestCancel(reason = 'cancelled') {
    if (this.cleanupPending) return this.retryCleanup()
    if (this.stopPromise) return this.stopPromise
    const completed = this.state === 'completed'
    this.stopIntent = completed && reason === 'cancelled' ? 'completed' : reason
    this.stopPromise = this.stop(reason).catch(error => {
      this.stopPromise = null
      this.primaryError ??= describeError(error)
      return { tree_stop_confirmed: false, home_removed: false, retained_home: this.home,
        primary_error: this.primaryError, cleanup_error: describeError(error) }
    })
    return this.stopPromise
  }

  async close() { return this.requestCancel(this.state === 'completed' ? 'completed' : 'closed') }

  /** @param {'cancelled' | 'deadline' | 'closed' | 'completed'} reason */
  async stop(reason) {
    const previous = this.state
    const finalReason = this.stopIntent
    this.state = finalReason === 'deadline' ? 'deadline_requested' : finalReason === 'cancelled' ? 'cancel_requested' : this.state
    if (this.timer) { clearTimeout(this.timer); this.timer = null }
    if (!this.child) {
      const removal = this.removeOwnedHome()
      this.cleanupPending = !removal.home_removed
      this.state = removal.home_removed ? 'closed' : 'cleanup_failed'
      this.lastCleanup = { exit_confirmed: true, tree_stop_confirmed: true, no_process: true,
        forced: false, exit: null, primary_error: this.primaryError, ...removal }
      return this.lastCleanup
    }
    const noProcess = this.spawnFailed && !Number.isSafeInteger(this.child.pid)
    const closeSendError = !noProcess && this.child.connected ? this.sendCommand({ type: 'close' }) : null
    const closed = noProcess ? { type: 'worker_error', error: this.workerError } :
      closeSendError ? { type: 'ipc_error', error: closeSendError } :
        await this.waitFor(['closed', 'close_error'], 3500)
    if (closed.type === 'closed' && this.child.connected) this.child.disconnect()
    let stopResult = null
    if (closed.type !== 'closed' && this.exit === null && !this.spawnFailed) stopResult = await stopOwnedTree(this.child)
    for (let i = 0; !noProcess && !this.exit && i < 30; i++) await delay(100)
    const treeConfirmed = noProcess || (this.exit !== null && (closed.type === 'closed' || stopResult?.descendants_stop_confirmed === true))
    const removal = treeConfirmed ? this.removeOwnedHome() :
      { home_removed: false, retained_home: this.home, cleanup_error: undefined }
    this.cleanupPending = treeConfirmed && !removal.home_removed
    const cleanup = { exit_confirmed: noProcess || this.exit !== null, tree_stop_confirmed: treeConfirmed, no_process: noProcess,
      ...removal, forced: stopResult?.command_succeeded === true, stop_result: stopResult, exit: this.exit,
      primary_error: this.primaryError,
      close_error: closed.type === 'close_error' ? closed.error : undefined,
      elapsed_ms: Math.round(performance.now() - this.startedAt) }
    this.lastCleanup = cleanup
    this.state = !treeConfirmed ? 'stop_unconfirmed' : !removal.home_removed ? 'cleanup_failed' :
      finalReason === 'deadline' ? 'deadline' : finalReason === 'cancelled' ? 'cancelled' : previous
    return cleanup
  }
}
