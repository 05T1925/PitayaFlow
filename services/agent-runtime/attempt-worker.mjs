import { readFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { validateDraft } from '../../packages/contracts/validate-draft.mjs'
import { validReviewDraft } from '../../packages/contracts/s2-review-protocol.mjs'
import { describeError } from './error-envelope.mjs'

const root = process.env.PF_DSH_SOURCE
if (!root || execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim() !==
    'ce7073d51254c26cd63b22a60835b8f948deb447') throw new Error('DSH source SHA mismatch')
const sdkDir = resolve(root, 'packages/sdk/client')
const sdkManifest = JSON.parse(readFileSync(resolve(sdkDir, 'package.json'), 'utf8'))
const { DeepSeekHarness } = await import(pathToFileURL(resolve(sdkDir, sdkManifest.main)).href)
const fixtureName = process.env.PF_FIXTURE_ID === 'b' ? 'synthetic-case-b.json' : 'synthetic-case.json'
const localFixture = process.env.PF_BRIDGE_URL ? null : JSON.parse(readFileSync(new URL(`../../packages/contracts/${fixtureName}`, import.meta.url), 'utf8'))
const allowedEnv = ['PATH', 'Path', 'SYSTEMROOT', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'APPDATA', 'LOCALAPPDATA', 'USERPROFILE',
  'PF_DSH_SOURCE', 'PF_SCENARIO', 'PF_FIXTURE_ID', 'PF_OWNER_ID', 'PF_AUTH_REV', 'PF_GRANT_FILE', 'PF_AUDIT_FILE',
  'PF_TARGET_CASE_ID', 'PF_TARGET_OWNER_ID', 'PF_MAX_MODEL_CALLS', 'PF_MAX_TOOL_CALLS', 'PF_CREDENTIAL_MARKER']
allowedEnv.push('PF_BRIDGE_URL','PF_BRIDGE_TOKEN','PF_BRIDGE_ATTEMPT','PF_BRIDGE_CASE_ID')
allowedEnv.push('PF_ATTEMPT_MODE')
const childEnv = Object.fromEntries(allowedEnv.filter(key => process.env[key] !== undefined).map(key => [key, process.env[key]]))
const harness = new DeepSeekHarness({ profile: 'sdk-minimal', patches: [resolve('packages/dsh-tools/controlled.patch.yml')],
  dshHome: process.env.PF_HOME, cwd: process.cwd(), processCwd: process.cwd(), env: childEnv,
  provider: 'pf-scripted', model: 'pf-scripted', initializeTimeoutMs: 30000, requestTimeoutMs: 30000,
  shutdownTimeoutMs: 500, disposeEofGraceMs: 1000, disposeGraceMs: 1000 })
const send = value => { if (process.connected) process.send(value) }
const resultGate = process.env.PF_RESULT_GATE === '1' ? Promise.withResolvers() : null
process.on('message', async message => {
  if (message === null || typeof message !== 'object') return
  const command = /** @type {{type?: string}} */ (message)
  if (command.type === 'release_result') resultGate?.resolve()
  if (command.type === 'start') {
    try { await harness.start(); send({ type: 'started' }) }
    catch (error) { send({ type: 'start_error', error: describeError(error) }) }
  }
  if (command.type === 'run') {
    try {
      const review = process.env.PF_ATTEMPT_MODE === 'review'
      const run = await harness.run(review ? 'Read the authorized full transcript and prepare a complete question review draft.' : 'Read the synthetic transcript and prepare a cited draft.', {
        onNotification(notification) {
          if (notification.method === 'session.event') send({ type: 'progress', at_ms: Math.round(performance.now()) })
        },
      })
      const audit = JSON.parse(readFileSync(process.env.PF_AUDIT_FILE, 'utf8'))
      let draft
      try { draft = JSON.parse(run.finalResponse) } catch { draft = null }
      const fixture = process.env.PF_BRIDGE_URL ? await fetch(`${process.env.PF_BRIDGE_URL}/internal/agent/${review ? 'review/' : ''}grants/${encodeURIComponent(process.env.PF_BRIDGE_ATTEMPT)}/source?case_id=${encodeURIComponent(process.env.PF_BRIDGE_CASE_ID)}&owner_id=${encodeURIComponent(process.env.PF_OWNER_ID)}`,
        { headers: { Authorization: `Bearer ${process.env.PF_BRIDGE_TOKEN}` }, signal: AbortSignal.timeout(5000), redirect: 'error' })
        .then(async response => { if (!response.ok) throw new Error(`PF_BRIDGE_SCOPE_${response.status}`); return response.json() }) : localFixture
      const validation = review ? { usable: validReviewDraft(draft) } : validateDraft(draft, fixture)
      if (resultGate) { send({ type: 'result_ready' }); await resultGate.promise }
      send({ type: 'result', session_id: run.sessionId, draft, validation, finish_reason: run.finishReason,
        tool_results: run.events.filter(event => event.type === 'tool/result').map(event => ({ is_error: event.data?.message?.isError,
          code: event.data?.error?.code, text: event.data?.message?.content?.find(block => block.type === 'text')?.text?.slice(0, 100) })),
        model_calls: run.events.filter(event => event.type === 'request/header').length,
        tool_calls: run.events.filter(event => event.type === 'tool/call').length,
        terminal_error: run.events.findLast(event => event.type === 'turn/end')?.data?.reason?.error?.message,
        output_bytes: Buffer.byteLength(run.finalResponse, 'utf8'),
        registered_tools: audit.names, credential_present: audit.credential_present,
        inherited_provider_key: audit.inherited_provider_key })
    } catch (error) { send({ type: 'run_error', error: describeError(error) }) }
  }
  if (command.type === 'close') {
    try {
      if (process.env.PF_INJECT_CLOSE_FAILURE === '1') throw new Error('synthetic close refusal')
      await harness.close(); send({ type: 'closed' })
    }
    catch (error) { send({ type: 'close_error', error: describeError(error) }) }
  }
})
