/** @typedef {{case_id: string, transcript_revision: string, assessment: string, citation: {transcript_revision: string, segment_id: string, start_ms: number, end_ms: number}}} ReviewDraft */

const record = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const text = value => typeof value === 'string' && value.trim().length > 0
const invalid = field => ({ usable: false, reason: 'invalid_draft_structure', field })

/** Parse an untrusted model result once; only a complete draft reaches evidence checks. */
export function parseDraft(value) {
  if (!record(value)) return { ok: false, result: invalid('draft') }
  for (const field of ['case_id', 'transcript_revision', 'assessment']) {
    if (!text(value[field])) return { ok: false, result: invalid(field) }
  }
  if (!record(value.citation)) return { ok: false, result: invalid('citation') }
  for (const field of ['transcript_revision', 'segment_id']) {
    if (!text(value.citation[field])) return { ok: false, result: invalid(`citation.${field}`) }
  }
  for (const field of ['start_ms', 'end_ms']) {
    if (!Number.isSafeInteger(value.citation[field]) || value.citation[field] < 0) return { ok: false, result: invalid(`citation.${field}`) }
  }
  return { ok: true, draft: /** @type {ReviewDraft} */ (value) }
}

/** Return a publication decision for an untrusted draft against one fixed transcript snapshot. */
export function validateDraft(value, fixture) {
  const parsed = parseDraft(value)
  if (!parsed.ok) return parsed.result
  const draft = parsed.draft
  if (draft.case_id !== fixture.case_id || draft.transcript_revision !== fixture.transcript_revision) return { usable: false, reason: 'revision_mismatch' }
  if (fixture.speaker_status !== 'confirmed') return { usable: false, reason: 'speaker_pending_confirmation' }
  const ref = draft.citation
  const segment = fixture.segments.find(item => item.id === ref.segment_id)
  if (!segment || ref.transcript_revision !== fixture.transcript_revision ||
      ref.start_ms < segment.start_ms || ref.end_ms > segment.end_ms || ref.start_ms >= ref.end_ms) return { usable: false, reason: 'invalid_citation' }
  return { usable: true, reason: 'validated' }
}
