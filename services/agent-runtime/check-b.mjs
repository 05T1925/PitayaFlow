import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { AttemptHost } from './attempt-host.mjs'

const dshSource = process.env.PF_DSH_SOURCE
assert.ok(dshSource, 'PF_DSH_SOURCE required')
assert.equal(execFileSync('git', ['rev-parse', 'HEAD'], { cwd: dshSource, encoding: 'utf8' }).trim(),
  'ce7073d51254c26cd63b22a60835b8f948deb447')
const make = (suffix, overrides = {}) => new AttemptHost({ dshSource, ownerId: `synthetic-owner-${suffix}`,
  caseId: suffix === 'b' ? 'PF-C005-synthetic-b' : 'PF-C001-synthetic', fixtureId: suffix,
  credentialMarker: `private-credential-${suffix}`, ...overrides })
const owned = []
const track = host => { owned.push(host); return host }
const close = async host => { const release = await host.close(); assert.equal(release.exit_confirmed, true); assert.equal(release.home_removed, true); return release }

try {
  const a = track(make('a'))
  const b = track(make('b'))
  assert.deepEqual((await Promise.all([a.start(), b.start()])).map(item => item.type), ['started', 'started'])
  const [ar, br] = await Promise.all([a.run(), b.run()])
  assert.equal(ar.type, 'result'); assert.equal(br.type, 'result')
  assert.notEqual(a.home, b.home); assert.notEqual(ar.session_id, br.session_id)
  assert.notEqual(ar.draft.case_id, br.draft.case_id)
  assert.notEqual(ar.draft.transcript_revision, br.draft.transcript_revision)
  for (const result of [ar, br]) {
    assert.equal(result.validation.usable, true)
    assert.deepEqual(result.registered_tools, ['read_synthetic_transcript'])
    assert.equal(result.credential_present, true)
    assert.equal(result.inherited_provider_key, false)
  }
  assert.ok(a.history.some(item => item.type === 'progress') && b.history.some(item => item.type === 'progress'))
  assert.equal(JSON.stringify([...a.history, ...b.history]).includes('private-credential-'), false)
  await Promise.all([close(a), close(b)])

  for (const override of [{ targetCaseId: 'PF-C005-synthetic-b' }, { targetOwnerId: 'synthetic-owner-b' }]) {
    const denied = track(make('a', override))
    assert.equal((await denied.start()).type, 'started')
    const output = await denied.run()
    assert.equal(output.type, 'result')
    assert.ok(output.tool_results.some(item => item.text?.includes('PF_SCOPE_DENIED')))
    assert.equal(output.validation.usable, false)
    await close(denied)
  }

  const waiting = track(make('a', { scenario: 'blocked' }))
  assert.equal((await waiting.start()).type, 'started')
  assert.equal((await waiting.run(100)).type, 'wait_timeout')
  assert.equal(waiting.inspect().state, 'running')
  const cancel = await waiting.requestCancel('cancelled')
  assert.equal(cancel.exit_confirmed, true)
  assert.equal(waiting.inspect().state, 'cancelled')
  assert.deepEqual(await waiting.requestCancel('cancelled'), cancel)

  const deadline = track(make('a', { scenario: 'blocked', deadlineMs: 5000 }))
  assert.equal((await deadline.start()).type, 'started')
  await deadline.run(7000)
  const deadlineRelease = await deadline.requestCancel('deadline')
  assert.equal(deadlineRelease.exit_confirmed, true)
  assert.equal(deadline.inspect().state, 'deadline')
  assert.equal(deadline.inspect().timer_active, false)

  const startupCancel = track(make('a'))
  const starting = startupCancel.start()
  const startupStop = startupCancel.requestCancel('cancelled')
  await Promise.all([starting, startupStop])
  assert.equal(startupCancel.inspect().state, 'cancelled')
  assert.equal(startupCancel.inspect().home_exists, false)

  const completed = track(make('a'))
  assert.equal((await completed.start()).type, 'started')
  assert.equal((await completed.run()).type, 'result')
  await completed.requestCancel('cancelled')
  assert.equal(completed.inspect().state, 'completed')

  const crashed = track(make('a', { scenario: 'crash' }))
  assert.equal((await crashed.start()).type, 'started')
  const crash = await crashed.run()
  assert.equal(crash.type, 'run_error')
  assert.match(crash.error.message, /exit code: 23/)
  await close(crashed)

  const forced = track(make('a', { injectCloseFailure: true }))
  assert.equal((await forced.start()).type, 'started')
  const forcedRelease = await forced.close()
  assert.equal(forcedRelease.forced, true)
  assert.equal(forcedRelease.exit_confirmed, true)
  assert.match(forcedRelease.close_error.message, /synthetic close refusal/)
  assert.equal(forcedRelease.home_removed, true)

  const modelCap = track(make('a', { maxModelCalls: 1 }))
  assert.equal((await modelCap.start()).type, 'started')
  const modelOutput = await modelCap.run()
  assert.equal(modelOutput.quota_reason, 'model_call_limit')
  assert.equal(modelOutput.delivery_usable, false)
  await close(modelCap)

  const toolCap = track(make('a', { maxToolCalls: 0 }))
  assert.equal((await toolCap.start()).type, 'started')
  const toolOutput = await toolCap.run()
  assert.equal(toolOutput.quota_reason, 'tool_call_limit')
  assert.equal(toolOutput.delivery_usable, false)
  await close(toolCap)

  const outputCap = track(make('a', { outputLimitBytes: 32 }))
  assert.equal((await outputCap.start()).type, 'started')
  const outputLimited = await outputCap.run()
  assert.equal(outputLimited.quota_reason, 'output_limit')
  assert.equal(outputCap.inspect().state, 'quota')
  await close(outputCap)

  process.stdout.write(JSON.stringify({ B1: 'pass', B2: 'pass', B3: 'pass', B4: 'pass', B5: 'pass',
    attempts: owned.length, example_release_ms: cancel.elapsed_ms }) + '\n')
} finally {
  for (const host of owned) if (host.child && host.exit === null) await host.requestCancel('cancelled')
}
