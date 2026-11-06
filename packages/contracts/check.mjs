import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { validateDraft } from './validate-draft.mjs'

const fixture = JSON.parse(readFileSync(new URL('./synthetic-case.json', import.meta.url), 'utf8'))
const draft = { case_id: fixture.case_id, transcript_revision: fixture.transcript_revision,
  assessment: 'Synthetic', citation: { transcript_revision: fixture.transcript_revision, segment_id: 'seg-1', start_ms: 1000, end_ms: 4200 } }
assert.equal(validateDraft(draft, fixture).usable, true)
for (const malformed of [
  null, [], {},
  { ...draft, assessment: undefined },
  { ...draft, assessment: '   ' },
  { ...draft, assessment: 42 },
  { ...draft, case_id: [] },
  { ...draft, citation: null },
  { ...draft, citation: [] },
  { ...draft, citation: { ...draft.citation, segment_id: '' } },
  { ...draft, citation: { ...draft.citation, start_ms: '1000' } },
  { ...draft, citation: { ...draft.citation, end_ms: null } },
]) assert.equal(validateDraft(malformed, fixture).reason, 'invalid_draft_structure')
for (const changed of [
  { transcript_revision: 'old' },
  { citation: { ...draft.citation, segment_id: 'missing' } },
  { citation: { ...draft.citation, end_ms: 5000 } },
  { citation: { ...draft.citation, start_ms: 4200 } },
  { citation: { ...draft.citation, transcript_revision: 'old' } },
]) assert.equal(validateDraft({ ...draft, ...changed }, fixture).usable, false)
assert.equal(validateDraft(draft, { ...fixture, speaker_status: 'unknown' }).reason, 'speaker_pending_confirmation')
process.stdout.write('contract cases: 19 passed\n')
