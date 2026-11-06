import { mkdtempSync, rmSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { validateDraft } from '../../packages/contracts/validate-draft.mjs'
import { attemptFailure, createAttemptCleanup } from './cleanup.mjs'

const root = process.env.PF_DSH_SOURCE
if (!root) throw new Error('PF_DSH_SOURCE is required')
const scenario = process.argv[2] ?? 'success'
const fixture = JSON.parse(readFileSync(new URL('../../packages/contracts/synthetic-case.json', import.meta.url), 'utf8'))
if (scenario === 'unknown-speaker') fixture.speaker_status = 'unknown'
const sdkDirectory = resolve(root, 'packages/sdk/client')
const sdkManifest = JSON.parse(readFileSync(resolve(sdkDirectory, 'package.json'), 'utf8'))
const { DeepSeekHarness } = await import(pathToFileURL(resolve(sdkDirectory, sdkManifest.main)).href)
const home = mkdtempSync(resolve(tmpdir(), 'pitayaflow-dsh-'))
const childEnv = Object.fromEntries(['PATH', 'Path', 'SYSTEMROOT', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'APPDATA', 'LOCALAPPDATA', 'USERPROFILE'].filter(key => process.env[key]).map(key => [key, process.env[key]]))
childEnv.PF_DSH_SOURCE = root
childEnv.PF_SCENARIO = scenario
childEnv.PF_AUDIT_FILE = resolve(home, 'inventory.json')
const harness = new DeepSeekHarness({
  profile: scenario === 'startup-failure' ? 'missing-pf-profile' : 'sdk-minimal',
  patches: [resolve('packages/dsh-tools/controlled.patch.yml')],
  dshHome: home, cwd: process.cwd(), processCwd: process.cwd(), env: childEnv,
  provider: 'pf-scripted', model: 'pf-scripted', initializeTimeoutMs: 30000, requestTimeoutMs: 30000,
  shutdownTimeoutMs: 1000, disposeEofGraceMs: 3000, disposeGraceMs: 3000,
})
const cleanup = createAttemptCleanup({ home, close: () => harness.close(),
  removeHome: path => rmSync(path, { recursive: true, force: true }) })
/** @type {{scenario: string, validation?: {usable: boolean, reason: string}, registered_tools?: string[], inherited_secret_marker?: boolean, inherited_provider_key?: boolean, cleanup?: unknown, delivery_usable?: boolean, [key: string]: unknown}} */
let result
let primaryError
try {
  if (scenario === 'startup-failure') {
    let failure
    try { await harness.start() } catch (error) { failure = error }
    if (!failure || failure.name !== 'TransportClosedError' || !String(failure).includes('profile "missing-pf-profile"') || !String(failure).includes('exit code: 1')) throw new Error(`unexpected startup outcome: ${String(failure)}`)
    result = { scenario, expected_failure: String(failure).slice(0, 500) }
  } else {
    const run = await harness.run('Read the synthetic transcript and prepare a cited draft.')
    const audit = JSON.parse(readFileSync(resolve(home, 'inventory.json'), 'utf8'))
    let draft = null
    let parseFailure
    try { draft = JSON.parse(run.finalResponse) } catch (error) { parseFailure = String(error) }
    const toolResults = run.events.filter(event => event.type === 'tool/result')
    const rejectedForbidden = scenario === 'forbidden' && draft?.refused === 'tool_not_allowed' && toolResults.some(event => event.data?.error?.code === 'UNKNOWN_TOOL')
    result = { scenario, registered_tools: audit.names, inherited_secret_marker: audit.inherited_secret_marker,
      inherited_provider_key: audit.inherited_provider_key, finish_reason: run.finishReason, draft,
      validation: rejectedForbidden ? { usable: false, reason: 'forbidden_tool' } : validateDraft(draft, fixture),
      forbidden_tool_rejected: rejectedForbidden,
      diagnostic: draft ? undefined : { parse_failure: parseFailure, response: run.finalResponse.slice(0, 800) },
      tool_results: toolResults.map(event => ({ is_error: event.data?.message?.isError, code: event.data?.error?.code })),
      terminal: draft ? undefined : run.events.filter(event => event.type === 'turn/end' || event.type === 'step/end'),
      event_types: run.events.map(event => event.type).filter(Boolean) }
  }
} catch (error) {
  primaryError = error
}
const release = await cleanup()
if (release.home_removed) {
  await cleanup()
  process.stderr.write(`PF_HOME_REMOVED=${home}\n`)
} else process.stderr.write(`PF_HOME_RETAINED=${home}\n`)
if (primaryError) {
  result = { scenario, ...attemptFailure(primaryError, release) }
  process.exitCode = 1
}
if (!primaryError) result.cleanup = release
result.delivery_usable = !primaryError && release.exit_confirmed && release.home_removed && result.validation?.usable === true
if (!release.exit_confirmed || !release.home_removed) process.exitCode = 1
process.stdout.write(`${JSON.stringify(result)}\n`)
if (scenario !== 'startup-failure' && !primaryError) {
  const expected = { success: 'validated', forbidden: 'forbidden_tool', invalid: 'invalid_citation', 'unknown-speaker': 'speaker_pending_confirmation', malformed: 'invalid_draft_structure' }[scenario]
  if (result.validation?.reason !== expected || result.delivery_usable !== (scenario === 'success') || result.registered_tools?.length !== 1 || result.registered_tools[0] !== 'read_synthetic_transcript' || result.inherited_secret_marker || result.inherited_provider_key) process.exitCode = 1
}
