import { createInterface } from 'node:readline'
import { validateProtocol } from '../../packages/contracts/s1-agent-protocol.mjs'
import { AttemptHost } from './attempt-host.mjs'

// The first line is the RunSpec. Keep stdin open for a bounded Java cancellation handshake.
const lines = createInterface({ input: process.stdin, crlfDelay: Infinity })
const first = await new Promise(resolve => lines.once('line', resolve))
let spec, host, cancelled = false, releaseHold
const cancelledSignal = new Promise(resolve => { releaseHold = resolve })
lines.on('line', line => {
  try {
    if (JSON.parse(line).type !== 'cancel') return
    cancelled = true
    releaseHold()
    if (host) void host.requestCancel('cancelled')
  } catch { /* malformed control frames have no authority */ }
})
const requireActive = () => { if (cancelled) throw new Error('bridge cancelled by owner') }
try {
  spec = JSON.parse(first)
  if (!validateProtocol('run_spec', spec)) throw new Error('invalid bridge RunSpec')
  if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(spec.bridge_url)) throw new Error('bridge must use configured local API')
  host = new AttemptHost({ ownerId: spec.owner_id, caseId: spec.case_id, dshSource: spec.dsh_source,
    mode: spec.mode ?? 'synthetic',
    homePath: spec.home_path, injectHomeRemovalFailure: spec.inject_cleanup_failure === true,
    deadlineMs: spec.deadline_ms, maxModelCalls: 4, maxToolCalls: 2,
    outputLimitBytes: spec.mode === 'review' ? 2_000_000 : 4096,
    scenario: spec.scenario ?? 'success',
    bridge: { url: spec.bridge_url, token: spec.grant_token, attemptId: spec.attempt_id } })
  requireActive()
  const started = await host.start()
  requireActive()
  if (started.type !== 'started') throw new Error(`DSH start: ${started.type}`)
  const result = await host.run(Math.max(1000, Math.min(spec.deadline_ms, 90000)))
  requireActive()
  if (result.type !== 'result') throw new Error(`DSH run: ${result.type}`)
  const ready = await fetch(`${spec.bridge_url}/internal/agent/${spec.mode === 'review' ? 'review/' : ''}grants/${encodeURIComponent(spec.attempt_id)}/ready?case_id=${encodeURIComponent(spec.case_id)}&owner_id=${encodeURIComponent(spec.owner_id)}`,
    { method: 'POST', headers: { Authorization: `Bearer ${spec.grant_token}` }, signal: AbortSignal.timeout(5000), redirect: 'error' })
  requireActive()
  if (!ready.ok) throw new Error(`grant ready denied: ${ready.status}`)
  if (spec.result_hold_ms !== undefined) {
    if (!Number.isSafeInteger(spec.result_hold_ms) || spec.result_hold_ms < 0 || spec.result_hold_ms > 15000) throw new Error('invalid result hold')
    let timer
    try { await Promise.race([new Promise(resolve => { timer = setTimeout(resolve, spec.result_hold_ms) }), cancelledSignal]) }
    finally { clearTimeout(timer) }
  }
  requireActive()
  const cleanup = await host.close()
  const frame = { task_id: spec.task_id, run_id: spec.run_id, attempt_id: spec.attempt_id,
    execution_epoch: spec.execution_epoch, draft: result.draft, validation: result.validation,
    registered_tools: result.registered_tools, cleanup }
  if (!validateProtocol('bridge_result', frame)) throw new Error('invalid bridge result frame')
  process.stdout.write(JSON.stringify(frame))
  if (!cleanup.tree_stop_confirmed || !cleanup.home_removed) process.exitCode = 2
} catch (error) {
  if (host) {
    const cleanup = await host.close().catch(() => null)
    process.stderr.write(`PF_BRIDGE_FAILURE=${error?.name ?? 'Error'} tree_confirmed=${cleanup?.tree_stop_confirmed === true} home_removed=${cleanup?.home_removed === true} retained_home=${cleanup?.retained_home ?? 'none'}\n`)
  } else process.stderr.write(`PF_BRIDGE_FAILURE=${error?.name ?? 'Error'}\n`)
  process.exitCode = 1
} finally { lines.close() }
