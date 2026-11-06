import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parseRunSpec, publicationDecision } from '../../packages/contracts/publication.mjs'
import { validateDraft } from '../../packages/contracts/validate-draft.mjs'
import { AttemptHost } from './attempt-host.mjs'
import { MemoryFacts } from './tests/memory-facts.mjs'

const fixture = JSON.parse(readFileSync(new URL('../../packages/contracts/synthetic-case.json', import.meta.url), 'utf8'))
const spec = { task_id: 'task-c', run_id: 'run-c', attempt_id: 'attempt-c1', owner_id: 'synthetic-owner-a',
  question_id: 'q-1', idempotency_key: 'publish-1', execution_epoch: 1, state_revision: 1,
  transcript_revision: 'rev-1', speaker_mapping_revision: 'speaker-1', material_revision: 'material-1',
  authorization_revision: 'auth-1', deadline_ms: 5000, max_model_calls: 4, max_tool_calls: 2, output_limit_bytes: 4096 }
const draft = { case_id: fixture.case_id, transcript_revision: fixture.transcript_revision, assessment: 'Synthetic queue answer',
  citation: { transcript_revision: 'rev-1', segment_id: 'seg-1', start_ms: 1000, end_ms: 4200 } }
const fresh = () => new MemoryFacts(spec, fixture)

assert.equal(parseRunSpec(null).reason, 'invalid_spec')
assert.equal(parseRunSpec({ ...spec, execution_epoch: '1' }).field, 'execution_epoch')
assert.equal(parseRunSpec(spec).ok, true)
assert.equal(publicationDecision(spec, { ...spec, owner_id: 'other-owner', granted: true }, draft, fixture).reason, 'scope_denied')
assert.equal(publicationDecision(spec, { ...spec, question_id: 'other-question', granted: true }, draft, fixture).reason, 'scope_denied')

const store = fresh()
const first = store.publish(spec, draft)
assert.equal(first.reason, 'published')
assert.deepEqual(store.publish(spec, draft), { publish: true, reason: 'duplicate', artifact_id: first.artifact_id })
assert.equal(store.published.size, 1)
assert.equal(store.publish(spec, { ...draft, assessment: 'Different' }).reason, 'idempotency_conflict')
store.nextAttempt('attempt-c2')
assert.equal(store.publish(spec, draft).reason, 'stale_attempt')
for (const action of ['cancel', 'delete', 'revoke']) {
  const changed = fresh(); changed[action]()
  assert.equal(changed.publish(spec, draft).reason, { cancel: 'cancelled', delete: 'deleted', revoke: 'authorization_revoked' }[action])
}
for (const change of [{ transcript_revision: 'rev-2' }, { speaker_mapping_revision: 'speaker-2' }, { material_revision: 'material-2' }]) {
  const changed = fresh(); changed.update(change)
  assert.equal(changed.publish(spec, draft).reason, 'stale_input')
}

const summaries = fresh()
const source = { id: 'seg-1', revision: 'rev-1', content_hash: 'synthetic-hash-1' }
const summary = { attempt_id: spec.attempt_id, execution_epoch: spec.execution_epoch, state_revision: 1,
  transcript_revision: 'rev-1', speaker_mapping_revision: 'speaker-1',
  material_revision: 'material-1', sources: [source], text: 'Synthetic summary' }
assert.equal(summaries.saveSummary(summary).reason, 'missing_source')
assert.equal(summaries.addSource(source, 'Synthetic source text').reason, 'created')
assert.equal(summaries.addSource(source, 'Synthetic source text').reason, 'duplicate')
assert.equal(summaries.addSource(source, 'Replacement text').reason, 'source_conflict')
assert.equal(summaries.resolveSource(source), 'Synthetic source text')
assert.equal(summaries.saveSummary(summary).usable, true)
assert.equal(summaries.readSummary().usable, true)
summaries.sources.clear()
assert.equal(summaries.readSummary().reason, 'missing_source')
summaries.addSource(source, 'Synthetic source text')
const delayed = Promise.withResolvers()
const late = delayed.promise.then(value => summaries.saveSummary(value))
summaries.update({ transcript_revision: 'rev-2' })
delayed.resolve(summary)
assert.equal((await late).reason, 'stale_summary')
assert.equal(summaries.readSummary().reason, 'stale_summary')
const differentAttempt = fresh(); differentAttempt.addSource(source, 'Synthetic source text')
differentAttempt.nextAttempt('attempt-c2')
assert.equal(differentAttempt.saveSummary(summary).reason, 'stale_summary')

const ordered = fresh()
const q1 = Promise.withResolvers(); const q2 = Promise.withResolvers()
const pending = [q1.promise.then(value => ordered.recordToolResult('q-1', 'call-1', value)),
  q2.promise.then(value => ordered.recordToolResult('q-2', 'call-2', value))]
q2.resolve('second'); await q2.promise
q1.resolve('first'); await Promise.all(pending)
assert.deepEqual(ordered.questionResults().map(item => [item.question_id, item.tool_call_id, item.value]),
  [['q-1', 'call-1', 'first'], ['q-2', 'call-2', 'second']])
assert.equal(ordered.recordToolResult('q-1', 'call-1', 'replacement').reason, 'duplicate_call')
assert.equal(ordered.replayReadOnly(spec, draft, 'wrong-key').reason, 'idempotency_conflict')
assert.equal(ordered.replayReadOnly(spec, draft, spec.idempotency_key).reason, 'executed')
assert.equal(ordered.replayReadOnly(spec, draft, spec.idempotency_key).reason, 'reused')
assert.equal(ordered.readOnlyReplays.size, 1)
ordered.nextAttempt('attempt-c2')
assert.equal(ordered.replayReadOnly(spec, draft, spec.idempotency_key).reason, 'stale_attempt')
ordered.noteExternalUnknown('provider-request-1')
assert.deepEqual(ordered.resumeExternal('provider-request-1'), { state: 'unknown_outcome', sends: 1 })

assert.equal(validateDraft(null, fixture).reason, 'invalid_draft_structure')
assert.equal(validateDraft({ ...draft, citation: { ...draft.citation, segment_id: 'missing' } }, fixture).reason, 'invalid_citation')
assert.equal(validateDraft(draft, { ...fixture, speaker_status: 'unknown' }).reason, 'speaker_pending_confirmation')

const dshSource = process.env.PF_DSH_SOURCE
assert.ok(dshSource, 'PF_DSH_SOURCE required')
async function realDraft() {
  const host = new AttemptHost({ dshSource, ownerId: spec.owner_id, caseId: fixture.case_id, fixtureId: 'a',
    credentialMarker: 'private-credential-c' })
  try {
    assert.equal((await host.start()).type, 'started')
    const output = await host.run()
    assert.equal(output.type, 'result')
    assert.equal(output.delivery_usable, true)
    assert.deepEqual(output.registered_tools, ['read_synthetic_transcript'])
    return output.draft
  } finally {
    const release = await host.close()
    assert.equal(release.exit_confirmed && release.home_removed, true)
  }
}
const realControl = await realDraft()
assert.equal(fresh().publish(spec, realControl).reason, 'published')
const realLate = await realDraft()
const cancelled = fresh(); cancelled.cancel()
assert.equal(cancelled.publish(spec, realLate).reason, 'cancelled')
const corrected = fresh(); corrected.update({ transcript_revision: 'rev-2' })
assert.equal(corrected.publish(spec, realLate).reason, 'stale_input')
assert.equal(publicationDecision(spec, corrected.current, realLate, fixture).publish, false)

process.stdout.write(JSON.stringify({ C1: 'pass', C2: 'pass', C3: 'pass', C4: 'pass', C5: 'pass',
  real_dsh_drafts: 2, published_artifacts: store.published.size }) + '\n')
